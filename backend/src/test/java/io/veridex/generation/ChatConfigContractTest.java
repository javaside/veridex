package io.veridex.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 配置文本契约（设计 §5.2）：SSE async timeout 不得短于模型总时限加收尾余量；
 * provider 默认 deterministic。读取 application.yml 原文断言，避免起全量上下文。
 */
class ChatConfigContractTest {

    private static final Path APP_YML = Path.of("src/main/resources/application.yml");

    private String lineContaining(String fragment) throws Exception {
        return Files.readAllLines(APP_YML).stream()
                .map(String::trim)
                .filter(line -> line.contains(fragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing line containing " + fragment));
    }

    @Test
    void sseAsyncTimeoutIsLongerThanChatTimeoutPlusMargin() throws Exception {
        // 设计 §5.2：HTTP/SSE 层超时不得短于模型总时限加收尾余量；固定 120s ≥ 60s + 60s 余量。
        assertThat(lineContaining("VERIDEX_SSE_ASYNC_TIMEOUT:120s"))
                .contains("request-timeout:");
        assertThat(lineContaining("VERIDEX_CHAT_TIMEOUT:60s"))
                .isEqualTo("timeout: ${VERIDEX_CHAT_TIMEOUT:60s}");
    }

    @Test
    void chatProviderDefaultsToDeterministic() throws Exception {
        assertThat(lineContaining("VERIDEX_CHAT_PROVIDER:deterministic"))
                .isEqualTo("provider: ${VERIDEX_CHAT_PROVIDER:deterministic}");
    }
}
