package io.veridex.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.veridex.shared.observability.OutboxObservability;
import io.veridex.shared.observability.RabbitPropagationContext;
import io.veridex.shared.observability.TelemetryErrorCode;
import io.veridex.shared.outbox.OutboxCommitEvent;
import io.veridex.shared.outbox.OutboxEventEntity;
import io.veridex.shared.outbox.OutboxEventRepository;
import io.veridex.shared.outbox.OutboxPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class OutboxPublisherTest {

    private static final String TRACEPARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    void publishesOriginalPayloadBytesWithPersistedContextHeaders() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        OutboxEventEntity event = event("{\"text\":\"a b\",\"number\":1}");
        event.setPropagationContext(new RabbitPropagationContext(TRACEPARENT, "vendor=value", "request-123"));
        when(repository.findUnpublished()).thenReturn(List.of(event));
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<MessagePostProcessor> processor = ArgumentCaptor.forClass(MessagePostProcessor.class);

        publisher(repository, rabbit, new SimpleMeterRegistry()).publishPending(new OutboxCommitEvent());

        verify(rabbit).convertAndSend(anyString(), anyString(), body.capture(), processor.capture());
        assertThat(body.getValue()).isEqualTo(event.getPayload().getBytes(StandardCharsets.UTF_8));
        Message processed = processor.getValue().postProcessMessage(new Message(body.getValue()));
        assertThat((Object) processed.getMessageProperties().getHeader("traceparent")).isEqualTo(TRACEPARENT);
        assertThat((Object) processed.getMessageProperties().getHeader("x-request-id")).isEqualTo("request-123");
        assertThat((Object) processed.getMessageProperties().getHeader("x-outbox-event-id"))
                .isEqualTo(event.getId().toString());
    }

    @Test
    void failedEventDoesNotPreventPublishingLaterEvents() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        when(repository.findUnpublished()).thenReturn(List.of(event("{\"first\":true}"), event("{\"second\":true}")));
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("secret broker detail");
            }
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(byte[].class), any(MessagePostProcessor.class));
        AtomicInteger saves = new AtomicInteger();
        when(repository.save(any(OutboxEventEntity.class))).thenAnswer(invocation -> {
            if (saves.getAndIncrement() == 0) {
                throw new IllegalStateException("secret database detail");
            }
            return invocation.getArgument(0);
        });

        publisher(repository, rabbit, new SimpleMeterRegistry()).publishPending(new OutboxCommitEvent());

        assertThat(attempts).hasValue(2);
        assertThat(saves).hasValue(2);
    }

    @Test
    void gaugesExposeValuesAndFailOpenWhenRepositoryQueriesFail() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        when(repository.countByPublishedAtIsNull()).thenReturn(3L);
        when(repository.findOldestUnpublishedAt()).thenReturn(Optional.of(Instant.now().minusSeconds(30)));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        new OutboxObservability(repository, meters,
                new io.veridex.shared.observability.VeridexObservability(
                        meters, ObservationRegistry.create()));

        assertThat(meters.get("veridex.outbox.pending").gauge().value()).isEqualTo(3);
        assertThat(meters.get("veridex.outbox.oldest.unpublished.age").gauge().value())
                .isBetween(29.0, 31.0);

        when(repository.countByPublishedAtIsNull()).thenThrow(new IllegalStateException("database detail"));
        when(repository.findOldestUnpublishedAt()).thenThrow(new IllegalStateException("database detail"));
        assertThat(meters.get("veridex.outbox.pending").gauge().value()).isZero();
        assertThat(meters.get("veridex.outbox.oldest.unpublished.age").gauge().value()).isZero();
    }

    @Test
    void failureTelemetryUsesFixedCodeAndDoesNotPersistExceptionMessage() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        OutboxEventEntity event = event("{\"documentVersionId\":\"sensitive\"}");
        when(repository.findUnpublished()).thenReturn(List.of(event));
        org.mockito.Mockito.doThrow(new IllegalStateException("secret broker detail"))
                .when(rabbit).convertAndSend(anyString(), anyString(), any(byte[].class), any(MessagePostProcessor.class));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        publisher(repository, rabbit, meters).publishPending(new OutboxCommitEvent());

        assertThat(event.getLastError()).isEqualTo(TelemetryErrorCode.OUTBOX_PUBLISH_FAILED.wireValue());
        assertThat(meters.get("veridex.outbox.publish").tag("outcome", "error")
                .tag("error_code", "outbox_publish_failed").timer().count()).isEqualTo(1);
    }

    private static OutboxPublisher publisher(OutboxEventRepository repository, RabbitTemplate rabbit,
                                             SimpleMeterRegistry meters) {
        return new OutboxPublisher(repository, rabbit,
                new OutboxObservability(repository, meters,
                        new io.veridex.shared.observability.VeridexObservability(
                                meters, ObservationRegistry.create())));
    }

    private static OutboxEventEntity event(String payload) {
        return new OutboxEventEntity("document_version", UUID.randomUUID(), "document.version.uploaded", payload);
    }
}
