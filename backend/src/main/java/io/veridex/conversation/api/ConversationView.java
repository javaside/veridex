package io.veridex.conversation.api;

import java.time.Instant;
import java.util.UUID;

public record ConversationView(UUID id, String title, Instant createdAt) {
}
