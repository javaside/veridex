package io.veridex.knowledge.api;

import java.util.List;
import java.util.UUID;

/**
 * 知识范围计算门面（供 qa 等模块跨模块调用）：返回「用户授权 VIEW ∩ 请求知识库」的交集。
 * 服务端取交集，客户端指定范围不能超出授权。
 */
public interface KnowledgeScopeQuery {

    List<UUID> resolve(UUID userId, List<UUID> requestedKnowledgeBaseIds);
}
