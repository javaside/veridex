package io.veridex.configuration.api;

import java.util.UUID;

/**
 * 配置 Profile 只读查询门面（供 evaluation、qa 等模块跨模块读取已发布版本的五维配置）。
 */
public interface ConfigurationProfileQuery {

    ProfileConfig requireVersionConfig(UUID profileId, int versionNo);

    /**
     * 读取「当前生效版本」的配置（知识管理员显式标记）；未设置时回退默认值。
     */
    ProfileConfig activeProfileConfig();
}
