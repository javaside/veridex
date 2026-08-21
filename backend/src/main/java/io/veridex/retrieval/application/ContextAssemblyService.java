package io.veridex.retrieval.application;

import io.veridex.retrieval.api.EvidencePiece;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 上下文组装：先按知识库保底（每个命中的库至少 1 条进入上下文），再用全局相关性序填满
 * 剩余名额；受 topK、单文档数量、字符预算三重限制，并为每条证据分配 [n] 引用编号。
 *
 * <p>关键点：多库混合检索时若只做「全局 top-K」，某几个库会因候选数量/分数优势把其它库
 * 挤掉，导致「某个库明明查到了相关内容却没用上、模型误判为没有内容」。因此先按库保底，
 * 再全局择优。
 */
@Component
public class ContextAssemblyService {

    public List<EvidencePiece> assemble(List<RankFusion.RankedHit> hits, String question,
                                        int topK, int perDocumentMax, int maxChars) {
        // 按知识库分组，组内保持原有相关性顺序。
        Map<UUID, List<RankFusion.RankedHit>> byKb = new LinkedHashMap<>();
        for (RankFusion.RankedHit hit : hits) {
            byKb.computeIfAbsent(hit.knowledgeBaseId(), k -> new ArrayList<>()).add(hit);
        }

        List<RankFusion.RankedHit> picked = new ArrayList<>();
        Map<UUID, Integer> perDoc = new HashMap<>();
        int total = 0;

        // 第一轮：每个知识库各取 1 条，保证「查出来的库」至少 1 条进上下文。
        for (List<RankFusion.RankedHit> kbHits : byKb.values()) {
            if (picked.size() >= topK) {
                break;
            }
            for (RankFusion.RankedHit hit : kbHits) {
                if (picked.contains(hit)) {
                    continue;
                }
                if (perDoc.getOrDefault(hit.documentVersionId(), 0) >= perDocumentMax) {
                    continue;
                }
                // 保证至少 1 条证据进入上下文：单条 chunk 超过 maxChars 时不能直接 break，
                // 否则「检索命中却返回 NO_RELEVANT_EVIDENCE」（历史数据 chunking.maxChars 曾大于
                // retrieval.contextMaxChars，导致每个 chunk 都超预算，证据恒为空）。
                if (!picked.isEmpty() && total + hit.text().length() > maxChars) {
                    break;
                }
                picked.add(hit);
                perDoc.merge(hit.documentVersionId(), 1, Integer::sum);
                total += hit.text().length();
                break; // 该库已保底 1 条，跳到下一个库
            }
        }

        // 第二轮：按全局相关性顺序填满剩余名额。
        for (RankFusion.RankedHit hit : hits) {
            if (picked.size() >= topK) {
                break;
            }
            if (picked.contains(hit)) {
                continue;
            }
            if (perDoc.getOrDefault(hit.documentVersionId(), 0) >= perDocumentMax) {
                continue;
            }
            if (!picked.isEmpty() && total + hit.text().length() > maxChars) {
                break;
            }
            picked.add(hit);
            perDoc.merge(hit.documentVersionId(), 1, Integer::sum);
            total += hit.text().length();
        }

        List<EvidencePiece> out = new ArrayList<>();
        for (int i = 0; i < picked.size(); i++) {
            RankFusion.RankedHit hit = picked.get(i);
            out.add(new EvidencePiece(i + 1, hit.knowledgeBaseId(), hit.documentVersionId(), hit.chunkIndex(),
                    hit.title(), hit.structurePath(), hit.text()));
        }
        return out;
    }
}
