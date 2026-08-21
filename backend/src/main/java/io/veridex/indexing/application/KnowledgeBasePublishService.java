package io.veridex.indexing.application;

import io.veridex.indexing.api.ChunkIndexer;
import io.veridex.indexing.api.ChunkRecord;
import io.veridex.indexing.api.DraftRelease;
import io.veridex.indexing.api.IndexReleaseManager;
import io.veridex.knowledge.api.DocumentVersionProcessing;
import io.veridex.knowledge.api.DocumentVersionProcessing.ReadyVersion;
import io.veridex.knowledge.api.ObjectStorage;
import io.veridex.shared.observability.ObservationName;
import io.veridex.shared.observability.TelemetryErrorCode;
import io.veridex.shared.observability.TelemetryOutcome;
import io.veridex.shared.observability.TelemetryTag;
import io.veridex.shared.observability.VeridexObservability;
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
 *
 * <p>拆成两步以根治「超大知识库发布阻塞 HTTP 请求」：
 * <ul>
 *   <li>{@link #beginPublish(UUID)} 在短事务内校验可发布文档、创建草稿并置为
 *       {@code PUBLISHING}，立即返回不可变任务快照；</li>
 *   <li>{@link #runPublish(PublishTask)} 在后台线程执行真正的索引与 alias 切换，
 *       无长事务（每个 {@link IndexReleaseManager} 调用各自提交）。</li>
 * </ul>
 */
@Service
public class KnowledgeBasePublishService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBasePublishService.class);

    private final DocumentVersionProcessing documents;
    private final ObjectStorage storage;
    private final ChunkIndexer indexer;
    private final IndexReleaseManager releases;
    private final JsonMapper jsonMapper;
    private final VeridexObservability observability;

    public KnowledgeBasePublishService(DocumentVersionProcessing documents, ObjectStorage storage,
                                       ChunkIndexer indexer, IndexReleaseManager releases,
                                       JsonMapper jsonMapper) {
        this(documents, storage, indexer, releases, jsonMapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeBasePublishService(DocumentVersionProcessing documents, ObjectStorage storage,
                                       ChunkIndexer indexer, IndexReleaseManager releases,
                                       JsonMapper jsonMapper, VeridexObservability observability) {
        this.documents = documents;
        this.storage = storage;
        this.indexer = indexer;
        this.releases = releases;
        this.jsonMapper = jsonMapper;
        this.observability = observability;
    }

    /**
     * 同步入口（事务内）：校验并创建草稿，置为 PUBLISHING，返回后台执行所需的不可变快照。
     * 不执行任何慢操作（不碰 OpenSearch/Ollama），因此可以安全地阻塞 HTTP 请求线程。
     */
    @Transactional
    public PublishTask beginPublish(UUID knowledgeBaseId) {
        List<ReadyVersion> ready = documents.listReadyVersions(knowledgeBaseId);
        if (ready.isEmpty()) {
            throw new IllegalStateException("no READY document versions to publish");
        }
        DraftRelease draft = releases.createDraft(knowledgeBaseId,
                "veridex-" + knowledgeBaseId + "-active");
        releases.markPublishing(draft.releaseId());
        return new PublishTask(knowledgeBaseId, draft, ready);
    }

    /**
     * 后台执行：无事务编排，索引全部 chunk 后发布并固化快照文档清单。
     * 失败时丢弃草稿并抛出（调用方决定同步传播或异步吞掉）。
     */
    public void runPublish(PublishTask task) {
        var observation = observability == null ? null : observability.start(ObservationName.INDEXING_PUBLISH);
        try {
            releases.prepare(task.draft().releaseId());
            int totalChunks = 0;
            for (ReadyVersion version : task.ready()) {
                List<ChunkRecord> chunks = readChunks(version.objectKey());
                indexer.index(task.knowledgeBaseId(), version.versionId(), chunks,
                        task.draft().indexName(), task.draft().releaseId());
                totalChunks += chunks.size();
            }
            releases.setStats(task.draft().releaseId(), task.ready().size(), totalChunks);
            releases.publish(task.draft().releaseId());
            releases.markSnapshotDocuments(task.draft().releaseId(),
                    task.ready().stream().map(ReadyVersion::versionId).toList());
        } catch (Exception e) {
            try {
                releases.discardDraft(task.draft().releaseId());
            } catch (Exception cleanup) {
                log.warn("indexing draft cleanup failed; error_code=indexing_cleanup_failed");
            }
            finishFailure(observation);
            throw e;
        }
        if (observation != null) {
            observation.success(TelemetryTag.indexingOutcome(TelemetryOutcome.Indexing.SUCCESS));
            observation.close();
        }
    }

    /**
     * 后台执行所需的不可变任务快照：包含知识库 id、已创建草稿，以及发布开始时
     * 固化的 READY 文档版本清单（避免后台执行期间文档状态漂移）。
     */
    public record PublishTask(UUID knowledgeBaseId, DraftRelease draft, List<ReadyVersion> ready) {
    }

    private static void finishFailure(VeridexObservability.ObservationScope observation) {
        if (observation != null) {
            observation.failure(TelemetryErrorCode.INDEXING_PUBLISH_FAILED);
            observation.close();
        }
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
