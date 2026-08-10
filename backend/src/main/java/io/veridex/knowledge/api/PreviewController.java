package io.veridex.knowledge.api;

import io.veridex.knowledge.application.DocumentService;
import io.veridex.knowledge.domain.DocumentVersion;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents/{documentId}/versions/{versionId}")
public class PreviewController {

    private final DocumentService documents;
    private final ObjectStorage storage;

    public PreviewController(DocumentService documents, ObjectStorage storage) {
        this.documents = documents;
        this.storage = storage;
    }

    @GetMapping(value = "/parsed", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> parsed(@PathVariable UUID documentId, @PathVariable UUID versionId) {
        DocumentVersion version = documents.findVersion(versionId);
        String key = version.getParsedObjectKey();
        if (key == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        try (var in = storage.get(key)) {
            return ResponseEntity.ok(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("failed to read parsed preview " + key, e);
        }
    }

    @GetMapping("/chunks")
    public List<Map<String, Object>> chunks(@PathVariable UUID documentId, @PathVariable UUID versionId) {
        DocumentVersion version = documents.findVersion(versionId);
        String key = version.getObjectKey() + ".chunks.json";
        try (var in = storage.get(key)) {
            return new tools.jackson.databind.json.JsonMapper()
                    .readValue(in.readAllBytes(), new tools.jackson.core.type.TypeReference<>() {
                    });
        } catch (Exception e) {
            throw new RuntimeException("failed to read chunks preview " + key, e);
        }
    }
}
