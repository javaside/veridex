package io.veridex.shared.outbox;

import io.veridex.shared.infrastructure.messaging.RabbitTopology;
import io.veridex.shared.observability.OutboxObservability;
import io.veridex.shared.observability.RabbitContextPropagation;
import io.veridex.shared.observability.TelemetryErrorCode;
import io.veridex.shared.observability.TelemetryOutcome;
import io.veridex.shared.observability.TelemetryTag;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository repository;
    private final RabbitTemplate rabbit;
    private final OutboxObservability observability;

    public OutboxPublisher(OutboxEventRepository repository, RabbitTemplate rabbit,
                           OutboxObservability observability) {
        this.repository = repository;
        this.rabbit = rabbit;
        this.observability = observability;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishPending(OutboxCommitEvent event) {
        List<OutboxEventEntity> pending = repository.findUnpublished();
        for (OutboxEventEntity outboxEvent : pending) {
            var observation = observability.startPublish();
            try {
                byte[] payload = outboxEvent.getPayload().getBytes(StandardCharsets.UTF_8);
                rabbit.convertAndSend(
                        RabbitTopology.INGESTION_EXCHANGE,
                        routingKeyFor(outboxEvent.getEventType()),
                        payload,
                        message -> {
                            RabbitContextPropagation.writeHeaders(outboxEvent.getPropagationContext(),
                                    outboxEvent.getId(), message.getMessageProperties());
                            return message;
                        });
                outboxEvent.markPublished();
                observation.success(TelemetryTag.outboxOutcome(TelemetryOutcome.Outbox.SUCCESS));
            } catch (Exception e) {
                log.warn("outbox publish failed; error_code=outbox_publish_failed");
                outboxEvent.recordFailure(TelemetryErrorCode.OUTBOX_PUBLISH_FAILED.wireValue());
                observability.recordPublishFailure();
                observation.failure(TelemetryErrorCode.OUTBOX_PUBLISH_FAILED);
            } finally {
                observation.close();
                saveEventState(outboxEvent);
            }
        }
    }

    private void saveEventState(OutboxEventEntity event) {
        try {
            repository.save(event);
        } catch (RuntimeException ignored) {
            log.warn("outbox state persistence failed; error_code=outbox_state_persist_failed");
        }
    }

    private static String routingKeyFor(String eventType) {
        return "document.ingest";
    }
}
