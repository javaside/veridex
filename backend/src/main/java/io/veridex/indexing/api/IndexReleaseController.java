package io.veridex.indexing.api;

import io.veridex.iam.api.CurrentActor;
import io.veridex.indexing.application.KnowledgeBasePublishService;
import io.veridex.knowledge.api.KnowledgeBaseAuthorization;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases/{kbId}/releases")
public class IndexReleaseController {

    private final IndexReleaseManager releases;
    private final KnowledgeBasePublishService publishService;
    private final KnowledgeBaseAuthorization authorization;

    public IndexReleaseController(IndexReleaseManager releases,
                                  KnowledgeBasePublishService publishService,
                                  KnowledgeBaseAuthorization authorization) {
        this.releases = releases;
        this.publishService = publishService;
        this.authorization = authorization;
    }

    @GetMapping
    public List<ReleaseView> list(@PathVariable UUID kbId) {
        if (!authorization.canView(kbId, CurrentActor.id())) {
            throw new SecurityException("no VIEW grant on knowledge base " + kbId);
        }
        return releases.listReleases(kbId);
    }

    @PostMapping("/publish")
    public ResponseEntity<PublishResult> publish(@PathVariable UUID kbId) {
        requireManage(kbId);
        return ResponseEntity.ok(publishService.publish(kbId));
    }

    @PostMapping("/{releaseId}/rollback")
    public ResponseEntity<Void> rollback(@PathVariable UUID kbId, @PathVariable UUID releaseId) {
        requireManage(kbId);
        releases.rollback(kbId, releaseId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{releaseId}/offline")
    public ResponseEntity<Void> offline(@PathVariable UUID kbId, @PathVariable UUID releaseId) {
        requireManage(kbId);
        releases.offline(kbId, releaseId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{releaseId}/delete")
    public ResponseEntity<Void> delete(@PathVariable UUID kbId, @PathVariable UUID releaseId) {
        requireManage(kbId);
        releases.delete(kbId, releaseId);
        return ResponseEntity.noContent().build();
    }

    private void requireManage(UUID kbId) {
        if (!authorization.canManage(kbId, CurrentActor.id())) {
            throw new SecurityException("no MANAGE grant on knowledge base " + kbId);
        }
    }
}
