package io.veridex.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.veridex.indexing.application.PublishCoordinator;
import io.veridex.indexing.application.ReindexRunner;
import io.veridex.knowledge.api.KnowledgeBaseQuery;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 一次性全量重建（spec §4.2）：reindex profile 下对全部 KB 逐个同步发布；
 * 任一失败返回退出码 1，全部成功 0。发布幂等保证重跑安全。
 *
 * <p>run() 末尾直接 System.exit(exitCode)（Boot 4 无 SpringApplicationExit；Mockito 禁止
 * mockStatic(java.lang.System)），故测试以匿名子类覆写受保护的 exit() 钩子，仅拦截
 * 退出动作，断言仍落在 getExitCode() 与发布调用序列上，语义不变。
 */
@ExtendWith(MockitoExtension.class)
class ReindexRunnerTest {

    @Mock KnowledgeBaseQuery knowledgeBases;
    @Mock PublishCoordinator coordinator;

    private ReindexRunner runner() {
        return new ReindexRunner(knowledgeBases, coordinator) {
            @Override
            protected void exit(int code) {
                // 单测吞掉退出动作，防止终止测试 JVM
            }
        };
    }

    @Test
    void republishesEveryKnowledgeBaseAndExitsZero() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(knowledgeBases.findAllIds()).thenReturn(List.of(a, b));

        ReindexRunner runner = runner();
        runner.run(null);

        assertThat(runner.getExitCode()).isZero();
        verify(coordinator).publishAndWait(a);
        verify(coordinator).publishAndWait(b);
    }

    @Test
    void exitsNonZeroWhenAnyPublishFails() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(knowledgeBases.findAllIds()).thenReturn(List.of(a, b));
        org.mockito.Mockito.doThrow(new IllegalStateException("no READY document versions to publish"))
                .when(coordinator).publishAndWait(a);

        ReindexRunner runner = runner();
        runner.run(null);

        assertThat(runner.getExitCode()).isEqualTo(1);
    }
}
