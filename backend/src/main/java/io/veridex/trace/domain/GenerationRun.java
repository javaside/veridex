package io.veridex.trace.domain;

import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 一次生成的记录：provider、模型、Token、总时延、首 token 时延、降级与上下文哈希。正文不落库。
 */
@Entity
@Table(name = "generation_run")
public class GenerationRun {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "query_run_id", nullable = false)
    private UUID queryRunId;

    @Column(nullable = false, length = 20)
    private String provider = "deterministic";

    @Column(nullable = false, length = 100)
    private String model;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "first_token_latency_ms", nullable = false)
    private long firstTokenLatencyMs;

    @Column(length = 100)
    private String degradation;

    @Column(name = "context_hash", length = 64)
    private String contextHash;

    protected GenerationRun() {
    }

    public GenerationRun(UUID queryRunId, String provider, String model, int inputTokens, int outputTokens,
                         long durationMs, long firstTokenLatencyMs, String degradation, String contextHash) {
        this.queryRunId = queryRunId;
        this.provider = provider;
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.durationMs = durationMs;
        this.firstTokenLatencyMs = firstTokenLatencyMs;
        this.degradation = degradation;
        this.contextHash = contextHash;
    }

    /** 流式完成后的指标回填（recordGeneration 幂等更新，不产生第二行）。 */
    public void applyMetrics(int inputTokens, int outputTokens, long durationMs,
                             long firstTokenLatencyMs, String degradation, String contextHash) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.durationMs = durationMs;
        this.firstTokenLatencyMs = firstTokenLatencyMs;
        this.degradation = degradation;
        this.contextHash = contextHash;
    }

    public UUID getId() { return id; }
    public UUID getQueryRunId() { return queryRunId; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public int getInputTokens() { return inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public long getDurationMs() { return durationMs; }
    public long getFirstTokenLatencyMs() { return firstTokenLatencyMs; }
}
