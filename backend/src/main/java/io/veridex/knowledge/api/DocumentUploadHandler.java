package io.veridex.knowledge.api;

import io.veridex.audit.api.AuditRecorder;
import io.veridex.knowledge.application.DocumentService;
import io.veridex.knowledge.api.ObjectStorage;
import io.veridex.knowledge.domain.DocumentVersion;
import io.veridex.shared.infrastructure.RequestIds;
import io.veridex.shared.outbox.OutboxWriter;
import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DocumentUploadHandler {

    private final DocumentService documents;
    private final ObjectStorage storage;
    private final OutboxWriter outbox;
    private final AuditRecorder audit;

    public DocumentUploadHandler(DocumentService documents, ObjectStorage storage,
                                 OutboxWriter outbox, AuditRecorder audit) {
        this.documents = documents;
        this.storage = storage;
        this.outbox = outbox;
        this.audit = audit;
    }

    @Transactional
    public DocumentVersion upload(UUID actorId, UUID kbId, String filename, String contentType,
                                  byte[] content, HttpServletRequest request) {
        // content 来自 MultipartFile.getBytes()（≤50MB，内存可容纳），保证 sha256 与 put 用同一份字节
        String sha256 = sha256Hex(content);
        DocumentVersion version = documents.upload(actorId, kbId, filename, contentType, content.length, sha256);
        storage.put(version.getObjectKey(),
                new java.io.ByteArrayInputStream(content), contentType, content.length);

        outbox.record("document_version", version.getId(), "document.version.uploaded",
                Map.of("documentVersionId", version.getId().toString(),
                        "knowledgeBaseId", kbId.toString(),
                        "objectKey", version.getObjectKey(),
                        "filename", filename,
                        "contentType", contentType));
        audit.record(actorId, "document.upload", "document_version", version.getId(), RequestIds.current(request),
                Map.of("knowledgeBaseId", kbId.toString(), "filename", filename));
        return version;
    }

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
