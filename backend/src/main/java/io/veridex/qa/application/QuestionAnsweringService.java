package io.veridex.qa.application;

import io.veridex.qa.api.AskRequest;
import io.veridex.qa.api.QaEvent;
import java.util.List;
import java.util.UUID;

/**
 * 问答编排端口：执行一次提问并返回 SSE 事件序列。
 */
public interface QuestionAnsweringService {

    List<QaEvent> ask(UUID userId, AskRequest request);
}
