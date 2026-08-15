package io.veridex.evaluation.application;

import io.veridex.evaluation.domain.CaseMetrics;
import io.veridex.evaluation.domain.EvidenceRef;
import io.veridex.evaluation.domain.RunMetrics;
import io.veridex.generation.api.CitationView;
import io.veridex.retrieval.api.RankedHitView;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 评测自动指标计算（纯函数）：Recall@K / MRR / NDCG@10 / citation hit / refusal match / latency。
 */
@Component
public class MetricsCalculator {

    public CaseMetrics compute(List<EvidenceRef> groundTruth, List<RankedHitView> hits,
                               List<CitationView> citations, boolean expectedRefuse,
                               boolean actualRefuse, long latencyMs) {
        Set<String> g = groundTruthChunks(groundTruth);
        List<String> ranked = hits.stream()
                .sorted(Comparator.comparingInt(RankedHitView::rank))
                .map(h -> h.documentVersionId() + ":" + h.chunkIndex())
                .toList();
        double recallAt1 = recallAtK(g, ranked, 1);
        double recallAt3 = recallAtK(g, ranked, 3);
        double recallAt5 = recallAtK(g, ranked, 5);
        double mrr = mrr(g, ranked);
        double ndcgAt10 = ndcgAtK(g, ranked, 10);
        double citationHit = citationHit(g, citations);
        boolean refusalMatch = expectedRefuse == actualRefuse;
        return new CaseMetrics(recallAt1, recallAt3, recallAt5, mrr, ndcgAt10,
                citationHit, refusalMatch, latencyMs);
    }

    public RunMetrics aggregate(List<CaseMetrics> all) {
        if (all.isEmpty()) {
            return new RunMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        int n = all.size();
        double avgRecallAt1 = all.stream().mapToDouble(CaseMetrics::recallAt1).average().orElse(0);
        double avgRecallAt3 = all.stream().mapToDouble(CaseMetrics::recallAt3).average().orElse(0);
        double avgRecallAt5 = all.stream().mapToDouble(CaseMetrics::recallAt5).average().orElse(0);
        double avgMrr = all.stream().mapToDouble(CaseMetrics::mrr).average().orElse(0);
        double avgNdcgAt10 = all.stream().mapToDouble(CaseMetrics::ndcgAt10).average().orElse(0);
        double citationHitRate = all.stream().mapToDouble(CaseMetrics::citationHit).average().orElse(0);
        double refusalMatchRate = all.stream().filter(CaseMetrics::refusalMatch).count() / (double) n;
        double avgLatencyMs = all.stream().mapToDouble(CaseMetrics::latencyMs).average().orElse(0);
        return new RunMetrics(avgRecallAt1, avgRecallAt3, avgRecallAt5, avgMrr, avgNdcgAt10,
                citationHitRate, refusalMatchRate, avgLatencyMs, n, n);
    }

    private static Set<String> groundTruthChunks(List<EvidenceRef> groundTruth) {
        Set<String> out = new HashSet<>();
        for (EvidenceRef ref : groundTruth) {
            for (int idx : ref.chunkIndexes()) {
                out.add(ref.documentVersionId() + ":" + idx);
            }
        }
        return out;
    }

    private static double recallAtK(Set<String> g, List<String> ranked, int k) {
        if (g.isEmpty()) {
            return 0.0;
        }
        Set<String> topK = new HashSet<>(ranked.subList(0, Math.min(k, ranked.size())));
        long hit = g.stream().filter(topK::contains).count();
        return (double) hit / g.size();
    }

    private static double mrr(Set<String> g, List<String> ranked) {
        for (int i = 0; i < ranked.size(); i++) {
            if (g.contains(ranked.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    private static double ndcgAtK(Set<String> g, List<String> ranked, int k) {
        double dcg = 0.0;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) {
            if (g.contains(ranked.get(i))) {
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
        }
        int idealRelevant = Math.min(g.size(), k);
        double idcg = 0.0;
        for (int i = 0; i < idealRelevant; i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    private static double citationHit(Set<String> g, List<CitationView> citations) {
        if (g.isEmpty()) {
            return 0.0;
        }
        long hit = citations.stream()
                .filter(c -> "VALID".equals(c.validationStatus()))
                .filter(c -> g.contains(c.documentVersionId() + ":" + c.chunkIndex()))
                .count();
        return (double) hit / g.size();
    }
}
