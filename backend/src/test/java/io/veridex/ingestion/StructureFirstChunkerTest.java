package io.veridex.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.ingestion.application.StructureChunker;
import io.veridex.ingestion.domain.ParsedDocument;
import io.veridex.ingestion.domain.StructureFirstChunker;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StructureFirstChunkerTest {

    private final StructureChunker chunker = new StructureFirstChunker();

    private String load(String resource) throws Exception {
        return Files.readString(Path.of("src/test/resources/sample/" + resource), StandardCharsets.UTF_8);
    }

    @Test
    void markdownHeadingStructureProducesChunksWithPaths() throws Exception {
        var chunks = chunker.chunk(new ParsedDocument(load("docs.md"), "docs.md", "text/markdown"));
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(5);
        assertThat(chunks.get(0).title()).isEqualTo("入职指南");
        assertThat(chunks.get(0).structurePath()).isEqualTo("1");
        assertThat(chunks.stream().anyMatch(c -> c.title().equals("薪酬福利"))).isTrue();
        assertThat(chunks.stream().allMatch(c -> !c.text().isBlank())).isTrue();
    }

    @Test
    void plainTextWithoutHeadingsStillChunksByParagraph() throws Exception {
        var chunks = chunker.chunk(new ParsedDocument(load("memo.txt"), "memo.txt", "text/plain"));
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(1);
        assertThat(chunks.stream().allMatch(c -> !c.text().isBlank())).isTrue();
    }

    @Test
    void longBodyIsHardSplitUnderMaxChars() {
        String longText = "段落开始。" + "x".repeat(5000);
        var chunks = chunker.chunk(new ParsedDocument(longText, "long.txt", "text/plain"));
        assertThat(chunks.stream().allMatch(c -> c.text().length() <= 2000)).isTrue();
    }
}
