package io.veridex.generation.api;

/**
 * 生成生命周期事件（设计 §4.2）：错误经 Flux error channel 传播，取消经 Reactor cancel 传播，
 * 不伪造 Completed。Completed 只在完整文本已聚合、引用已校验、元数据已收尾后发出。
 */
public sealed interface GenerationEvent {

    record Delta(String text) implements GenerationEvent {
    }

    record Completed(GenerationResult result) implements GenerationEvent {
    }

    record Refused(GenerationResult result) implements GenerationEvent {
    }
}
