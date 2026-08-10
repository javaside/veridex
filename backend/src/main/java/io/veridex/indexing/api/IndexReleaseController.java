package io.veridex.indexing.api;

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

    public IndexReleaseController(IndexReleaseManager releases) {
        this.releases = releases;
    }

    @GetMapping
    public List<ReleaseView> list(@PathVariable UUID kbId) {
        return releases.listReleases(kbId);
    }

    @PostMapping("/{releaseId}/publish")
    public ResponseEntity<Void> publish(@PathVariable UUID releaseId) {
        releases.publish(releaseId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{releaseId}/rollback")
    public ResponseEntity<Void> rollback(@PathVariable UUID releaseId) {
        releases.rollback(releaseId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{releaseId}/offline")
    public ResponseEntity<Void> offline(@PathVariable UUID releaseId) {
        releases.offline(releaseId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{releaseId}/delete")
    public ResponseEntity<Void> delete(@PathVariable UUID releaseId) {
        releases.delete(releaseId);
        return ResponseEntity.noContent().build();
    }
}
