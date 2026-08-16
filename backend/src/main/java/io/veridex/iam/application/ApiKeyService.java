package io.veridex.iam.application;

import io.veridex.iam.domain.ApiKey;
import io.veridex.iam.domain.ApiKeyRepository;
import io.veridex.iam.domain.ApiKeyScope;
import io.veridex.iam.domain.ApiKeyTokenGenerator;
import io.veridex.iam.domain.PlatformUser;
import io.veridex.iam.domain.PlatformUserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiKeyService {

    private final ApiKeyRepository apiKeys;
    private final PlatformUserRepository users;
    private final ApiKeyTokenGenerator generator;

    public ApiKeyService(ApiKeyRepository apiKeys, PlatformUserRepository users, ApiKeyTokenGenerator generator) {
        this.apiKeys = apiKeys;
        this.users = users;
        this.generator = generator;
    }

    public record AuthenticatedKey(ApiKey key, PlatformUser user) {}

    @Transactional
    public io.veridex.iam.api.ApiKeyCreatedView createFor(
            UUID actorId, String actorRole, UUID targetUserId, String name, List<String> scopes) {
        if (!"PLATFORM_ADMIN".equals(actorRole) && !actorId.equals(targetUserId)) {
            throw new SecurityException("targetUserId must match actor unless caller is PLATFORM_ADMIN");
        }
        String validatedScopes = validateScopes(scopes);
        String validatedName = validateName(name);
        if (!users.existsById(targetUserId)) {
            throw new IllegalArgumentException("target user not found: " + targetUserId);
        }
        ApiKeyTokenGenerator.PlainToken plain = generator.issue();
        ApiKey key = new ApiKey(targetUserId, validatedName, plain.hash(), plain.prefix(), validatedScopes);
        apiKeys.save(key);
        return new io.veridex.iam.api.ApiKeyCreatedView(
                key.getId(), key.getUserId(), key.getName(), key.getTokenPrefix(),
                List.of(validatedScopes.split(",")), key.getCreatedAt(), plain.token());
    }

    @Transactional(readOnly = true)
    public List<io.veridex.iam.api.ApiKeyView> listFor(UUID actorId, String actorRole) {
        List<ApiKey> visibleKeys = "PLATFORM_ADMIN".equals(actorRole)
                ? apiKeys.findAllByOrderByCreatedAtDesc()
                : apiKeys.findByUserIdOrderByCreatedAtDesc(actorId);
        return visibleKeys.stream()
                .map(ApiKeyService::toView)
                .toList();
    }

    @Transactional
    public void revoke(UUID actorId, String actorRole, UUID keyId) {
        ApiKey key = apiKeys.findById(keyId)
                .orElseThrow(() -> new IllegalArgumentException("api key not found: " + keyId));
        if (!"PLATFORM_ADMIN".equals(actorRole) && !actorId.equals(key.getUserId())) {
            throw new SecurityException("only own api keys can be revoked unless caller is PLATFORM_ADMIN");
        }
        key.revoke();
        apiKeys.save(key);
    }

    /** Bearer token 校验：哈希查找 + 未吊销 + 用户可用，成功则标记 lastUsedAt。 */
    @Transactional
    public Optional<AuthenticatedKey> authenticate(String token) {
        if (token == null || !token.startsWith("vd_")) {
            return Optional.empty();
        }
        return apiKeys.findByTokenHash(generator.hash(token))
                .filter(k -> !k.isRevoked())
                .flatMap(k -> users.findById(k.getUserId())
                        .filter(PlatformUser::isEnabled)
                        .map(u -> {
                            k.markUsed();
                            apiKeys.save(k);
                            return new AuthenticatedKey(k, u);
                        }));
    }

    private static String validateScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("scopes must not be empty");
        }
        return scopes.stream()
                .map(ApiKeyScope::fromValue)
                .distinct()
                .map(ApiKeyScope::value)
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 200) {
            throw new IllegalArgumentException("name must be 1-200 characters");
        }
        return name.trim();
    }

    private static io.veridex.iam.api.ApiKeyView toView(ApiKey key) {
        List<String> scopes = ApiKeyScope.parse(key.getScopes()).stream()
                .map(ApiKeyScope::value)
                .toList();
        return new io.veridex.iam.api.ApiKeyView(
                key.getId(), key.getUserId(), key.getName(), key.getTokenPrefix(),
                scopes, key.getCreatedAt(), key.getRevokedAt(), key.getLastUsedAt());
    }
}
