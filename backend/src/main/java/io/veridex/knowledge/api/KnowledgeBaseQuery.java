package io.veridex.knowledge.api;

import java.util.List;
import java.util.UUID;

/**
 * 知识库只读查询门面（供 indexing 等模块跨模块调用，不泄漏 knowledge.domain 类型）。
 * ReindexRunner 需要枚举全部知识库 id 做一次性全量重建（spec §4.2）。
 */
public interface KnowledgeBaseQuery {

    /**
     * 返回全部知识库 id（含无文档/无发布的空库；publish 幂等保证空库重跑安全）。
     */
    List<UUID> findAllIds();
}
