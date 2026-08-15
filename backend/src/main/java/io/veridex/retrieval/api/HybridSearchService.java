package io.veridex.retrieval.api;

import java.util.List;
import java.util.UUID;

/**
 * 混合检索编排端口：授权交集 → active release 快照 → 在线过滤 → 双路召回 → RRF 融合。
 */
public interface HybridSearchService {

    /**
     * @param authorizedKnowledgeBaseIds 用户全部有 VIEW 权限的知识库
     * @param requestedKnowledgeBaseIds  客户端请求的知识库（服务端取交集）
     * @return 进入上下文的证据 + 全部命中明细（供 Trace 记录）
     */
    HybridSearchResult search(UUID userId, List<UUID> authorizedKnowledgeBaseIds,
                              List<UUID> requestedKnowledgeBaseIds, String question);

    /**
     * 参数化检索：供评测运行按 ProfileConfig 驱动召回/融合/上下文组装参数。
     */
    HybridSearchResult search(UUID userId, List<UUID> authorizedKnowledgeBaseIds,
                              List<UUID> requestedKnowledgeBaseIds, String question,
                              RetrievalParameters parameters);
}
