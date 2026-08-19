package io.veridex.qa.application;

import io.veridex.qa.api.AskRequest;
import io.veridex.qa.api.QaEvent;
import java.util.UUID;
import reactor.core.publisher.Flux;

/**
 * 问答编排端口：执行一次提问并返回 SSE 事件流。
 * cold Flux：只有订阅后才创建/校验会话、启动 QueryRun、检索并调用模型；
 * 单次订阅只执行一次问答流程，禁止隐式重试（设计 §4.3）。
 */
public interface QuestionAnsweringService {

    Flux<QaEvent> ask(UUID userId, AskRequest request);
}
