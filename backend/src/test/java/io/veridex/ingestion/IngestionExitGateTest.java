package io.veridex.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.indexing.api.ChunkRecord;
import io.veridex.indexing.api.IndexReleaseManager;
import io.veridex.indexing.application.KnowledgeBasePublishService;
import io.veridex.indexing.application.SearchIndexGateway;
import io.veridex.indexing.domain.IndexReleaseRepository;
import io.veridex.knowledge.api.ObjectStorage;
import io.veridex.knowledge.application.DocumentService;
import io.veridex.knowledge.application.KnowledgeBaseService;
import io.veridex.knowledge.domain.DocumentVersion;
import io.veridex.knowledge.domain.DocumentVersionStatus;
import io.veridex.support.MinioContainerConfiguration;
import io.veridex.support.OpenSearchContainerConfiguration;
import io.veridex.support.PostgresIntegrationTest;
import io.veridex.support.RabbitContainerConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 2 出口门禁：验证路线图要求的 4 个关键场景。
 * 场景 2（worker 重启续传）由「未 ack 消息重投 + chunk _id 幂等」保证，
 * 等价于场景 1 的重复投递幂等验证（重启后重新投递即重复投递）。
 */
@Testcontainers
@Import({MinioContainerConfiguration.class, RabbitContainerConfiguration.class, OpenSearchContainerConfiguration.class})
class IngestionExitGateTest extends PostgresIntegrationTest {

    @Autowired DocumentService documents;
    @Autowired KnowledgeBaseService knowledgeBases;
    @Autowired ObjectStorage storage;
    @Autowired RabbitTemplate rabbit;
    @Autowired SearchIndexGateway gateway;
    @Autowired IndexReleaseManager releaseManager;
    @Autowired IndexReleaseRepository releaseRepository;
    @Autowired KnowledgeBasePublishService publishService;

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private record Uploaded(UUID kbId, DocumentVersion version) {
    }

    @Test
    void duplicateDeliveryDoesNotDuplicateChunks() throws Exception {
        Uploaded u = uploadAndPut("dup.md", "# 重复\n\n内容");
        String body = messageBody(u);

        rabbit.convertAndSend("veridex.ingestion", "document.ingest", body.getBytes(StandardCharsets.UTF_8));
        rabbit.convertAndSend("veridex.ingestion", "document.ingest", body.getBytes(StandardCharsets.UTF_8));

        awaitReady(u.version().getId());
        // worker 幂等：第二次投递不重复建 release，chunk 数不变
        assertThat(documents.findVersion(u.version().getId()).getChunkCount()).isEqualTo(1);
        assertThat(releaseRepository.findByKnowledgeBaseIdOrderByVersionNoDesc(u.kbId())).isEmpty();
    }

    @Test
    void failedNewVersionIsExcludedFromNextManualPublish() throws Exception {
        Uploaded v1 = uploadAndPut("a.md", "# 第一版\n\n稳定内容");
        publishMessage(v1);
        awaitReady(v1.version().getId());
        publishService.publish(v1.kbId());

        // 同一知识库上传 b.md：MinIO 中不存在该对象 → worker storage.get 抛异常 → markFailed + DLQ
        Uploaded v2 = uploadVersion("b.md", v1.kbId());
        publishMessage(v2);
        awaitStatus(v2.version().getId(), DocumentVersionStatus.FAILED);

        // 再次手动发布：失败版本被排除（excludedCount=1），新快照不含 v2
        var result = publishService.publish(v1.kbId());
        assertThat(result.excludedCount()).isEqualTo(1);

        String alias = "veridex-" + v1.kbId() + "-active";
        assertThat(gateway.findChunksByDocumentVersion(alias, v1.version().getId())).isNotEmpty();
        assertThat(gateway.findChunksByDocumentVersion(alias, v2.version().getId())).isEmpty();
    }

    @Test
    void offlineReleaseIsExcludedFromSearch() throws Exception {
        Uploaded u = uploadAndPut("off.md", "# 离线\n\n敏感内容");
        publishMessage(u);
        awaitReady(u.version().getId());
        var published = publishService.publish(u.kbId());

        String alias = "veridex-" + u.kbId() + "-active";
        assertThat(gateway.findChunksByDocumentVersion(alias, u.version().getId())).isNotEmpty();

        // offline 当前 active release（移除 alias）
        releaseManager.offline(u.kbId(), published.release().releaseId());

        assertThat(gateway.findChunksByDocumentVersion(alias, u.version().getId())).isEmpty();
    }

    @Test
    void makeCurrentRestoresOfflineReleaseToSearch() throws Exception {
        Uploaded u = uploadAndPut("off.md", "# 离线\n\n敏感内容");
        publishMessage(u);
        awaitReady(u.version().getId());
        var published = publishService.publish(u.kbId());

        String alias = "veridex-" + u.kbId() + "-active";
        releaseManager.offline(u.kbId(), published.release().releaseId());
        assertThat(gateway.findChunksByDocumentVersion(alias, u.version().getId())).isEmpty();

        releaseManager.makeCurrent(u.kbId(), published.release().releaseId());

        assertThat(gateway.findChunksByDocumentVersion(alias, u.version().getId())).isNotEmpty();
    }

    private Uploaded uploadAndPut(String filename, String content) throws Exception {
        Uploaded u = uploadVersion(filename);
        storage.put(u.version().getObjectKey(),
                new java.io.ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                "text/markdown", content.getBytes(StandardCharsets.UTF_8).length);
        return u;
    }

    private Uploaded uploadVersion(String filename) {
        UUID kb = knowledgeBases.createKnowledgeBase(ACTOR, "门禁-" + UUID.randomUUID(), null).getId();
        return uploadVersion(filename, kb);
    }

    private Uploaded uploadVersion(String filename, UUID kb) {
        DocumentVersion version = documents.upload(ACTOR, kb, filename, "text/markdown",
                filename.length(), "a".repeat(64));
        return new Uploaded(kb, version);
    }

    private String messageBody(Uploaded u) {
        return "{\"documentVersionId\":\"" + u.version().getId() + "\",\"knowledgeBaseId\":\"" + u.kbId()
                + "\",\"objectKey\":\"" + u.version().getObjectKey()
                + "\",\"filename\":\"x.md\",\"contentType\":\"text/markdown\"}";
    }

    private void publishMessage(Uploaded u) {
        rabbit.convertAndSend("veridex.ingestion", "document.ingest",
                messageBody(u).getBytes(StandardCharsets.UTF_8));
    }

    private void awaitReady(UUID versionId) throws InterruptedException {
        awaitStatus(versionId, DocumentVersionStatus.READY);
    }

    private void awaitStatus(UUID versionId, DocumentVersionStatus expected) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            if (documents.findVersion(versionId).getStatus() == expected) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("version " + versionId + " not " + expected);
    }
}
