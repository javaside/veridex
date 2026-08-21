package io.veridex.indexing.application;

import io.veridex.indexing.api.IndexReleaseManager;
import io.veridex.indexing.api.PublishResult;
import io.veridex.indexing.api.ReleaseView;
import io.veridex.indexing.application.KnowledgeBasePublishService.PublishTask;
import io.veridex.knowledge.api.DocumentVersionProcessing;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 发布协调器：把「创建草稿」与「执行索引」切到不同线程，避免大知识库发布阻塞 HTTP 请求。
 *
 * <ul>
 *   <li>{@link #start(UUID)}：同步创建草稿并立即返回 {@code PUBLISHING} 视图，后台线程执行；</li>
 *   <li>{@link #publishAndWait(UUID)}：同步执行到完成，供一次性全量重建（reindex）等 CLI 场景使用。</li>
 * </ul>
 */
@Component
public class PublishCoordinator {

    private static final Logger log = LoggerFactory.getLogger(PublishCoordinator.class);

    private final KnowledgeBasePublishService publisher;
    private final IndexReleaseManager releases;
    private final DocumentVersionProcessing documents;
    private final Executor executor;

    public PublishCoordinator(KnowledgeBasePublishService publisher, IndexReleaseManager releases,
                              DocumentVersionProcessing documents,
                              @Qualifier("indexingPublishExecutor") Executor executor) {
        this.publisher = publisher;
        this.releases = releases;
        this.documents = documents;
        this.executor = executor;
    }

    /**
     * 异步发布：创建草稿（PUBLISHING）后立即返回该草稿视图，真正的索引在后台线程执行。
     */
    public ReleaseView start(UUID knowledgeBaseId) {
        PublishTask task = publisher.beginPublish(knowledgeBaseId);
        executor.execute(() -> runQuietly(task));
        return viewOf(knowledgeBaseId, task.draft().releaseId());
    }

    /**
     * 同步发布：执行到完成（成功发布或失败抛出），供 reindex 等一次性批量场景使用。
     */
    public PublishResult publishAndWait(UUID knowledgeBaseId) {
        PublishTask task = publisher.beginPublish(knowledgeBaseId);
        publisher.runPublish(task);
        ReleaseView view = viewOf(knowledgeBaseId, task.draft().releaseId());
        return new PublishResult(view, documents.countNotReady(knowledgeBaseId));
    }

    private void runQuietly(PublishTask task) {
        try {
            publisher.runPublish(task);
        } catch (RuntimeException e) {
            // 观测性边界：只记知识库 id 与异常类名，不落发布/文档标识或内容。
            log.warn("indexing publish failed: kb={} error={}", task.knowledgeBaseId(),
                    e.getClass().getSimpleName());
        }
    }

    private ReleaseView viewOf(UUID knowledgeBaseId, UUID releaseId) {
        return releases.listReleases(knowledgeBaseId).stream()
                .filter(r -> r.releaseId().equals(releaseId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("publishing release not found"));
    }
}
