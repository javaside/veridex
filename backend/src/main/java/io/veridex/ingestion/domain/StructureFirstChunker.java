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
 * - 超长块按 maxChars 硬切（含 overlap）。
 * maxChars/overlap 来自当前生效的配置 Profile（chunking 维度），由入库 worker 注入。
 */
@Component
public class StructureFirstChunker implements StructureChunker {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,3})\\s+(.+)$");

    @Override
    public List<Chunk> chunk(ParsedDocument document, int maxChars, int overlap) {
        List<Section> sections = new ArrayList<>();
        Section current = null;
        String[] lines = document.text().split("\n", -1);
        // 只有 Markdown 源文件才把「# xxx」当作标题；PDF/DOCX/TXT 解析出的「# 命令」
        // 是 shell 注释（如 # sudo apt-get install …），误判会导致 chunk title 变成命令文本。
        boolean markdown = isMarkdown(document);

        for (String line : lines) {
            Matcher heading = markdown ? MARKDOWN_HEADING.matcher(line) : null;
            if (heading != null && heading.matches()) {
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
            for (String part : hardSplit(body, maxChars, overlap)) {
                chunks.add(new Chunk(index++, part, section.title, String.valueOf(section.path)));
            }
        }
        return chunks;
    }

    private static boolean isMarkdown(ParsedDocument document) {
        String contentType = document.contentType();
        if (contentType != null && contentType.contains("markdown")) {
            return true;
        }
        String filename = document.sourceFilename();
        return filename != null && (filename.endsWith(".md") || filename.endsWith(".markdown"));
    }

    private static List<String> hardSplit(String body, int maxChars, int overlap) {
        if (body.length() <= maxChars) {
            return List.of(body);
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < body.length()) {
            int end = Math.min(start + maxChars, body.length());
            parts.add(body.substring(start, end));
            if (end == body.length()) {
                break; // 已到末尾，避免 start 回退导致死循环
            }
            start = end - overlap;
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
