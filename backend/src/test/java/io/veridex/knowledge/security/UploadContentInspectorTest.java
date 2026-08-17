package io.veridex.knowledge.security;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.knowledge.infrastructure.security.UploadContentInspector;
import io.veridex.knowledge.infrastructure.security.UploadSecurityProperties;
import org.junit.jupiter.api.Test;

class UploadContentInspectorTest {

    private static final byte[] PDF_MAGIC = "%PDF-1.7\n".getBytes(UTF_8);
    private static final byte[] ZIP_MAGIC = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00, 0x00};

    private UploadContentInspector inspector() {
        return new UploadContentInspector(UploadSecurityProperties.defaults());
    }

    @Test
    void acceptsPdfWithMatchingMagic() {
        var result = inspector().inspect("report.pdf", "application/pdf", PDF_MAGIC, 128);
        assertThat(result.allowed()).isTrue();
        assertThat(result.code()).isNull();
    }

    @Test
    void acceptsDocxWithZipMagic() {
        var result = inspector().inspect("document.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ZIP_MAGIC, 128);
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void acceptsTextAndMarkdown() {
        assertThat(inspector().inspect("notes.txt", "text/plain", "hello world".getBytes(UTF_8), 11).allowed()).isTrue();
        assertThat(inspector().inspect("README.md", "text/markdown", "# title".getBytes(UTF_8), 7).allowed()).isTrue();
    }

    @Test
    void rejectsUnsupportedExtension() {
        var result = inspector().inspect("malware.exe", "application/octet-stream", ZIP_MAGIC, 128);
        assertThat(result.allowed()).isFalse();
        assertThat(result.code()).isEqualTo("unsupported_type");
    }

    @Test
    void rejectsPdfExtensionWithZipMagic() {
        var result = inspector().inspect("report.pdf", "application/pdf", ZIP_MAGIC, 128);
        assertThat(result.allowed()).isFalse();
        assertThat(result.code()).isEqualTo("content_type_mismatch");
    }

    @Test
    void rejectsDocxExtensionWithPdfMagic() {
        var result = inspector().inspect("document.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", PDF_MAGIC, 128);
        assertThat(result.allowed()).isFalse();
        assertThat(result.code()).isEqualTo("content_type_mismatch");
    }

    @Test
    void normalizesExtensionCase() {
        var result = inspector().inspect("REPORT.PDF", "application/pdf", PDF_MAGIC, 128);
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void rejectsOversizedFile() {
        var props = new UploadSecurityProperties(10, 1000, 100, 8);
        var result = new UploadContentInspector(props).inspect("notes.txt", "text/plain", "a".getBytes(UTF_8), 11);
        assertThat(result.allowed()).isFalse();
        assertThat(result.code()).isEqualTo("file_too_large");
    }

    @Test
    void rejectsBinaryContentDisguisedAsText() {
        var result = inspector().inspect("notes.txt", "text/plain", ZIP_MAGIC, 128);
        assertThat(result.allowed()).isFalse();
        assertThat(result.code()).isEqualTo("content_type_mismatch");
    }
}
