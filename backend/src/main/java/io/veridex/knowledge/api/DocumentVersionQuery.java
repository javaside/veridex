package io.veridex.knowledge.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 文档版本的只读查询门面（供 retrieval 等模块跨模块调用，不泄漏 knowledge.domain 类型）。
 */
public interface DocumentVersionQuery {

    /**
     * 返回指定版本中仍在线的版本 id（排除 OFFLINE）。紧急下架即时生效，不依赖已发布快照。
     */
    List<UUID> findOnlineVersionIds(Collection<UUID> versionIds);
}
