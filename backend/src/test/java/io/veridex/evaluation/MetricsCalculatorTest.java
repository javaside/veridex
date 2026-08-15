package io.veridex.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.evaluation.application.MetricsCalculator;
import io.veridex.evaluation.domain.CaseMetrics;
import io.veridex.evaluation.domain.EvidenceRef;
import io.veridex.evaluation.domain.RunMetrics;
import io.veridex.generation.api.CitationView;
import io.veridex.retrieval.api.RankedHitView;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MetricsCalculatorTest {

    private final MetricsCalculator calculator = new MetricsCalculator();

    private static RankedHitView hit(UUID docVersionId, int chunkIndex, int rank) {
        return new RankedHitView(UUID.randomUUID(), docVersionId, chunkIndex, "BM25",
                null, null, 1.0, rank, true, null);
    }

    private static CitationView citation(int index, UUID docVersionId, int chunkIndex, String status) {
        return new CitationView(index, UUID.randomUUID(), docVersionId, chunkIndex, "t", "[" + index + "]", status);
    }

    @Test
    void recallAtKIsHitRatio() {
        UUID dv = UUID.randomUUID();
        var groundTruth = List.of(new EvidenceRef(dv, List.of(0, 1)));
        var hits = List.of(
                hit(dv, 0, 1),
                hit(dv, 2, 2),
                hit(dv, 1, 3));

        CaseMetrics metrics = calculator.compute(groundTruth, hits, List.of(), false, false, 10);

        assertThat(metrics.recallAt1()).isEqualTo(0.5); // 1/2
        assertThat(metrics.recallAt3()).isEqualTo(1.0); // 2/2
    }

    @Test
    void mrrIsReciprocalOfFirstRelevantRank() {
        UUID dv = UUID.randomUUID();
        var groundTruth = List.of(new EvidenceRef(dv, List.of(5)));
        var hits = List.of(hit(dv, 1, 1), hit(dv, 5, 2), hit(dv, 9, 3));

        CaseMetrics metrics = calculator.compute(groundTruth, hits, List.of(), false, false, 10);
        assertThat(metrics.mrr()).isEqualTo(0.5); // rank 2
    }

    @Test
    void emptyGroundTruthYieldsZeroRecallAndZeroCitationHit() {
        var metrics = calculator.compute(List.of(), List.of(hit(UUID.randomUUID(), 0, 1)), List.of(),
                true, true, 10);
        assertThat(metrics.recallAt1()).isZero();
        assertThat(metrics.citationHit()).isZero();
        assertThat(metrics.refusalMatch()).isTrue();
    }

    @Test
    void citationHitCountsValidCitationsInGroundTruth() {
        UUID dv = UUID.randomUUID();
        var groundTruth = List.of(new EvidenceRef(dv, List.of(0, 1)));
        var citations = List.of(
                citation(1, dv, 0, "VALID"),
                citation(2, dv, 9, "VALID"),
                citation(3, dv, 1, "INVALID"));

        CaseMetrics metrics = calculator.compute(groundTruth, List.of(), citations, false, false, 10);
        assertThat(metrics.citationHit()).isEqualTo(0.5); // only chunk 0 valid
    }

    @Test
    void refusalMatchIsTrueWhenExpectedEqualsActual() {
        CaseMetrics matched = calculator.compute(List.of(), List.of(), List.of(), true, true, 0);
        assertThat(matched.refusalMatch()).isTrue();

        CaseMetrics mismatched = calculator.compute(List.of(), List.of(), List.of(), false, true, 0);
        assertThat(mismatched.refusalMatch()).isFalse();
    }

    @Test
    void aggregateAveragesCaseMetrics() {
        var a = new CaseMetrics(1.0, 0.8, 0.6, 0.5, 0.4, 0.3, true, 100);
        var b = new CaseMetrics(0.0, 0.6, 0.4, 0.25, 0.2, 0.1, false, 300);

        RunMetrics agg = calculator.aggregate(List.of(a, b));

        assertThat(agg.avgRecallAt1()).isEqualTo(0.5);
        assertThat(agg.avgLatencyMs()).isEqualTo(200.0);
        assertThat(agg.refusalMatchRate()).isEqualTo(0.5);
        assertThat(agg.caseCount()).isEqualTo(2);
        assertThat(agg.completedCount()).isEqualTo(2);
    }
}
