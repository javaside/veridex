package io.veridex.shared.outbox;

import io.veridex.shared.observability.RabbitPropagationContext;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_event")
public class OutboxEventEntity {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 200)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion = 1;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "last_error")
    private String lastError;

    @Column(length = 100)
    private String traceparent;

    @Column(length = 512)
    private String tracestate;

    @Column(name = "request_id", length = 100)
    private String requestId;

    protected OutboxEventEntity() {
    }

    public OutboxEventEntity(String aggregateType, UUID aggregateId, String eventType, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
    }

    public UUID getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public UUID getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getTraceparent() { return traceparent; }
    public String getTracestate() { return tracestate; }
    public String getRequestId() { return requestId; }
    public String getLastError() { return lastError; }
    public RabbitPropagationContext getPropagationContext() {
        return new RabbitPropagationContext(traceparent, tracestate, requestId);
    }
    public void setPropagationContext(RabbitPropagationContext context) {
        if (context != null) {
            this.traceparent = context.traceparent();
            this.tracestate = context.tracestate();
            this.requestId = context.requestId();
        }
    }
    public void markPublished() { this.publishedAt = Instant.now(); }
    public void recordFailure(String errorCode) {
        this.attempts++;
        this.lastError = errorCode;
    }
}
