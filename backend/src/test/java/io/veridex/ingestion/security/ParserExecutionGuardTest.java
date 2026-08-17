package io.veridex.ingestion.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.veridex.ingestion.infrastructure.ParserExecutionGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParserExecutionGuardTest {

    @TempDir Path tempRoot;

    private ParserExecutionGuard guard(Duration timeout) {
        return new ParserExecutionGuard(timeout, tempRoot);
    }

    @Test
    void timeoutCleansTaskDirectoryAndReturnsFixedCode() {
        ParserExecutionGuard guard = guard(Duration.ofMillis(100));

        assertThatThrownBy(() -> guard.execute(UUID.randomUUID(), taskDir -> {
            Files.writeString(taskDir.resolve("partial.txt"), "sentinel");
            Thread.sleep(500);
            return null;
        })).hasMessage("parse_timeout");

        // 临时目录已清理
        try (var paths = Files.list(tempRoot)) {
            assertThat(paths.findAny()).isEmpty();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void pathEscapeIsRejectedBeforeWrite() {
        ParserExecutionGuard guard = guard(Duration.ofSeconds(5));
        Path taskDir = tempRoot.resolve("task");
        assertThatThrownBy(() -> guard.resolve(taskDir, "../outside.txt"))
                .hasMessage("unsafe_path");
    }

    @Test
    void successfulWorkRunsInIsolatedDirectoryAndCleansUp() throws Exception {
        ParserExecutionGuard guard = guard(Duration.ofSeconds(5));
        UUID versionId = UUID.randomUUID();

        String result = guard.execute(versionId, taskDir -> {
            assertThat(taskDir).exists();
            assertThat(taskDir.getFileName().toString()).contains(versionId.toString());
            Files.writeString(taskDir.resolve("result.txt"), "ok");
            return "done";
        });

        assertThat(result).isEqualTo("done");
        try (var paths = Files.list(tempRoot)) {
            assertThat(paths.findAny()).isEmpty();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void cleanupIsIdempotent() throws Exception {
        ParserExecutionGuard guard = guard(Duration.ofSeconds(5));
        Path taskDir = Files.createTempDirectory(tempRoot, "cleanup");
        Files.writeString(taskDir.resolve("a.txt"), "x");

        guard.cleanup(taskDir);
        guard.cleanup(taskDir); // 幂等

        assertThat(Files.exists(taskDir)).isFalse();
    }

    @Test
    void parserExceptionIsPropagated() {
        ParserExecutionGuard guard = guard(Duration.ofSeconds(5));

        assertThatThrownBy(() -> guard.execute(UUID.randomUUID(), taskDir -> {
            throw new IllegalArgumentException("parse_error");
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("parse_error");

        try (var paths = Files.list(tempRoot)) {
            assertThat(paths.findAny()).isEmpty();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void resolveAllowsSafeNestedPath() {
        ParserExecutionGuard guard = guard(Duration.ofSeconds(5));
        Path taskDir = tempRoot.resolve("task");
        Path safe = guard.resolve(taskDir, "docs/section.txt");
        assertThat(safe.startsWith(taskDir)).isTrue();
        assertThatCode(() -> guard.resolve(taskDir, "docs/section.txt")).doesNotThrowAnyException();
    }
}
