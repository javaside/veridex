package io.veridex.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.retrieval.application.ContextAssemblyService;
import io.veridex.retrieval.application.RankFusion;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContextAssemblyServiceTest {

    private final ContextAssemblyService service = new ContextAssemblyService();

    private RankFusion.RankedHit hit(UUID doc, String text) {
        return new RankFusion.RankedHit(UUID.randomUUID(), doc, 0, "标题", "1", text, 1.0, 1.0, 2.0);
    }

    private RankFusion.RankedHit hitInKb(UUID kb, UUID doc, String text) {
        return new RankFusion.RankedHit(kb, doc, 0, "标题", "1", text, 1.0, 1.0, 2.0);
    }

    @Test
    void assembleNumbersEvidenceInRankOrder() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var hits = List.of(hit(a, "内容A"), hit(b, "内容B"));
        var evidence = service.assemble(hits, "q", 6, 3, 4000);
        assertThat(evidence).hasSize(2);
        assertThat(evidence.get(0).citationIndex()).isEqualTo(1);
        assertThat(evidence.get(1).citationIndex()).isEqualTo(2);
        assertThat(evidence.get(0).text()).isEqualTo("内容A");
    }

    @Test
    void assembleCapsPerDocument() {
        UUID doc = UUID.randomUUID();
        var hits = List.of(hit(doc, "a".repeat(100)), hit(doc, "b".repeat(100)), hit(doc, "c".repeat(100)));
        var evidence = service.assemble(hits, "q", 6, 2, 10_000);
        assertThat(evidence).hasSize(2); // perDocumentMax=2
    }

    @Test
    void assembleCapsTotalChars() {
        UUID doc = UUID.randomUUID();
        var hits = List.of(hit(doc, "a".repeat(600)), hit(doc, "b".repeat(600)));
        var evidence = service.assemble(hits, "q", 6, 5, 1000);
        assertThat(evidence).hasSize(1); // 第一条 600 后，再加 600 超 1000 → 截断
        assertThat(evidence.get(0).text().length()).isEqualTo(600);
    }

    @Test
    void assembleKeepsAtLeastOneEvidenceWhenSingleChunkExceedsBudget() {
        // 历史数据 chunking.maxChars(5000) 曾大于 contextMaxChars(4000)：单条 chunk 就超预算。
        // 不能因此 break 成 0 条——检索命中却返回 NO_RELEVANT_EVIDENCE 是错误行为。
        UUID doc = UUID.randomUUID();
        var hits = List.of(hit(doc, "长".repeat(5000)), hit(doc, "短".repeat(100)));
        var evidence = service.assemble(hits, "q", 6, 3, 4000);
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).text().length()).isEqualTo(5000);
    }

    @Test
    void assembleLimitsByTopK() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        var hits = List.of(hit(a, "x"), hit(b, "y"), hit(c, "z"));
        var evidence = service.assemble(hits, "q", 2, 3, 4000);
        assertThat(evidence).hasSize(2);
    }

    @Test
    void guaranteesEachKnowledgeBaseAtLeastOneEvidence() {
        // 库 A 只查出 1 条相关证据，库 B 查出很多条（数量占优、分数靠前）。
        // 旧逻辑全局 top-K 会把库 A 挤掉；新逻辑必须保证库 A 至少 1 条进上下文。
        UUID kbA = UUID.randomUUID(), kbB = UUID.randomUUID();
        UUID docA = UUID.randomUUID(), docB = UUID.randomUUID();
        var hits = new java.util.ArrayList<RankFusion.RankedHit>();
        for (int i = 0; i < 6; i++) {
            hits.add(hitInKb(kbB, docB, "库B的第" + i + "条证据"));
        }
        hits.add(hitInKb(kbA, docA, "库A唯一相关证据"));

        var evidence = service.assemble(hits, "q", 6, 3, 4000);

        assertThat(evidence).anyMatch(e -> e.knowledgeBaseId().equals(kbA));
    }
}
