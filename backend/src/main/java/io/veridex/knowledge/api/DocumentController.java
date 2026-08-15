package io.veridex.knowledge.api;

import io.veridex.iam.api.CurrentActor;
import io.veridex.knowledge.application.DocumentService;
import io.veridex.knowledge.domain.Document;
import io.veridex.knowledge.domain.DocumentVersion;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/knowledge-bases/{kbId}/documents")
public class DocumentController {

    private final DocumentService documents;
    private final DocumentUploadHandler uploadHandler;

    public DocumentController(DocumentService documents, DocumentUploadHandler uploadHandler) {
        this.documents = documents;
        this.uploadHandler = uploadHandler;
    }

    @PostMapping
    public ResponseEntity<DocumentVersionView> upload(@PathVariable UUID kbId,
                                                      @RequestParam("file") MultipartFile file,
                                                      HttpServletRequest request)
            throws IOException {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        String filename = file.getOriginalFilename() == null ? "untitled" : file.getOriginalFilename();
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        DocumentVersion version = uploadHandler.upload(CurrentActor.id(), kbId, filename, contentType,
                file.getBytes(), request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(DocumentVersionView.from(version));
    }

    @GetMapping
    public List<DocumentView> list(@PathVariable UUID kbId) {
        return documents.listDocuments(kbId).stream()
                .map(DocumentView::from)
                .toList();
    }
}
