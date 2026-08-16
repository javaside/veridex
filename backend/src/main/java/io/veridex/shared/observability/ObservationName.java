package io.veridex.shared.observability;

public enum ObservationName {
    QA_RUN("veridex.qa.run"),
    RETRIEVAL_RUN("veridex.retrieval.run"),
    GENERATION_MODEL("veridex.generation.model"),
    OUTBOX_PUBLISH("veridex.outbox.publish"),
    INGESTION_RUN("veridex.ingestion.run"),
    INDEXING_PUBLISH("veridex.indexing.publish");

    private final String value;

    ObservationName(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
