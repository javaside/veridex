package io.veridex.knowledge.api;

import io.veridex.iam.api.CurrentActor;
import io.veridex.knowledge.application.DocumentService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文档版本反向定位：给定 documentVersionId，一次查询返回其所属的知识库与文档。
 * 供评测证据回显使用，避免前端遍历全量 KB/文档造成 N+1 请求风暴。
 */
@RestController
@RequestMapping("/api/documents/versions")
public class DocumentVersionLocationController {

    private final DocumentService documents;

    public DocumentVersionLocationController(DocumentService documents) {
        this.documents = documents;
    }

    @GetMapping("/{versionId}/location")
    public ResponseEntity<DocumentService.LocatedVersion> locate(@PathVariable UUID versionId) {
        return documents.locateVersion(versionId, CurrentActor.id())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
