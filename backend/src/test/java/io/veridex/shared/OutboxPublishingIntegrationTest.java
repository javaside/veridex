package io.veridex.shared;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.shared.observability.RabbitPropagationContext;
import io.veridex.shared.outbox.OutboxCommitEvent;
import io.veridex.shared.outbox.OutboxEventEntity;
import io.veridex.shared.outbox.OutboxEventRepository;
import io.veridex.shared.outbox.OutboxPublisher;
import io.veridex.shared.outbox.OutboxWriter;
import io.veridex.support.MinioContainerConfiguration;
import io.veridex.support.PostgresIntegrationTest;
import io.veridex.support.RabbitContainerConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@TestPropertySource(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@Import({MinioContainerConfiguration.class, RabbitContainerConfiguration.class})
class OutboxPublishingIntegrationTest extends PostgresIntegrationTest {

    @Autowired OutboxWriter outboxWriter;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin amqpAdmin;
    @Autowired OutboxEventRepository outboxEvents;
    @Autowired OutboxPublisher outboxPublisher;

    @BeforeEach
    void clearOutboxAndQueue() {
        rabbit.execute(channel -> {
            channel.queuePurge("ingestion.document");
            return null;
        });
        outboxEvents.deleteAll();
    }

    @Test
    void transactionCommitPublishesRecordedEventToRabbit() throws Exception {
        UUID versionId = UUID.randomUUID();
        outboxWriter.record("document_version", versionId, "document.version.uploaded",
                Map.of("documentVersionId", versionId.toString()));

        QueueInformation info = awaitQueueDepth("ingestion.document", 1);
        assertThat(info.getMessageCount()).isEqualTo(1);

        var message = rabbit.receive("ingestion.document", 5000);
        assertThat(message).isNotNull();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        assertThat(body).contains(versionId.toString());
    }

    @Test
    void persistedContextSurvivesClearedCurrentSpanBeforePublish() throws Exception {
        String expectedTraceparent =
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        OutboxEventEntity event = new OutboxEventEntity("document_version", UUID.randomUUID(),
                "document.version.uploaded", "{\"documentVersionId\":\"persisted\"}");
        event.setPropagationContext(new RabbitPropagationContext(
                expectedTraceparent, "vendor=value", "request-persisted"));
        UUID eventId = outboxEvents.save(event).getId();
        OutboxEventEntity persisted = outboxEvents.findById(eventId).orElseThrow();
        assertThat(persisted.getPropagationContext()).isEqualTo(event.getPropagationContext());
        org.slf4j.MDC.clear();

        outboxPublisher.publishPending(new OutboxCommitEvent());
        var message = rabbit.receive("ingestion.document", 5000);
        assertThat(message).isNotNull();
        assertThat((Object) message.getMessageProperties().getHeader("traceparent")).isEqualTo(expectedTraceparent);
        assertThat((Object) message.getMessageProperties().getHeader("x-request-id")).isEqualTo("request-persisted");
        assertThat((Object) message.getMessageProperties().getHeader("x-outbox-event-id")).isNotNull();
        assertThat((Object) message.getMessageProperties().getHeader("x-veridex-telemetry-version")).isEqualTo("1");
    }

    private QueueInformation awaitQueueDepth(String queue, int minDepth) throws InterruptedException {
        QueueInformation info = null;
        for (int i = 0; i < 20; i++) {
            info = amqpAdmin.getQueueInfo(queue);
            if (info != null && info.getMessageCount() >= minDepth) {
                return info;
            }
            Thread.sleep(200);
        }
        return info;
    }
}
