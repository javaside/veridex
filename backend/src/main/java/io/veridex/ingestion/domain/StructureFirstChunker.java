package io.veridex.ingestion.domain;

import io.veridex.ingestion.application.StructureChunker;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 结构优先分块器（v1）：
 * - Markdown 1-3 级标题驱动章节切分，标题作为 chunk 标题；
 * - 无标题的纯文本按段落聚合；
 * - 超长块按 MAX_CHARS 硬切（含 overlap）。
 */
@Component
public class StructureFirstChunker implements StructureChunker {

    private static final int MAX_CHARS = 2000;
    private static final int OVERLAP = 80;

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,3})\\s+(.+)$");

    @Override
    public List<Chunk> chunk(ParsedDocument document) {
        List<Section> sections = new ArrayList<>();
        Section current = null;
        String[] lines = document.text().split("\n", -1);

        for (String line : lines) {
            Matcher heading = MARKDOWN_HEADING.matcher(line);
            if (heading.matches()) {
                if (current != null && !current.body.isEmpty()) {
                    sections.add(current);
                }
                String title = heading.group(2).trim();
                current = new Section(title, sections.size() + 1);
                current.body.add(title); // 标题文本作为章节代表内容，空正文章节也能产出 chunk
                continue;
            }
            if (current == null) {
                current = new Section(document.sourceFilename(), sections.size() + 1);
            }
            current.body.add(line);
        }
        if (current != null && !current.body.isEmpty()) {
            sections.add(current);
        }

        List<Chunk> chunks = new ArrayList<>();
        int index = 0;
        for (Section section : sections) {
            String body = String.join("\n", section.body).trim();
            if (body.isBlank()) {
                continue;
            }
            for (String part : hardSplit(body)) {
                chunks.add(new Chunk(index++, part, section.title, String.valueOf(section.path)));
            }
        }
        return chunks;
    }

    private static List<String> hardSplit(String body) {
        if (body.length() <= MAX_CHARS) {
            return List.of(body);
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < body.length()) {
            int end = Math.min(start + MAX_CHARS, body.length());
            parts.add(body.substring(start, end));
            if (end == body.length()) {
                break; // 已到末尾，避免 start 回退导致死循环
            }
            start = end - OVERLAP;
        }
        return parts;
    }

    private static final class Section {
        private final String title;
        private final int path;
        private final List<String> body = new ArrayList<>();

        private Section(String title, int path) {
            this.title = title;
            this.path = path;
        }
    }
}
