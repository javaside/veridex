package io.veridex.ingestion;

import io.veridex.knowledge.api.ObjectStorage;
import io.veridex.knowledge.application.DocumentService;
import io.veridex.knowledge.application.KnowledgeBaseService;
import io.veridex.knowledge.domain.DocumentVersionStatus;
import io.veridex.support.MinioContainerConfiguration;
import io.veridex.support.OpenSearchContainerConfiguration;
import io.veridex.support.PostgresIntegrationTest;
import io.veridex.support.RabbitContainerConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@Import({MinioContainerConfiguration.class, RabbitContainerConfiguration.class, OpenSearchContainerConfiguration.class})
class IngestionWorkerIntegrationTest extends PostgresIntegrationTest {

    @Autowired DocumentService documents;
    @Autowired KnowledgeBaseService knowledgeBases;
    @Autowired ObjectStorage storage;
    @Autowired RabbitTemplate rabbit;

    @Test
    void uploadedDocumentIsProcessedToReadyAndIndexed() throws Exception {
        UUID actor = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var kb = knowledgeBases.createKnowledgeBase(actor, "集成测试库", null);
        var version = documents.upload(actor, kb.getId(), "guide.md", "text/markdown", 11L, "a".repeat(64));
        storage.put(version.getObjectKey(),
                new java.io.ByteArrayInputStream("# 指南\n\n正文内容".getBytes()),
                "text/markdown", 11L);

        String body = "{\"documentVersionId\":\"" + version.getId() + "\",\"knowledgeBaseId\":\""
                + kb.getId() + "\",\"objectKey\":\"" + version.getObjectKey()
                + "\",\"filename\":\"guide.md\",\"contentType\":\"text/markdown\"}";
        rabbit.convertAndSend("veridex.ingestion", "document.ingest", body.getBytes());

        awaitStatus(version.getId(), DocumentVersionStatus.READY);
    }

    private void awaitStatus(UUID versionId, DocumentVersionStatus expected) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            var version = documents.findVersion(versionId);
            if (version.getStatus() == expected) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("document version did not reach " + expected);
    }
}
