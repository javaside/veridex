package io.veridex.ingestion.infrastructure;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 解析执行隔离：为每个版本创建不可预测的临时目录，在超时 deadline 内执行
 * 解析工作，并在成功、失败、超时和中断路径上清理目录。所有输出路径必须落在
 * 任务目录内，拒绝路径穿越。
 */
public final class ParserExecutionGuard {

    private final Duration timeout;
    private final Path tempRoot;

    public ParserExecutionGuard(Duration timeout, Path tempRoot) {
        this.timeout = timeout;
        this.tempRoot = tempRoot;
    }

    public <T> T execute(UUID versionId, ParserWork<T> work) {
        Path taskDir = createTaskDir(versionId);
        try {
            return runWithTimeout(() -> work.run(taskDir));
        } finally {
            cleanup(taskDir);
        }
    }

    /** 将相对名称解析到任务目录内；任何逃逸都返回固定错误码。 */
    public Path resolve(Path taskDir, String name) {
        Path normalized = taskDir.resolve(name).normalize();
        if (!normalized.startsWith(taskDir.normalize())) {
            throw new IllegalStateException("unsafe_path");
        }
        return normalized;
    }

    /** 幂等、有界的递归清理。清理失败不改变解析结果。 */
    public void cleanup(Path taskDirectory) {
        if (taskDirectory == null || !Files.exists(taskDirectory)) {
            return;
        }
        try (var paths = Files.walk(taskDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (Exception ignored) {
                    // 清理失败 fail-open：不改变解析结果
                }
            });
        } catch (Exception ignored) {
            // 遍历失败 fail-open
        }
    }

    private Path createTaskDir(UUID versionId) {
        try {
            Files.createDirectories(tempRoot);
            return Files.createTempDirectory(tempRoot, "parser-" + versionId + "-");
        } catch (Exception e) {
            throw new IllegalStateException("temp_dir_error", e);
        }
    }

    private <T> T runWithTimeout(Callable<T> work) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<T> future = executor.submit(work);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("parse_timeout", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("parse_error", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new IllegalStateException("parse_interrupted", e);
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    public interface ParserWork<T> {
        T run(Path taskDirectory) throws Exception;
    }
}
