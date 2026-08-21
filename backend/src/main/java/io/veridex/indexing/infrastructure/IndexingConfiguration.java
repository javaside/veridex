package io.veridex.indexing.infrastructure;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 索引发布专用的后台执行线程池。
 *
 * <p>发布是「重 IO + 重 CPU」的串行操作：嵌入阶段会长时间占用 Ollama，且多个知识库
 * 并发发布会互相抢占同一嵌入模型与 OpenSearch，导致整体更慢并放大超时。因此固定为
 * 单线程串行执行，队列按需排队——发布是低频管理操作，串行可接受，换取可预测的吞吐。
 */
@Configuration
public class IndexingConfiguration {

    @Bean(name = "indexingPublishExecutor")
    Executor indexingPublishExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setThreadNamePrefix("veridex-publish-");
        executor.initialize();
        return executor;
    }
}
