package io.veridex.ingestion.infrastructure;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 解析执行预算。超时和临时根目录可配置；非法值回退到安全默认值。
 */
@ConfigurationProperties(prefix = "veridex.security.parser")
public record ParserExecutionProperties(Duration timeout, Path tempRoot) {

    public ParserExecutionProperties {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(30);
        }
        if (tempRoot == null) {
            tempRoot = Path.of(System.getProperty("java.io.tmpdir"), "veridex-parser");
        }
    }

    public static ParserExecutionProperties defaults() {
        return new ParserExecutionProperties(Duration.ofSeconds(30),
                Path.of(System.getProperty("java.io.tmpdir"), "veridex-parser"));
    }
}
