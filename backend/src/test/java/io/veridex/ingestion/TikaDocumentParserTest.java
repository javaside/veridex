package io.veridex.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.ingestion.application.DocumentParser;
import io.veridex.ingestion.infrastructure.TikaDocumentParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TikaDocumentParserTest {

    private final DocumentParser parser = new TikaDocumentParser();

    @Test
    void parsesMarkdownToText() {
        var bytes = "# 标题\n\n正文内容".getBytes(StandardCharsets.UTF_8);
        var parsed = parser.parse(new ByteArrayInputStream(bytes), "doc.md", "text/markdown");
        assertThat(parsed.text()).contains("正文内容");
    }

    @Test
    void parsesPlainText() {
        var parsed = parser.parse(new ByteArrayInputStream("纯文本".getBytes(StandardCharsets.UTF_8)),
                "memo.txt", "text/plain");
        assertThat(parsed.text()).contains("纯文本");
    }

    @Test
    void parsesDocxGeneratedByPoi() throws Exception {
        try (org.apache.poi.xwpf.usermodel.XWPFDocument docx = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            docx.createParagraph().createRun().setText("Word 文档正文");
            var bytes = new ByteArrayOutputStream();
            docx.write(bytes);
            var parsed = parser.parse(new ByteArrayInputStream(bytes.toByteArray()),
                    "letter.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            assertThat(parsed.text()).contains("Word 文档正文");
        }
    }
}
