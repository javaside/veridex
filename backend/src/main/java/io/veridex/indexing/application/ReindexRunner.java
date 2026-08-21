package io.veridex.indexing.application;

import io.veridex.knowledge.api.KnowledgeBaseQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 一次性全量重建（spec §4.2）：仅 reindex profile 装配。恢复脚本用它起一次性容器，
 * 对全部知识库逐个全量发布（幂等、alias 切换、状态机均复用 publish 语义），跑完即退。
 * 不暴露任何 HTTP/常驻 API。成功退出码 0，任一 KB 失败 1（重跑安全）。
 */
@Component
@Profile("reindex")
public class ReindexRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReindexRunner.class);

    private final KnowledgeBaseQuery knowledgeBases;
    private final PublishCoordinator coordinator;
    private volatile int exitCode = 0;

    public ReindexRunner(KnowledgeBaseQuery knowledgeBases, PublishCoordinator coordinator) {
        this.knowledgeBases = knowledgeBases;
        this.coordinator = coordinator;
    }

    @Override
    public void run(ApplicationArguments args) {
        int ok = 0;
        int failed = 0;
        for (var kbId : knowledgeBases.findAllIds()) {
            try {
                coordinator.publishAndWait(kbId);
                ok++;
                log.info("reindex: kb={} published", kbId);
            } catch (RuntimeException e) {
                failed++;
                // 消息只含 KB id 与异常类名，不落证据/内容（可观测性边界）
                log.warn("reindex: kb={} failed: {}", kbId, e.getClass().getSimpleName());
            }
        }
        if (failed > 0) {
            exitCode = 1;
        }
        log.info("reindex: done ok={} failed={}", ok, failed);
        // Boot 4.0：runner 内直接以退出码结束进程（ExitCodeGenerator 兜底异常路径）。
        // 经 exit() 钩子调用以便单测覆写（生产路径仍为 System.exit）。
        exit(exitCode);
    }

    /**
     * 退出钩子：默认 System.exit；测试子类可覆写避免终止测试 JVM，语义不变。
     */
    protected void exit(int code) {
        System.exit(code);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
