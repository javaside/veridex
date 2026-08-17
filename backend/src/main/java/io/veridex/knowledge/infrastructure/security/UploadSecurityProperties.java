package io.veridex.knowledge.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上传与解析资源预算。默认值偏保守，均可配置；非法值在启动时拒绝。
 */
@ConfigurationProperties(prefix = "veridex.security.upload")
public record UploadSecurityProperties(
        long maxFileBytes,
        long maxArchiveExpandedBytes,
        int maxArchiveEntries,
        int maxArchiveDepth) {

    public UploadSecurityProperties {
        if (maxFileBytes <= 0) {
            maxFileBytes = 50L * 1024 * 1024;
        }
        if (maxArchiveExpandedBytes <= 0) {
            maxArchiveExpandedBytes = 200L * 1024 * 1024;
        }
        if (maxArchiveEntries <= 0) {
            maxArchiveEntries = 10_000;
        }
        if (maxArchiveDepth < 0) {
            maxArchiveDepth = 8;
        }
    }

    public static UploadSecurityProperties defaults() {
        return new UploadSecurityProperties(50L * 1024 * 1024, 200L * 1024 * 1024, 10_000, 8);
    }

    /** 非法预算 fail-fast，避免静默放宽安全边界。 */
    public void validate() {
        if (maxFileBytes <= 0 || maxArchiveExpandedBytes <= 0
                || maxArchiveEntries <= 0 || maxArchiveDepth < 0) {
            throw new IllegalStateException("invalid_security_configuration");
        }
    }
}
