package io.veridex.iam.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 创建成功视图：token 明文仅此一次返回。 */
public record ApiKeyCreatedView(
        UUID id,
        UUID userId,
        String name,
        String tokenPrefix,
        List<String> scopes,
        Instant createdAt,
        String token) {}
