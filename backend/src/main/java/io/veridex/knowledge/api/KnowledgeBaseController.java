package io.veridex.knowledge.api;

import io.veridex.iam.api.CurrentActor;
import io.veridex.knowledge.application.KnowledgeBaseService;
import io.veridex.knowledge.domain.KnowledgeBase;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBases;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBases) {
        this.knowledgeBases = knowledgeBases;
    }

    @PostMapping
    public ResponseEntity<KnowledgeBaseView> create(@RequestBody CreateKnowledgeBaseRequest body) {
        KnowledgeBase kb = knowledgeBases.createKnowledgeBase(CurrentActor.id(), body.name(), body.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(KnowledgeBaseView.from(kb));
    }

    @GetMapping
    public List<KnowledgeBaseView> list() {
        UUID actorId = CurrentActor.id();
        return knowledgeBases.listViewable(actorId).stream()
                .map(KnowledgeBaseView::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<KnowledgeBaseView> get(@PathVariable UUID id) {
        UUID actorId = CurrentActor.id();
        if (!knowledgeBases.canView(id, actorId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(KnowledgeBaseView.from(knowledgeBases.findByIdOrThrow(id)));
    }
}
