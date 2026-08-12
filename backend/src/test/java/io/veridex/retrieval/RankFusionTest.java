package io.veridex.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.retrieval.api.SearchHit;
import io.veridex.retrieval.application.RankFusion;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RankFusionTest {

    private static SearchHit hit(String text, UUID doc, SearchHit.Channel ch, double score) {
        return new SearchHit(UUID.randomUUID(), doc, 0, "t", "1", text, ch, score);
    }

    @Test
    void fuseMergesSameChunkFromBothChannelsByRank() {
        UUID doc = UUID.randomUUID();
        var bm25 = List.of(hit("甲", doc, SearchHit.Channel.BM25, 2.0));
        var vector = List.of(hit("甲", doc, SearchHit.Channel.VECTOR, 1.5));
        var fused = RankFusion.fuse(bm25, vector, 60);
        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).documentVersionId()).isEqualTo(doc);
        assertThat(fused.get(0).bm25Score()).isEqualTo(2.0);
        assertThat(fused.get(0).vectorScore()).isEqualTo(1.5);
        // RRF: 1/(60+1) + 1/(60+1)
        assertThat(fused.get(0).fusionScore()).isEqualTo(2.0 / 61.0);
    }

    @Test
    void fuseKeepsHigherRankWhenSameChunkAppearsInOneChannelOnly() {
        UUID docA = UUID.randomUUID(), docB = UUID.randomUUID();
        var bm25 = List.of(hit("A", docA, SearchHit.Channel.BM25, 1.0));
        var vector = List.of(hit("B", docB, SearchHit.Channel.VECTOR, 1.0));
        var fused = RankFusion.fuse(bm25, vector, 60);
        assertThat(fused).hasSize(2);
        assertThat(fused.get(0).documentVersionId()).isEqualTo(docA); // rank 1 的 BM25 融合分高
        assertThat(fused.get(1).documentVersionId()).isEqualTo(docB);
    }

    @Test
    void fuseSortsByFusionScoreDescending() {
        UUID docA = UUID.randomUUID(), docB = UUID.randomUUID();
        // A 在两路都命中 rank1/rank1，B 只在向量路 rank3
        var bm25 = List.of(hit("A", docA, SearchHit.Channel.BM25, 1.0));
        var vector = List.of(
                hit("A", docA, SearchHit.Channel.VECTOR, 1.0),
                hit("B", docB, SearchHit.Channel.VECTOR, 1.0),
                hit("C", UUID.randomUUID(), SearchHit.Channel.VECTOR, 1.0));
        var fused = RankFusion.fuse(bm25, vector, 60);
        assertThat(fused.get(0).documentVersionId()).isEqualTo(docA);
    }
}
