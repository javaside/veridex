package io.veridex.knowledge.api;

import io.veridex.knowledge.application.DocumentService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents/{documentId}/versions")
public class DocumentVersionsController {

    private final DocumentService documents;

    public DocumentVersionsController(DocumentService documents) {
        this.documents = documents;
    }

    @GetMapping
    public List<DocumentVersionView> list(@PathVariable UUID documentId) {
        return documents.listVersions(documentId).stream()
                .map(DocumentVersionView::from)
                .toList();
    }
}
