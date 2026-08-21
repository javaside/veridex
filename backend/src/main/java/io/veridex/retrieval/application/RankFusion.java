package io.veridex.retrieval.application;

import io.veridex.retrieval.api.SearchHit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 基于排名的 RRF（Reciprocal Rank Fusion）融合：规避跨索引分数不可比，
 * 仅依赖各通道内的相对排名。
 */
public final class RankFusion {

    private RankFusion() {
    }

    public static List<RankedHit> fuse(List<SearchHit> bm25, List<SearchHit> vector, int k) {
        Map<String, RankedHit> byChunk = new LinkedHashMap<>();
        addChannel(bm25, byChunk, k, true);
        addChannel(vector, byChunk, k, false);
        return byChunk.values().stream()
                .sorted(Comparator.comparingDouble(RankedHit::fusionScore).reversed())
                .toList();
    }

    private static void addChannel(List<SearchHit> hits, Map<String, RankedHit> out, int k, boolean bm25Channel) {
        // 排名必须按「每个知识库内」计算，而不是按拼接后的全局位置。
        // 否则 scope 里靠前的库会拿到 rank 0/1/2… 的高分，靠后的库（哪怕内容更相关）
        // 被系统性压到低分，导致最相关的内容反而进不了上下文。
        Map<UUID, Integer> rankPerKb = new HashMap<>();
        for (SearchHit h : hits) {
            int rank = rankPerKb.getOrDefault(h.knowledgeBaseId(), 0);
            rankPerKb.put(h.knowledgeBaseId(), rank + 1);
            String key = h.documentVersionId() + ":" + h.chunkIndex();
            double contribution = 1.0 / (k + rank + 1);
            RankedHit existing = out.get(key);
            if (existing == null) {
                out.put(key, new RankedHit(h.knowledgeBaseId(), h.documentVersionId(), h.chunkIndex(),
                        h.title(), h.structurePath(), h.text(),
                        bm25Channel ? h.score() : null, bm25Channel ? null : h.score(), contribution));
            } else {
                out.put(key, new RankedHit(existing.knowledgeBaseId(), existing.documentVersionId(), existing.chunkIndex(),
                        existing.title(), existing.structurePath(), existing.text(),
                        bm25Channel ? h.score() : existing.bm25Score(),
                        bm25Channel ? existing.vectorScore() : h.score(),
                        existing.fusionScore() + contribution));
            }
        }
    }

    public record RankedHit(UUID knowledgeBaseId, UUID documentVersionId, int chunkIndex,
                            String title, String structurePath, String text,
                            Double bm25Score, Double vectorScore, double fusionScore) {
    }
}
