package io.veridex.knowledge.infrastructure.security;

import java.util.Locale;

/**
 * 上传文件类型与大小检查。基于扩展名、声明 MIME 和魔数三者的最小一致性，
 * 防止可执行文件或压缩包伪装成受支持文档。
 */
public final class UploadContentInspector {

    private final UploadSecurityProperties properties;

    public UploadContentInspector(UploadSecurityProperties properties) {
        this.properties = properties;
    }

    public InspectionResult inspect(String filename, String declaredContentType, byte[] prefix, long size) {
        if (size > properties.maxFileBytes()) {
            return InspectionResult.reject("file_too_large");
        }

        DocumentKind extensionKind = DocumentKind.fromExtension(filename);
        if (extensionKind == null) {
            return InspectionResult.reject("unsupported_type");
        }

        DocumentKind mimeKind = DocumentKind.fromContentType(declaredContentType);
        DocumentKind magicKind = DocumentKind.fromMagic(prefix);

        if (mimeKind != null && mimeKind != extensionKind) {
            return InspectionResult.reject("content_type_mismatch");
        }
        if (magicKind != null && magicKind != extensionKind) {
            return InspectionResult.reject("content_type_mismatch");
        }
        if (extensionKind == DocumentKind.TEXT && looksBinary(prefix)) {
            return InspectionResult.reject("content_type_mismatch");
        }
        return InspectionResult.ok();
    }

    private boolean looksBinary(byte[] prefix) {
        if (prefix == null) {
            return false;
        }
        for (byte b : prefix) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    public record InspectionResult(boolean allowed, String code) {
        public static InspectionResult ok() {
            return new InspectionResult(true, null);
        }

        public static InspectionResult reject(String code) {
            return new InspectionResult(false, code);
        }
    }

    private enum DocumentKind {
        PDF, DOCX, TEXT;

        static DocumentKind fromExtension(String filename) {
            if (filename == null || filename.isBlank()) {
                return null;
            }
            String lower = filename.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".pdf")) {
                return PDF;
            }
            if (lower.endsWith(".docx")) {
                return DOCX;
            }
            if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown")) {
                return TEXT;
            }
            return null;
        }

        static DocumentKind fromContentType(String contentType) {
            if (contentType == null || contentType.isBlank()) {
                return null;
            }
            String lower = contentType.toLowerCase(Locale.ROOT).split(";")[0].trim();
            return switch (lower) {
                case "application/pdf" -> PDF;
                case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DOCX;
                case "text/plain", "text/markdown", "text/x-markdown" -> TEXT;
                default -> null;
            };
        }

        static DocumentKind fromMagic(byte[] prefix) {
            if (prefix == null || prefix.length == 0) {
                return null;
            }
            if (startsWithAscii(prefix, "%PDF")) {
                return PDF;
            }
            if (prefix.length >= 4 && prefix[0] == 0x50 && prefix[1] == 0x4B
                    && prefix[2] == 0x03 && prefix[3] == 0x04) {
                return DOCX;
            }
            return null;
        }

        private static boolean startsWithAscii(byte[] bytes, String ascii) {
            if (bytes.length < ascii.length()) {
                return false;
            }
            for (int i = 0; i < ascii.length(); i++) {
                if (bytes[i] != (byte) ascii.charAt(i)) {
                    return false;
                }
            }
            return true;
        }
    }
}
