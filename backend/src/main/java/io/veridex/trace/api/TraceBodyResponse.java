package io.veridex.trace.api;

import java.time.Instant;
import java.util.UUID;

public record TraceBodyResponse(UUID runId, Instant createdAt, Instant expiresAt, Object envelope) {
}
