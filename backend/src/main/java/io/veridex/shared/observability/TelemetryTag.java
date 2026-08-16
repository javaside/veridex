package io.veridex.shared.observability;

import java.util.Locale;

public final class TelemetryTag {

    private final String key;
    private final String value;

    private TelemetryTag(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String key() {
        return key;
    }

    public String value() {
        return value;
    }

    public static TelemetryTag qaOutcome(TelemetryOutcome.Qa value) {
        return fixed("outcome", value);
    }

    public static TelemetryTag conversation(TelemetryOutcome.Conversation value) {
        return fixed("conversation", value);
    }

    public static TelemetryTag retrievalOutcome(TelemetryOutcome.Retrieval value) {
        return fixed("outcome", value);
    }

    public static TelemetryTag generationOutcome(TelemetryOutcome.Generation value) {
        return fixed("outcome", value);
    }

    public static TelemetryTag outboxOutcome(TelemetryOutcome.Outbox value) {
        return fixed("outcome", value);
    }

    public static TelemetryTag ingestionOutcome(TelemetryOutcome.Ingestion value) {
        return fixed("result", value);
    }

    public static TelemetryTag ingestionStage(TelemetryOutcome.IngestionStage value) {
        return fixed("failure_stage", value);
    }

    public static TelemetryTag indexingOutcome(TelemetryOutcome.Indexing value) {
        return fixed("outcome", value);
    }

    public static TelemetryTag skipReason(TelemetryOutcome.TraceBodySkipReason value) {
        return fixed("reason", value);
    }

    public static TelemetryTag errorCode(TelemetryErrorCode value) {
        return new TelemetryTag("error_code", value.wireValue());
    }

    static TelemetryTag model(String value) {
        return new TelemetryTag("model", value);
    }

    static TelemetryTag provider(String value) {
        return new TelemetryTag("provider", value);
    }

    private static TelemetryTag fixed(String key, Enum<?> value) {
        return new TelemetryTag(key, value.name().toLowerCase(Locale.ROOT));
    }
}
