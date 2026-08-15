package io.veridex.evaluation.domain;

/**
 * 单个评测用例的自动指标。
 */
public record CaseMetrics(double recallAt1, double recallAt3, double recallAt5,
                          double mrr, double ndcgAt10, double citationHit,
                          boolean refusalMatch, long latencyMs) {
}
