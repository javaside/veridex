package io.veridex.knowledge.infrastructure.security;

import java.nio.file.Path;

/**
 * 压缩包展开预算与路径安全。解压前逐条目累计展开大小与条目数，
 * 拒绝路径穿越、绝对路径、NUL 字节与超过嵌套深度的条目。
 */
public final class ArchiveBudget {

    private final UploadSecurityProperties properties;
    private long expandedBytes;
    private int entries;

    public ArchiveBudget(UploadSecurityProperties properties) {
        this.properties = properties;
    }

    public void checkEntry(String entryName, long entryExpandedBytes, int depth) {
        if (entryName == null || entryName.indexOf('\u0000') >= 0) {
            throw new IllegalStateException("unsafe_path");
        }
        Path normalized = Path.of(entryName).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new IllegalStateException("unsafe_path");
        }
        if (depth > properties.maxArchiveDepth()) {
            throw new IllegalStateException("archive_budget_exceeded");
        }

        expandedBytes += entryExpandedBytes;
        entries += 1;
        if (expandedBytes > properties.maxArchiveExpandedBytes()
                || entries > properties.maxArchiveEntries()) {
            throw new IllegalStateException("archive_budget_exceeded");
        }
    }
}
