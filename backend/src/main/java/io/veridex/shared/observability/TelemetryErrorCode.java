package io.veridex.shared.observability;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeoutException;

public enum TelemetryErrorCode {
    OPENSEARCH_TIMEOUT("opensearch_timeout"),
    EMBEDDING_UNAVAILABLE("embedding_unavailable"),
    DUAL_RETRIEVAL_FAILED("dual_retrieval_failed"),
    MODEL_ERROR("model_error"),
    STORAGE_ERROR("storage_error"),
    PARSE_ERROR("parse_error"),
    OUTBOX_PUBLISH_FAILED("outbox_publish_failed"),
    UNKNOWN("unknown");

    private final String wireValue;

    TelemetryErrorCode(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Set<String> persistedQueryRunCodes() {
        return Set.of(OPENSEARCH_TIMEOUT.name(), EMBEDDING_UNAVAILABLE.name(), DUAL_RETRIEVAL_FAILED.name(),
                MODEL_ERROR.name(), STORAGE_ERROR.name(), PARSE_ERROR.name(), UNKNOWN.name(), "TRACE_FAILURE");
    }

    public static TelemetryErrorCode classify(Exception exception) {
        if (exception instanceof TimeoutException) {
            return OPENSEARCH_TIMEOUT;
        }
        if (exception instanceof IOException) {
            return STORAGE_ERROR;
        }
        return UNKNOWN;
    }
}
