package io.veridex.iam.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 列表/吊销视图：永不含 token 明文。 */
public record ApiKeyView(
        UUID id,
        UUID userId,
        String name,
        String tokenPrefix,
        List<String> scopes,
        Instant createdAt,
        Instant revokedAt,
        Instant lastUsedAt) {}
