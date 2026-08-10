package io.veridex.shared.outbox;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository repository;
    private final RabbitTemplate rabbit;
    private final JsonMapper objectMapper;

    public OutboxPublisher(OutboxEventRepository repository, RabbitTemplate rabbit, JsonMapper objectMapper) {
        this.repository = repository;
        this.rabbit = rabbit;
        this.objectMapper = objectMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishPending(OutboxCommitEvent event) {
        List<OutboxEventEntity> pending = repository.findUnpublished();
        for (OutboxEventEntity outboxEvent : pending) {
            try {
                // payload 原样透传（调用方已序列化为合法 JSON 对象文本）
                rabbit.convertAndSend(
                        io.veridex.shared.infrastructure.messaging.RabbitTopology.INGESTION_EXCHANGE,
                        routingKeyFor(outboxEvent.getEventType()),
                        outboxEvent.getPayload().getBytes(StandardCharsets.UTF_8));
                outboxEvent.markPublished();
            } catch (Exception e) {
                log.warn("failed to publish outbox event {}: {}", outboxEvent.getId(), e.getMessage());
                outboxEvent.recordFailure(e.getMessage());
            } finally {
                repository.save(outboxEvent);
            }
        }
    }

    private static String routingKeyFor(String eventType) {
        return "document.ingest";
    }
}
