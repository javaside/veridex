package io.veridex.evaluation.api;

/**
 * 回归门禁判定：PASS（无退化）/ FAIL（有指标退化）/ INCOMPLETE（运行不完整）。
 */
public enum GateVerdict {
    PASS, FAIL, INCOMPLETE
}
