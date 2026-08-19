package io.veridex.shared.observability;

public final class TelemetryOutcome {

    private TelemetryOutcome() {
    }

    public enum Qa {
        COMPLETED,
        REFUSED,
        FAILED,
        CANCELLED
    }

    public enum Conversation {
        NEW,
        EXISTING
    }

    public enum Retrieval {
        SUCCESS,
        DEGRADED,
        FAILED
    }

    public enum Generation {
        SUCCESS,
        ERROR,
        REFUSED,
        CANCELLED
    }

    public enum Outbox {
        SUCCESS,
        ERROR
    }

    public enum Ingestion {
        SUCCESS,
        ALREADY_READY,
        FAILED
    }

    public enum IngestionStage {
        MESSAGE_DECODE,
        STATUS_CHECK,
        STORAGE_READ,
        PARSE,
        CHUNK,
        ARTIFACT_WRITE,
        STATE_UPDATE,
        AUDIT,
        ACK,
        UNKNOWN
    }

    public enum Indexing {
        SUCCESS,
        ERROR
    }

    public enum TraceBodySkipReason {
        TOO_LARGE,
        WRITER_FAILURE,
        POLICY_DISABLED
    }

    public enum TraceBodyOutcome {
        SUCCESS,
        NOT_FOUND,
        DECRYPT_FAILED,
        DENIED,
        FAILURE
    }

    public enum TraceBodyDeniedReason {
        INVALID_REASON,
        UNAUTHORIZED
    }

    public enum TraceBodyCleanupReason {
        EXPIRED,
        QUERY_RUN_SCRUB,
        MAINTENANCE_FAILURE,
        INVALID_CONFIGURATION
    }
}
