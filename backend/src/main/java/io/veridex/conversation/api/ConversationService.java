package io.veridex.conversation.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话与消息端口（conversation 模块对外 API）。
 */
public interface ConversationService {

    ConversationView create(UUID userId, String title);

    List<ConversationView> listForUser(UUID userId);

    Optional<ConversationView> findOwned(UUID userId, UUID conversationId);

    MessageRecord addMessage(UUID conversationId, String role, String content, UUID queryRunId);

    List<MessageRecord> recentMessages(UUID conversationId, int limit);
}
