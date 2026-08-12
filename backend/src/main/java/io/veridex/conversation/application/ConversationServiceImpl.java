package io.veridex.conversation.application;

import io.veridex.conversation.api.ConversationService;
import io.veridex.conversation.api.ConversationView;
import io.veridex.conversation.api.MessageRecord;
import io.veridex.conversation.domain.Conversation;
import io.veridex.conversation.domain.ConversationRepository;
import io.veridex.conversation.domain.Message;
import io.veridex.conversation.domain.MessageRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ConversationServiceImpl implements ConversationService {

    private final ConversationRepository conversations;
    private final MessageRepository messages;

    public ConversationServiceImpl(ConversationRepository conversations, MessageRepository messages) {
        this.conversations = conversations;
        this.messages = messages;
    }

    @Override
    public ConversationView create(UUID userId, String title) {
        Conversation c = conversations.save(new Conversation(userId, title));
        return new ConversationView(c.getId(), c.getTitle(), c.getCreatedAt());
    }

    @Override
    public List<ConversationView> listForUser(UUID userId) {
        return conversations.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(c -> new ConversationView(c.getId(), c.getTitle(), c.getCreatedAt()))
                .toList();
    }

    @Override
    public Optional<ConversationView> findOwned(UUID userId, UUID conversationId) {
        return conversations.findByIdAndUserId(conversationId, userId)
                .map(c -> new ConversationView(c.getId(), c.getTitle(), c.getCreatedAt()));
    }

    @Override
    public MessageRecord addMessage(UUID conversationId, String role, String content, UUID queryRunId) {
        Message m = messages.save(new Message(conversationId, role, content, queryRunId));
        conversations.findById(conversationId).ifPresent(c -> {
            c.touch();
            conversations.save(c);
        });
        return new MessageRecord(m.getId(), m.getRole(), m.getContent(), m.getQueryRunId());
    }

    @Override
    public List<MessageRecord> recentMessages(UUID conversationId, int limit) {
        var all = messages.findByConversationIdOrderByCreatedAtAsc(conversationId);
        return all.stream().skip(Math.max(0, all.size() - limit))
                .map(m -> new MessageRecord(m.getId(), m.getRole(), m.getContent(), m.getQueryRunId()))
                .toList();
    }
}
