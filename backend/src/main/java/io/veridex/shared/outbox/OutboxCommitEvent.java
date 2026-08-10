package io.veridex.shared.outbox;

/**
 * 事务提交信号：业务事务内由 {@link OutboxWriter#record} 发布，
 * {@link OutboxPublisher} 在 AFTER_COMMIT 阶段收到后发布未发送的 outbox 事件。
 */
public record OutboxCommitEvent() {
}
