package io.veridex.indexing.application;

import io.veridex.indexing.api.ChunkIndexer;
import io.veridex.indexing.api.ChunkRecord;
import io.veridex.indexing.api.DraftRelease;
import io.veridex.indexing.api.IndexReleaseManager;
import io.veridex.indexing.api.PublishResult;
import io.veridex.indexing.api.ReleaseView;
import io.veridex.knowledge.api.DocumentVersionProcessing;
import io.veridex.knowledge.api.DocumentVersionProcessing.ReadyVersion;
import io.veridex.knowledge.api.ObjectStorage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 手动发布编排：把知识库当前全部 READY 文档版本固化为一个不可变快照索引并切 alias。
 */
@Service
public class KnowledgeBasePublishService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBasePublishService.class);

    private final DocumentVersionProcessing documents;
    private final ObjectStorage storage;
    private final ChunkIndexer indexer;
    private final IndexReleaseManager releases;
    private final JsonMapper jsonMapper;

    public KnowledgeBasePublishService(DocumentVersionProcessing documents, ObjectStorage storage,
                                       ChunkIndexer indexer, IndexReleaseManager releases,
                                       JsonMapper jsonMapper) {
        this.documents = documents;
        this.storage = storage;
        this.indexer = indexer;
        this.releases = releases;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public PublishResult publish(UUID knowledgeBaseId) {
        List<ReadyVersion> ready = documents.listReadyVersions(knowledgeBaseId);
        if (ready.isEmpty()) {
            throw new IllegalStateException("no READY document versions to publish");
        }
        DraftRelease draft = releases.createDraft(knowledgeBaseId,
                "veridex-" + knowledgeBaseId + "-active");
        try {
            releases.prepare(draft.releaseId());
            int totalChunks = 0;
            for (ReadyVersion version : ready) {
                List<ChunkRecord> chunks = readChunks(version.objectKey());
                indexer.index(knowledgeBaseId, version.versionId(), chunks,
                        draft.indexName(), draft.releaseId());
                totalChunks += chunks.size();
            }
            releases.setStats(draft.releaseId(), ready.size(), totalChunks);
            releases.publish(draft.releaseId());
            releases.markSnapshotDocuments(draft.releaseId(),
                    ready.stream().map(ReadyVersion::versionId).toList());
        } catch (Exception e) {
            try {
                releases.discardDraft(draft.releaseId());
            } catch (Exception cleanup) {
                log.error("failed to discard draft {}", draft.releaseId(), cleanup);
            }
            throw e;
        }
        ReleaseView view = releases.listReleases(knowledgeBaseId).stream()
                .filter(r -> r.releaseId().equals(draft.releaseId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("published release not found"));
        return new PublishResult(view, documents.countNotReady(knowledgeBaseId));
    }

    @SuppressWarnings("unchecked")
    private List<ChunkRecord> readChunks(String objectKey) {
        try (InputStream in = storage.get(objectKey + ".chunks.json")) {
            List<Map<String, Object>> raw = jsonMapper.readValue(in, List.class);
            return raw.stream()
                    .map(m -> new ChunkRecord(
                            ((Number) m.get("index")).intValue(),
                            String.valueOf(m.get("text")),
                            String.valueOf(m.get("title")),
                            String.valueOf(m.get("structurePath"))))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read chunk manifest for " + objectKey, e);
        }
    }
}
