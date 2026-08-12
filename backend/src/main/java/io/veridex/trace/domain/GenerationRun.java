package io.veridex.trace.domain;

import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 一次生成的记录：模型、Token、耗时、降级与上下文哈希。正文不落库。
 */
@Entity
@Table(name = "generation_run")
public class GenerationRun {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "query_run_id", nullable = false)
    private UUID queryRunId;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(length = 100)
    private String degradation;

    @Column(name = "context_hash", length = 64)
    private String contextHash;

    protected GenerationRun() {
    }

    public GenerationRun(UUID queryRunId, String model, int inputTokens, int outputTokens,
                         long durationMs, String degradation, String contextHash) {
        this.queryRunId = queryRunId;
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.durationMs = durationMs;
        this.degradation = degradation;
        this.contextHash = contextHash;
    }

    public UUID getId() { return id; }
    public UUID getQueryRunId() { return queryRunId; }
    public String getModel() { return model; }
}
