package io.veridex.shared.observability;

public enum MetricName {
    RETRIEVAL_CHANNEL_FAILURE("veridex.retrieval.channel.failure"),
    RETRIEVAL_DEGRADATION("veridex.retrieval.degradation"),
    GENERATION_TOKENS("veridex.generation.tokens"),
    GENERATION_FIRST_TOKEN("veridex.generation.first_token"),
    GENERATION_OUTCOME("veridex.generation.outcome"),
    OUTBOX_PENDING("veridex.outbox.pending"),
    OUTBOX_OLDEST_UNPUBLISHED_AGE("veridex.outbox.oldest.unpublished.age"),
    OUTBOX_PUBLISH_FAILURE("veridex.outbox.publish.failure"),
    INGESTION_ACK("veridex.ingestion.ack"),
    INGESTION_REJECT("veridex.ingestion.reject"),
    INGESTION_PROCESSING_STUCK("veridex.ingestion.processing.stuck"),
    INDEXING_OPERATION("veridex.indexing.operation"),
    TRACE_BODY_CAPTURE("veridex.trace.body.capture"),
    TRACE_BODY_CAPTURE_SKIPPED("veridex.trace.body.capture.skipped"),
    TRACE_BODY_READ("veridex.trace.body.read"),
    TRACE_BODY_DENIED("veridex.trace.body.denied"),
    TRACE_BODY_CLEANUP("veridex.trace.body.cleanup");

    private final String value;

    MetricName(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
