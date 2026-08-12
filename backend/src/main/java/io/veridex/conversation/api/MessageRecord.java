package io.veridex.conversation.api;

import java.util.UUID;

public record MessageRecord(UUID id, String role, String content, UUID queryRunId) {
}
