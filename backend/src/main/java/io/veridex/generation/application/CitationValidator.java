package io.veridex.generation.application;

import io.veridex.generation.api.CitationView;
import io.veridex.retrieval.api.EvidencePiece;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    public List<CitationView> validate(String answer, List<EvidencePiece> evidence) {
        Set<Integer> validIndexes = evidence.stream().map(EvidencePiece::citationIndex).collect(Collectors.toSet());
        Map<Integer, EvidencePiece> byIndex = evidence.stream()
                .collect(Collectors.toMap(EvidencePiece::citationIndex, e -> e));
        List<CitationView> out = new ArrayList<>();
        Matcher matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            boolean valid = validIndexes.contains(index);
            EvidencePiece e = byIndex.get(index);
            out.add(new CitationView(index,
                    e != null ? e.documentVersionId() : null,
                    e != null ? e.chunkIndex() : 0,
                    e != null ? e.title() : null,
                    "[" + index + "]",
                    valid ? "VALID" : "INVALID"));
        }
        return out;
    }
}
