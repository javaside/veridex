package io.veridex.shared;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.shared.outbox.OutboxWriter;
import io.veridex.support.MinioContainerConfiguration;
import io.veridex.support.PostgresIntegrationTest;
import io.veridex.support.RabbitContainerConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
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
