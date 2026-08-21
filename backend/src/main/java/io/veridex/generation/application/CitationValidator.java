package io.veridex.generation.application;

import io.veridex.generation.api.CitationView;
import io.veridex.retrieval.api.EvidencePiece;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 引用校验：解析回答中的 [n] 编号，校验编号是否属于本次上下文证据范围。
 * 生成后调用；校验状态写入 citation.validationStatus。
 */
@Component
public class CitationValidator {

    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)\\]");

    public List<CitationView> validate(String answer, List<EvidencePiece> evidence,
                                       Map<UUID, UUID> documentIdByVersionId) {
        Set<Integer> validIndexes = evidence.stream().map(EvidencePiece::citationIndex).collect(Collectors.toSet());
        Map<Integer, EvidencePiece> byIndex = evidence.stream()
                .collect(Collectors.toMap(EvidencePiece::citationIndex, e -> e));
        // 回答里同一个 [n] 可能多次出现（模型常重复引用同一证据），但引用列表必须去重：
        // 每个 citationIndex 只保留首次出现的那条，避免前端底部重复渲染同一引用。
        Map<Integer, CitationView> byIndexOrdered = new LinkedHashMap<>();
        Matcher matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (byIndexOrdered.containsKey(index)) {
                continue;
            }
            boolean valid = validIndexes.contains(index);
            EvidencePiece e = byIndex.get(index);
            byIndexOrdered.put(index, new CitationView(index,
                    e != null ? documentIdByVersionId.get(e.documentVersionId()) : null,
                    e != null ? e.documentVersionId() : null,
                    e != null ? e.chunkIndex() : 0,
                    e != null ? e.title() : null,
                    "[" + index + "]",
                    valid ? "VALID" : "INVALID"));
        }
        return new ArrayList<>(byIndexOrdered.values());
    }
}
