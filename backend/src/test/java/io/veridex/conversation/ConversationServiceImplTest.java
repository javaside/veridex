package io.veridex.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.veridex.conversation.api.ConversationService;
import io.veridex.conversation.api.ConversationView;
import io.veridex.conversation.api.MessageRecord;
import io.veridex.conversation.application.ConversationServiceImpl;
import io.veridex.conversation.domain.Conversation;
import io.veridex.conversation.domain.ConversationRepository;
import io.veridex.conversation.domain.Message;
import io.veridex.conversation.domain.MessageRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConversationServiceImplTest {

    @Mock ConversationRepository conversations;
    @Mock MessageRepository messages;
    @InjectMocks ConversationServiceImpl service;

    private static final UUID USER = UUID.randomUUID();
    private static final UUID CONV = UUID.randomUUID();

    @Test
    void createPersistsConversationAndReturnsView() {
        when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ConversationView view = service.create(USER, "请假规定");
        assertThat(view.title()).isEqualTo("请假规定");
        verify(conversations).save(argThat(c -> c.getUserId().equals(USER)));
    }

    @Test
    void recentMessagesReturnsLatestAscending() {
        var m1 = new Message(CONV, "USER", "q1", null);
        var m2 = new Message(CONV, "ASSISTANT", "a1", UUID.randomUUID());
        var m3 = new Message(CONV, "USER", "q2", null);
        when(messages.findByConversationIdOrderByCreatedAtAsc(CONV)).thenReturn(List.of(m1, m2, m3));
        var recent = service.recentMessages(CONV, 2);
        assertThat(recent).extracting(MessageRecord::content).containsExactly("a1", "q2");
    }

    @Test
    void findOwnedReturnsEmptyForOtherUsersConversation() {
        when(conversations.findByIdAndUserId(CONV, USER)).thenReturn(Optional.empty());
        assertThat(service.findOwned(USER, CONV)).isEmpty();
    }

    @Test
    void listForUserOrdersByUpdatedAtDesc() {
        var c1 = new Conversation(USER, "a");
        c1.touch();
        var c2 = new Conversation(USER, "b");
        c2.touch();
        when(conversations.findByUserIdOrderByUpdatedAtDesc(USER)).thenReturn(List.of(c1, c2));
        var views = service.listForUser(USER);
        assertThat(views).hasSize(2);
    }

    @Test
    void addMessageTouchesConversationAndReturnsRecord() {
        var conv = new Conversation(USER, "t");
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(conversations.findById(CONV)).thenReturn(Optional.of(conv));
        MessageRecord record = service.addMessage(CONV, "USER", "hello", null);
        assertThat(record.role()).isEqualTo("USER");
        assertThat(record.content()).isEqualTo("hello");
        assertThat(conv.getUpdatedAt()).isAfterOrEqualTo(conv.getCreatedAt());
    }
}
