package io.veridex.retrieval.application;

import io.veridex.retrieval.api.EvidencePiece;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 上下文组装：按融合序取 Top K，单文档数量限制（来源多样性），
 * 以字符近似 token 预算裁剪，并为每个证据分配 [n] 引用编号。
 */
@Component
public class ContextAssemblyService {

    public List<EvidencePiece> assemble(List<RankFusion.RankedHit> hits, String question,
                                        int topK, int perDocumentMax, int maxChars) {
        List<RankFusion.RankedHit> capped = new ArrayList<>();
        Map<UUID, Integer> perDoc = new HashMap<>();
        int total = 0;
        for (RankFusion.RankedHit hit : hits) {
            if (capped.size() >= topK) {
                break;
            }
            int used = perDoc.merge(hit.documentVersionId(), 1, Integer::sum);
            if (used > perDocumentMax) {
                continue;
            }
            if (total + hit.text().length() > maxChars) {
                break;
            }
            capped.add(hit);
            total += hit.text().length();
        }
        List<EvidencePiece> out = new ArrayList<>();
        for (int i = 0; i < capped.size(); i++) {
            RankFusion.RankedHit hit = capped.get(i);
            out.add(new EvidencePiece(i + 1, hit.knowledgeBaseId(), hit.documentVersionId(), hit.chunkIndex(),
                    hit.title(), hit.structurePath(), hit.text()));
        }
        return out;
    }
}
