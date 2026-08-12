package io.veridex.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.indexing.api.ChunkRecord;
import io.veridex.indexing.application.SearchIndexGateway;
import io.veridex.retrieval.api.SearchHit;
import io.veridex.retrieval.infrastructure.OpenSearchRetrievalReader;
import io.veridex.shared.infrastructure.embedding.DeterministicEmbeddingModel;
import io.veridex.support.OpenSearchContainerConfiguration;
import io.veridex.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@Import({OpenSearchContainerConfiguration.class, DeterministicEmbeddingModel.class})
class OpenSearchRetrievalReaderTest extends PostgresIntegrationTest {

    @Autowired OpenSearchRetrievalReader reader;
    @Autowired SearchIndexGateway gateway;
    @Autowired DeterministicEmbeddingModel embeddings;

    private static final UUID KB = UUID.randomUUID();
    private static final UUID VER = UUID.randomUUID();
    private static final UUID RELEASE = UUID.randomUUID();

    private String index;
    private String alias;

    @BeforeEach
    void seed() {
        index = "veridex-retrieval-test-" + System.nanoTime();
        alias = "veridex-retrieval-active-" + System.nanoTime();
        gateway.createIndex(index, 128);
        gateway.aliasTo(alias, index);
        gateway.indexChunks(index, KB, VER, RELEASE, List.of(
                new ChunkRecord(0, "员工请假需提前两个工作日提交申请", "请假制度", "1"),
                new ChunkRecord(1, "年假最长不超过十五个工作日", "请假制度", "1.1"),
                new ChunkRecord(2, "工资于每月十号发放", "薪酬规定", "2")));
    }

    @Test
    void bm25FindsKeywordMatch() {
        var hits = reader.bm25(alias, KB, "请假申请", 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).channel()).isEqualTo(SearchHit.Channel.BM25);
        assertThat(hits.get(0).text()).contains("请假");
    }

    @Test
    void vectorFindsExactTextChunkFirst() {
        // DeterministicEmbeddingModel 是字节哈希：相同文本必得相同向量 → kNN 距离 0 → 必排第一
        var hits = reader.vector(alias, KB, "工资于每月十号发放", 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).text()).isEqualTo("工资于每月十号发放");
        assertThat(hits.get(0).channel()).isEqualTo(SearchHit.Channel.VECTOR);
    }

    @Test
    void bothChannelsReturnKnowledgeBaseScopedHits() {
        var bm25 = reader.bm25(alias, KB, "工资", 5);
        var vec = reader.vector(alias, KB, "工资 发放", 5);
        assertThat(bm25).allMatch(h -> h.knowledgeBaseId().equals(KB));
        assertThat(vec).allMatch(h -> h.knowledgeBaseId().equals(KB));
    }
}
