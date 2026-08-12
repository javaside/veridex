package io.veridex.generation;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.generation.application.RefusalPolicy;
import io.veridex.retrieval.api.EvidencePiece;
import io.veridex.shared.RefusalReason;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefusalPolicyTest {

    private final RefusalPolicy policy = new RefusalPolicy();

    @Test
    void emptyEvidenceRefusesNoRelevantEvidence() {
        assertThat(policy.evaluate(List.of())).isEqualTo(RefusalReason.NO_RELEVANT_EVIDENCE);
    }

    @Test
    void shortEvidenceRefusesInsufficientEvidence() {
        var evidence = List.of(new EvidencePiece(1, UUID.randomUUID(), UUID.randomUUID(), 0, "t", "1", "短"));
        assertThat(policy.evaluate(evidence)).isEqualTo(RefusalReason.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void sufficientEvidenceAllowsGeneration() {
        var evidence = List.of(new EvidencePiece(1, UUID.randomUUID(), UUID.randomUUID(), 0, "t", "1",
                "员工请假需提前两个工作日向直属主管提交书面申请，经审批后生效；连续请假超过五个工作日的，还需报人力资源部备案。"));
        assertThat(policy.evaluate(evidence)).isNull();
    }
}
