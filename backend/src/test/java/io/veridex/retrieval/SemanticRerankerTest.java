package io.veridex.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.retrieval.api.SearchHit;
import io.veridex.retrieval.application.SemanticReranker;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SemanticRerankerTest {

    private static SearchHit hit(String text, Double vectorScore) {
        return new SearchHit(UUID.randomUUID(), UUID.randomUUID(), 0, "t", "1", text,
                SearchHit.Channel.BM25, 1.0, vectorScore);
    }

    @Test
    void ranksHigherVectorSimilarityFirstAcrossKnowledgeBases() {
        var netty = hit("Netty 的 Channel 生命周期有 ChannelUnregistered/Registered/Active/Inactive", 0.71);
        var kafka = hit("Kafka 主题、分区与消费者组监控指标说明", 0.45);
        var spring = hit("Spring Boot 自动配置与属性外置", 0.44);

        var result = new SemanticReranker().rerank(List.of(kafka, netty, spring), "Netty Channel 生命周期");

        assertThat(result).hasSize(3);
        assertThat(result.get(0).text()).contains("Netty");
    }

    @Test
    void placesBm25OnlyHitsAfterVectorHits() {
        var withVector = hit("有向量分数", 0.5);
        var bm25Only = hit("仅BM25命中", null);

        var result = new SemanticReranker().rerank(List.of(bm25Only, withVector), "问题");

        assertThat(result.get(0)).isEqualTo(withVector);
        assertThat(result.get(1)).isEqualTo(bm25Only);
    }

    @Test
    void noOpForSingleHitOrNullList() {
        assertThat(new SemanticReranker().rerank(List.of(hit("a", 0.1)), "q")).hasSize(1);
        assertThat(new SemanticReranker().rerank(null, "q")).isEmpty();
    }
}
