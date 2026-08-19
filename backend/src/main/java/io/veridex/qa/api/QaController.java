package io.veridex.qa.api;

import io.veridex.conversation.api.ConversationService;
import io.veridex.conversation.api.ConversationView;
import io.veridex.conversation.api.MessageRecord;
import io.veridex.iam.api.CurrentActor;
import io.veridex.qa.application.QuestionAnsweringService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

/**
 * 员工问答 API：SSE 流式问答（真实 token 流，设计 §4.3）、会话列表、会话消息与反馈占位。
 * Controller 只做事件名映射；服务端超时由 spring.mvc.async.request-timeout 兜底（≥ chat.timeout + 余量）。
 */
@RestController
@RequestMapping("/api/qa")
public class QaController {

    private final QuestionAnsweringService service;
    private final ConversationService conversations;

    public QaController(QuestionAnsweringService service, ConversationService conversations) {
        this.service = service;
        this.conversations = conversations;
    }

    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<QaEvent>> ask(@RequestBody AskRequest request) {
        UUID userId = CurrentActor.id();
        return service.ask(userId, request)
                .map(event -> ServerSentEvent.<QaEvent>builder()
                        .event(eventName(event))
                        .data(event)
                        .build());
    }

    @GetMapping("/conversations")
    public List<ConversationView> conversations() {
        return conversations.listForUser(CurrentActor.id());
    }

    @GetMapping("/conversations/{id}/messages")
    public List<MessageRecord> messages(@PathVariable UUID id) {
        UUID userId = CurrentActor.id();
        if (conversations.findOwned(userId, id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "会话不存在或无权访问");
        }
        return conversations.recentMessages(id, 1000);
    }

    @PostMapping("/feedback")
    public ResponseEntity<Void> feedback() {
        // Phase 4 完整坏例闭环落库；本轮前端按钮占位，返回 204
        return ResponseEntity.noContent().build();
    }

    private static String eventName(QaEvent event) {
        return switch (event) {
            case QaEvent.RunStarted r -> "run.started";
            case QaEvent.RetrievalCompleted r -> "retrieval.completed";
            case QaEvent.AnswerDelta r -> "answer.delta";
            case QaEvent.CitationAvailable r -> "citation.available";
            case QaEvent.AnswerCompleted r -> "answer.completed";
            case QaEvent.AnswerRefused r -> "answer.refused";
            case QaEvent.RunFailed r -> "run.failed";
        };
    }
}
