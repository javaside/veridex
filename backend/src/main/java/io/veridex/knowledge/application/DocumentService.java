package io.veridex.knowledge.application;

import io.veridex.knowledge.api.DocumentVersionProcessing;
import io.veridex.knowledge.api.KnowledgeBaseAuthorization;
import io.veridex.knowledge.domain.Document;
import io.veridex.knowledge.domain.DocumentRepository;
import io.veridex.knowledge.domain.DocumentVersion;
import io.veridex.knowledge.domain.DocumentVersionRepository;
import io.veridex.knowledge.domain.DocumentVersionStatus;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class DocumentService implements DocumentVersionProcessing {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "txt", "md");
    private static final long MAX_BYTES = 50L * 1024 * 1024;

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final KnowledgeBaseAuthorization authorization;

    public DocumentService(DocumentRepository documents, DocumentVersionRepository versions,
                           KnowledgeBaseAuthorization authorization) {
        this.documents = documents;
        this.versions = versions;
        this.authorization = authorization;
    }

    public DocumentVersion upload(UUID actorId, UUID kbId, String filename, String contentType,
                                  long sizeBytes, String sha256Hex) {
        String ext = extensionOf(filename);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("unsupported file type: " + ext);
        }
        if (sizeBytes > MAX_BYTES) {
            throw new IllegalArgumentException("file exceeds 50MB limit");
        }
        if (!authorization.canManage(kbId, actorId)) {
            throw new SecurityException("no MANAGE grant on knowledge base " + kbId);
        }
        Document document = documents.findByKnowledgeBaseIdAndFilename(kbId, filename)
                .orElseGet(() -> documents.save(new Document(kbId, filename, contentType, sizeBytes, actorId)));
        int nextVersion = (int) versions.countByDocumentId(document.getId()) + 1;
        return versions.save(new DocumentVersion(document.getId(), nextVersion,
                objectKey(kbId, document.getId(), nextVersion, filename), sha256Hex));
    }

    public void markProcessing(UUID versionId) {
        DocumentVersion version = require(versionId);
        version.markProcessing();
        versions.save(version);
    }

    public void markReady(UUID versionId, int chunkCount) {
        DocumentVersion version = require(versionId);
        version.markReady(chunkCount);
        versions.save(version);
    }

    public void markFailed(UUID versionId, String reason) {
        DocumentVersion version = require(versionId);
        version.markFailed(reason);
        versions.save(version);
    }

    public void setParsedObjectKey(UUID versionId, String key) {
        DocumentVersion version = require(versionId);
        version.setParsedObjectKey(key);
        versions.save(version);
    }

    public List<Document> listDocuments(UUID kbId) {
        return documents.findByKnowledgeBaseIdOrderByCreatedAtDesc(kbId);
    }

    public List<DocumentVersion> listVersions(UUID documentId) {
        return versions.findByDocumentIdOrderByVersionNoDesc(documentId);
    }

    public DocumentVersion findVersion(UUID versionId) {
        return require(versionId);
    }

    @Override
    public String findVersionStatus(UUID versionId) {
        return require(versionId).getStatus().name();
    }

    @Override
    public List<ReadyVersion> listReadyVersions(UUID knowledgeBaseId) {
        return documents.findByKnowledgeBaseIdOrderByCreatedAtDesc(knowledgeBaseId).stream()
                .map(doc -> versions.findFirstByDocumentIdOrderByVersionNoDesc(doc.getId()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(v -> v.getStatus() == DocumentVersionStatus.READY)
                .map(v -> new ReadyVersion(v.getId(), v.getObjectKey(), v.getChunkCount()))
                .toList();
    }

    @Override
    public int countNotReady(UUID knowledgeBaseId) {
        long ready = listReadyVersions(knowledgeBaseId).size();
        return (int) (documents.findByKnowledgeBaseIdOrderByCreatedAtDesc(knowledgeBaseId).size() - ready);
    }

    private DocumentVersion require(UUID versionId) {
        return versions.findById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("unknown document version " + versionId));
    }

    private static String objectKey(UUID kbId, UUID documentId, int versionNo, String filename) {
        return kbId + "/" + documentId + "/v" + versionNo + "/" + filename;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
