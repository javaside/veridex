package io.veridex.shared.outbox;

import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
public class OutboxWriter {

    private final OutboxEventRepository repository;
    private final JsonMapper objectMapper;
    private final ApplicationEventPublisher events;

    public OutboxWriter(OutboxEventRepository repository, JsonMapper objectMapper,
                        ApplicationEventPublisher events) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.events = events;
    }

    @Transactional
    public void record(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            repository.save(new OutboxEventEntity(aggregateType, aggregateId, eventType, json));
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot serialize outbox payload for " + eventType, e);
        }
        events.publishEvent(new OutboxCommitEvent());
    }
}
