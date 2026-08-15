package io.veridex.configuration.api;

import java.util.UUID;

/**
 * 配置 Profile 只读查询门面（供 evaluation 等模块跨模块读取已发布版本的五维配置）。
 */
public interface ConfigurationProfileQuery {

    ProfileConfig requireVersionConfig(UUID profileId, int versionNo);
}
