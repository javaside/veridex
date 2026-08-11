package io.veridex.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.indexing.api.ChunkRecord;
import io.veridex.indexing.application.SearchIndexGateway;
import io.veridex.shared.infrastructure.embedding.DeterministicEmbeddingModel;
import io.veridex.support.OpenSearchContainerConfiguration;
import io.veridex.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@Import({OpenSearchContainerConfiguration.class, DeterministicEmbeddingModel.class})
class OpenSearchIndexGatewayTest extends PostgresIntegrationTest {

    @Autowired SearchIndexGateway gateway;
    @Autowired DeterministicEmbeddingModel embeddings;

    @Test
    void createAliasAndIndexChunksIdempotently() {
        String index = "veridex-test-release-1";
        String alias = "veridex-test-active";
        UUID kb = UUID.randomUUID();
        UUID docVersion = UUID.randomUUID();
        UUID release = UUID.randomUUID();

        gateway.deleteIndex(index);
        gateway.createIndex(index, 128);
        gateway.aliasTo(alias, index);

        var chunks = List.of(
                new ChunkRecord(0, "第一条内容", "标题A", "1"),
                new ChunkRecord(1, "第二条内容", "标题B", "1.1"));
        gateway.indexChunks(index, kb, docVersion, release, chunks);
        // 重复投递：再次写入相同 chunks，必须仍是 2 条
        gateway.indexChunks(index, kb, docVersion, release, chunks);

        var found = gateway.findChunksByDocumentVersion(index, docVersion);
        assertThat(found).hasSize(2);

        assertThat(gateway.indexExists(alias)).isTrue();
        gateway.deleteIndex(index);
    }

    @Test
    void switchingAliasMovesItFromOldIndexToNewIndex() {
        String oldIndex = "veridex-alias-old";
        String newIndex = "veridex-alias-new";
        String alias = "veridex-alias-active";
        UUID kb = UUID.randomUUID();
        UUID oldVersion = UUID.randomUUID();
        UUID newVersion = UUID.randomUUID();

        gateway.deleteIndex(oldIndex);
        gateway.deleteIndex(newIndex);
        gateway.createIndex(oldIndex, 128);
        gateway.createIndex(newIndex, 128);
        gateway.indexChunks(oldIndex, kb, oldVersion, UUID.randomUUID(),
                List.of(new ChunkRecord(0, "旧内容", "旧", "1")));
        gateway.indexChunks(newIndex, kb, newVersion, UUID.randomUUID(),
                List.of(new ChunkRecord(0, "新内容", "新", "1")));
        gateway.aliasTo(alias, oldIndex);

        gateway.aliasTo(alias, newIndex);

        assertThat(gateway.findChunksByDocumentVersion(alias, oldVersion)).isEmpty();
        assertThat(gateway.findChunksByDocumentVersion(alias, newVersion)).hasSize(1);
        gateway.deleteIndex(oldIndex);
        gateway.deleteIndex(newIndex);
    }

    @Test
    void deterministicEmbeddingHasFixedDimension() {
        assertThat(embeddings.dimensions()).isEqualTo(128);
        assertThat(embeddings.embed("测试文本")).hasSize(128);
    }
}
