# Phase 5-a API 治理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 API 治理基础：scoped API key（可吊销）、每用户固定窗口限流、Request ID 透传、springdoc OpenAPI 文档与 `/admin` key 管理页。

**Architecture:** iam 模块新增 ApiKey 域与 `/api/iam/keys` REST；key 认证通过 `ApiKeyAuthFilter` 复用 `PlatformUserDetails`（存量 controller 权限零改动），scope 判定在 Spring Security 授权层完成。限流与 Request ID 过滤器放 shared 基础设施。前端 `/admin` 从占位页换成 key 管理页。

**Tech Stack:** Java 21、Spring Boot 4.0.0、Spring Security、Flyway（V12）、Jackson 3（`tools.jackson`）、springdoc-openapi-starter-webmvc-api 3.1.0、React 19 + TypeScript + Vitest。

**设计文档:** `docs/superpowers/specs/2026-08-16-phase5a-api-governance-design.md`（决策 D1–D10，实现时不得偏离）

## Global Constraints

- Token 格式：`vd_` + 43 字符 URL-safe 随机（32 字节）；库中只存 SHA-256 hex（64 字符）；列表只展示前 8 字符明文前缀。
- Scope 枚举固定 6 个：`qa` / `knowledge:read` / `knowledge:write` / `configuration` / `evaluation` / `feedback`。
- 未映射到 scope 的路径（含 `/api/iam/keys`、`/v3/api-docs`、actuator）对 key 请求一律 403。
- 限流默认 600 req/min/user（Session 与 key 统一按 userId），超限 `429` + `Retry-After`。
- key 管理权限：`PLATFORM_ADMIN` 全量；`KNOWLEDGE_ADMIN` 仅自己的 key。
- springdoc 只用 `springdoc-openapi-starter-webmvc-api`（无 UI starter），`/v3/api-docs` 仅限管理员 Session。
- iam 模块依赖不变：`shared`；shared 新增类不得引入业务模块依赖。
- TDD：每个 Task 先写失败测试。迁移命名 `V12__api_key.sql`。
- 每个独立可测的 Task 单独提交；最后跑 `./scripts/verify.sh`。

---

## File Structure

**后端（`backend/src/main/java/io/veridex/`）：**

- Create `iam/domain/ApiKey.java` — 实体
- Create `iam/domain/ApiKeyRepository.java` — JPA 仓储
- Create `iam/domain/ApiKeyScope.java` — scope 枚举 + 路径映射
- Create `iam/domain/ApiKeyTokenGenerator.java` — token 生成与哈希
- Create `iam/application/ApiKeyService.java` — 创建/列表/吊销
- Create `iam/api/ApiKeyController.java` — REST
- Create `iam/api/ApiKeyView.java`、`iam/api/ApiKeyCreatedView.java`、`iam/api/CreateKeyRequest.java` — DTO
- Create `iam/api/ApiKeyExceptionHandler.java` — 403/400
- Create `iam/infrastructure/ApiKeyAuthFilter.java` — Bearer 认证过滤器
- Create `iam/infrastructure/ApiKeyScopeAuthorizationManager.java` — scope 判定
- Modify `iam/infrastructure/SecurityConfig.java` — 挂过滤器、api-docs 规则
- Create `shared/infrastructure/RequestIdFilter.java` — Request ID 过滤器
- Create `shared/infrastructure/RateLimitFilter.java` — 固定窗口限流
- Create `shared/infrastructure/RateLimitProperties.java` — 阈值配置
- Create `shared/infrastructure/RequestIds.java` — requestId 存取静态工具

**后端迁移与配置：**

- Create `backend/src/main/resources/db/migration/V12__api_key.sql`
- Modify `backend/src/main/resources/application.yml` — 限流配置 + springdoc 配置
- Modify `backend/pom.xml` — springdoc 依赖

**后端测试：**

- Create `backend/src/test/java/io/veridex/iam/ApiKeyServiceTest.java`
- Create `backend/src/test/java/io/veridex/iam/ApiKeyScopeTest.java`
- Create `backend/src/test/java/io/veridex/iam/ApiKeyIntegrationTest.java`
- Create `backend/src/test/java/io/veridex/shared/RateLimitFilterTest.java`
- Create `backend/src/test/java/io/veridex/shared/RequestIdFilterTest.java`
- Modify `backend/src/test/java/io/veridex/support/DatabaseMigrationTest.java` — V12 断言
- Create `backend/src/test/java/io/veridex/OpenApiDocsTest.java`

**审计补传 requestId：**

- Modify `knowledge/api/DocumentUploadHandler.java`
- Modify `ingestion/infrastructure/DocumentIngestionWorker.java`

**前端：**

- Create `web/src/features/admin/adminApi.ts`
- Create `web/src/features/admin/ApiKeyAdminPage.tsx`
- Create `web/src/features/admin/ApiKeyAdminPage.test.tsx`
- Create `web/src/features/admin/components/CreateKeyDialog.tsx`
- Create `web/src/features/admin/components/KeyList.tsx`
- Modify `web/src/app/routes.tsx` — `/admin` 换真页
- Modify `web/src/styles.css` — key 列表样式
- Modify `docs/architecture.md` — API 表补 `/api/iam/keys` 与 `/v3/api-docs`

---

### Task 1: ApiKey 域模型与迁移 V12

**Files:**
- Create `backend/src/main/java/io/veridex/iam/domain/ApiKey.java`
- Create `backend/src/main/java/io/veridex/iam/domain/ApiKeyRepository.java`
- Create `backend/src/main/java/io/veridex/iam/domain/ApiKeyScope.java`
- Create `backend/src/main/java/io/veridex/iam/domain/ApiKeyTokenGenerator.java`
- Create `backend/src/main/resources/db/migration/V12__api_key.sql`
- Test `backend/src/test/java/io/veridex/iam/ApiKeyServiceTest.java`（本 Task 只测 generator）
- Modify `backend/src/test/java/io/veridex/support/DatabaseMigrationTest.java`

**Interfaces:**
- Produces: `ApiKeyTokenGenerator.issue()` → `ApiKeyTokenGenerator.PlainToken(String token, String hash, String prefix)`；`ApiKeyScope` 枚举值与 `ApiKeyScope.allows(String method, String path)`；`ApiKeyRepository.findByTokenHash(String)` / `findByUserIdOrderByCreatedAtDesc(UUID)` / `existsByTokenHash(String)`
- 后续 Task 依赖这些确切签名。

- [ ] **Step 1: 写失败测试（generator + scope）**

`ApiKeyServiceTest.java` 先建骨架，测试挂到 generator 上（service 后续 Task 再加）：

```java
package io.veridex.iam;

import io.veridex.iam.domain.ApiKeyScope;
import io.veridex.iam.domain.ApiKeyTokenGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyServiceTest {

    @Test
    void issuedTokenHasVdPrefixHashAndSafePrefix() {
        ApiKeyTokenGenerator.PlainToken t = new ApiKeyTokenGenerator().issue();
        assertThat(t.token()).startsWith("vd_").hasSize(46); // vd_ + 43
        assertThat(t.token()).matches("vd_[A-Za-z0-9_-]{43}");
        assertThat(t.hash()).hasSize(64).doesNotContain(t.token());
        assertThat(t.prefix()).isEqualTo(t.token().substring(0, 8));
    }

    @Test
    void sameTokenAlwaysHashesIdentically() {
        ApiKeyTokenGenerator gen = new ApiKeyTokenGenerator();
        String token = gen.issue().token();
        assertThat(gen.hash(token)).isEqualTo(gen.hash(token)).hasSize(64);
    }

    @Test
    void scopeMatchesNamespacePaths() {
        assertThat(ApiKeyScope.QA.allows("POST", "/api/qa/ask")).isTrue();
        assertThat(ApiKeyScope.QA.allows("GET", "/api/qa/conversations")).isTrue();
        assertThat(ApiKeyScope.QA.allows("GET", "/api/evaluation/datasets")).isFalse();

        assertThat(ApiKeyScope.KNOWLEDGE_READ.allows("GET", "/api/knowledge-bases")).isTrue();
        assertThat(ApiKeyScope.KNOWLEDGE_READ.allows("GET", "/api/documents/x/versions/y/chunks")).isTrue();
        assertThat(ApiKeyScope.KNOWLEDGE_READ.allows("POST", "/api/knowledge-bases")).isFalse();
        assertThat(ApiKeyScope.KNOWLEDGE_WRITE.allows("POST", "/api/knowledge-bases")).isTrue();
        assertThat(ApiKeyScope.KNOWLEDGE_WRITE.allows("GET", "/api/knowledge-bases")).isFalse();

        assertThat(ApiKeyScope.CONFIGURATION.allows("PUT", "/api/configuration/profiles/x")).isTrue();
        assertThat(ApiKeyScope.EVALUATION.allows("POST", "/api/evaluation/runs")).isTrue();
        assertThat(ApiKeyScope.FEEDBACK.allows("POST", "/api/feedback")).isTrue();
    }

    @Test
    void scopeNeverMatchesKeyManagementOrDocsPaths() {
        for (ApiKeyScope scope : ApiKeyScope.values()) {
            assertThat(scope.allows("POST", "/api/iam/keys")).isFalse();
            assertThat(scope.allows("GET", "/api/iam/keys")).isFalse();
            assertThat(scope.allows("GET", "/v3/api-docs")).isFalse();
            assertThat(scope.allows("GET", "/actuator/health")).isFalse();
        }
    }
}
```

- [ ] **Step 2: 运行确认失败** — `./mvnw -pl backend test -Dtest=ApiKeyServiceTest`
  预期：编译错误（类不存在）。

- [ ] **Step 3: 实现枚举、生成器、实体、仓储**

`ApiKeyScope.java`：

```java
package io.veridex.iam.domain;

import java.util.List;

public enum ApiKeyScope {
    QA(List.of("/api/qa")) {
        @Override public boolean allows(String method, String path) { return matches(path); }
    },
    KNOWLEDGE_READ(List.of("/api/knowledge-bases", "/api/documents")) {
        @Override public boolean allows(String method, String path) {
            return "GET".equalsIgnoreCase(method) && matches(path);
        }
    },
    KNOWLEDGE_WRITE(List.of("/api/knowledge-bases", "/api/documents")) {
        @Override public boolean allows(String method, String path) {
            return !"GET".equalsIgnoreCase(method) && !"OPTIONS".equalsIgnoreCase(method) && matches(path);
        }
    },
    CONFIGURATION(List.of("/api/configuration")) {
        @Override public boolean allows(String method, String path) { return matches(path); }
    },
    EVALUATION(List.of("/api/evaluation")) {
        @Override public boolean allows(String method, String path) { return matches(path); }
    },
    FEEDBACK(List.of("/api/feedback")) {
        @Override public boolean allows(String method, String path) { return matches(path); }
    };

    private final List<String> prefixes;

    ApiKeyScope(List<String> prefixes) { this.prefixes = prefixes; }

    protected boolean matches(String path) {
        return prefixes.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/") || path.startsWith(p + "?"));
    }

    public abstract boolean allows(String method, String path);

    /** scopes 逗号串（DB 存储）→ 枚举集合；未知值抛 IllegalArgumentException */
    public static java.util.Set<ApiKeyScope> parse(String csv) {
        if (csv == null || csv.isBlank()) throw new IllegalArgumentException("scopes must not be empty");
        var set = new java.util.LinkedHashSet<ApiKeyScope>();
        for (String part : csv.split(",")) {
            set.add(ApiKeyScope.valueOf(part.trim()));
        }
        return set;
    }
}
```

`ApiKeyTokenGenerator.java`：

```java
package io.veridex.iam.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public class ApiKeyTokenGenerator {

    public record PlainToken(String token, String hash, String prefix) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    public PlainToken issue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = "vd_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new PlainToken(token, hash(token), token.substring(0, 8));
    }

    public String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(token.getBytes(StandardCharsets.US_ASCII));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
```

`ApiKey.java`：

```java
package io.veridex.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_key")
public class ApiKey {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "token_prefix", nullable = false, length = 16)
    private String tokenPrefix;

    @Column(nullable = false, length = 500)
    private String scopes;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ApiKey() {
    }

    public ApiKey(UUID userId, String name, String tokenHash, String tokenPrefix, String scopes) {
        this.userId = userId;
        this.name = name;
        this.tokenHash = tokenHash;
        this.tokenPrefix = tokenPrefix;
        this.scopes = scopes;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getName() { return name; }
    public String getTokenHash() { return tokenHash; }
    public String getTokenPrefix() { return tokenPrefix; }
    public String getScopes() { return scopes; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public boolean isRevoked() { return revokedAt != null; }
    public void revoke() { this.revokedAt = Instant.now(); }
    public void markUsed() { this.lastUsedAt = Instant.now(); }
}
```

`ApiKeyRepository.java`：

```java
package io.veridex.iam.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
    Optional<ApiKey> findByTokenHash(String tokenHash);
    java.util.List<ApiKey> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
```

- [ ] **Step 4: 迁移 V12**

`V12__api_key.sql`：

```sql
-- Phase 5-a API 治理：scoped API key
CREATE TABLE api_key (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(200) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    token_prefix VARCHAR(16) NOT NULL,
    scopes VARCHAR(500) NOT NULL,
    revoked_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_api_key_user ON api_key (user_id);
```

`DatabaseMigrationTest.java` 的 `flywayAppliesPlatformBaselineMigration` 中：

```java
assertThat(versions).contains("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
```

并确认 `tableNames(connection)` 断言追加 `.contains("api_key")`（在已有的 contains 链上追加）。

- [ ] **Step 5: 运行确认通过** — `./mvnw -pl backend test "-Dtest=ApiKeyServiceTest,DatabaseMigrationTest"`

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/io/veridex/iam/domain backend/src/main/resources/db/migration/V12__api_key.sql backend/src/test/java/io/veridex/iam/ApiKeyServiceTest.java backend/src/test/java/io/veridex/support/DatabaseMigrationTest.java
git commit -m "feat: add api key domain model with scoped tokens"
```

---

### Task 2: ApiKeyService 与 REST API

**Files:**
- Create `iam/application/ApiKeyService.java`
- Create `iam/api/CreateKeyRequest.java`
- Create `iam/api/ApiKeyCreatedView.java`
- Create `iam/api/ApiKeyView.java`
- Create `iam/api/ApiKeyController.java`
- Create `iam/api/ApiKeyExceptionHandler.java`
- Test `backend/src/test/java/io/veridex/iam/ApiKeyServiceTest.java`（追加）
- Test `backend/src/test/java/io/veridex/iam/ApiKeyIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 的 `ApiKey`/`ApiKeyRepository`/`ApiKeyScope.parse`/`ApiKeyTokenGenerator.issue()`
- Produces: `ApiKeyService.create(UUID actorId, String actorRole, String name, java.util.List<String> scopes)` → `ApiKeyCreatedView`；`listFor(UUID actorId, String actorRole)` → `List<ApiKeyView>`；`revoke(UUID actorId, String actorRole, UUID keyId)`；`authenticate(String bearerToken)` → `Optional<AuthenticatedKey>`，其中 `record AuthenticatedKey(ApiKey key, io.veridex.iam.domain.PlatformUser user)`（Task 3 过滤器依赖此签名）

- [ ] **Step 1: 追加失败的服务测试**

在 `ApiKeyServiceTest.java` 追加（Mockito 风格，参考 `ConfigurationProfileServiceTest`）：

```java
    @Test
    void createHashesTokenAndStoresScopes() {
        ApiKeyRepository repo = mock(ApiKeyRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ApiKeyService service = new ApiKeyService(repo, mock(io.veridex.iam.domain.PlatformUserRepository.class), new ApiKeyTokenGenerator());

        ApiKeyCreatedView created = service.create(ACTOR_ADMIN, "PLATFORM_ADMIN", "集成用", java.util.List.of("qa"));

        assertThat(created.token()).startsWith("vd_");
        assertThat(created.scopes()).isEqualTo(java.util.List.of("qa"));
        verify(repo).save(argThat((ApiKey k) ->
                k.getTokenHash().length() == 64 && !k.getTokenHash().equals(created.token()) && k.getTokenPrefix().equals(created.token().substring(0, 8))));
    }

    @Test
    void createRejectsUnknownScope() {
        ApiKeyService service = new ApiKeyService(mock(ApiKeyRepository.class),
                mock(io.veridex.iam.domain.PlatformUserRepository.class), new ApiKeyTokenGenerator());
        assertThatThrownBy(() -> service.create(ACTOR_ADMIN, "PLATFORM_ADMIN", "x", java.util.List.of("root")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void knowledgeAdminCanOnlyManageOwnKeys() {
        ApiKeyRepository repo = mock(ApiKeyRepository.class);
        ApiKeyService service = new ApiKeyService(repo, mock(io.veridex.iam.domain.PlatformUserRepository.class), new ApiKeyTokenGenerator());

        // 为他人创建 → 403（SecurityException）
        assertThatThrownBy(() -> service.create(ACTOR_KADMIN, "KNOWLEDGE_ADMIN", "x", java.util.List.of("qa")))
                .hasMessageContaining("targetUserId") ; // kadmin 只能 owner=自己，见实现签名

        // 吊销他人的 key → 403
        ApiKey others = new ApiKey(ACTOR_ADMIN, "n", "h", "vd_12345", "qa");
        when(repo.findById(any())).thenReturn(java.util.Optional.of(others));
        assertThatThrownBy(() -> service.revoke(ACTOR_KADMIN, "KNOWLEDGE_ADMIN", UUID.randomUUID()))
                .isInstanceOf(SecurityException.class);
    }
```

类顶部常量：

```java
    private static final UUID ACTOR_ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_KADMIN = UUID.fromString("00000000-0000-0000-0000-000000000002");
```

- [ ] **Step 2: 实现 service**

`ApiKeyService.java`：

```java
package io.veridex.iam.application;

import io.veridex.iam.api.ApiKeyCreatedView;
import io.veridex.iam.api.ApiKeyView;
import io.veridex.iam.domain.ApiKey;
import io.veridex.iam.domain.ApiKeyRepository;
import io.veridex.iam.domain.ApiKeyScope;
import io.veridex.iam.domain.ApiKeyTokenGenerator;
import io.veridex.iam.domain.PlatformUser;
import io.veridex.iam.domain.PlatformUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiKeyService {

    /** key 认证结果：Task 3 的 ApiKeyAuthFilter 消费。 */
    public record AuthenticatedKey(ApiKey key, PlatformUser user) {}

    private final ApiKeyRepository keys;
    private final PlatformUserRepository users;
    private final ApiKeyTokenGenerator generator;

    public ApiKeyService(ApiKeyRepository keys, PlatformUserRepository users, ApiKeyTokenGenerator generator) {
        this.keys = keys;
        this.users = users;
        this.generator = generator;
    }

    @Transactional
    public ApiKeyCreatedView create(UUID actorId, String actorRole, String name, List<String> scopes) {
        return createFor(actorId, actorRole, actorId, name, scopes);
    }

    @Transactional
    public ApiKeyCreatedView createFor(UUID actorId, String actorRole, UUID targetUserId,
                                       String name, List<String> scopes) {
        boolean platformAdmin = "PLATFORM_ADMIN".equals(actorRole);
        if (!platformAdmin && !actorId.equals(targetUserId)) {
            throw new SecurityException("knowledge admin may only create keys for targetUserId=self");
        }
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        if (scopes == null || scopes.isEmpty()) throw new IllegalArgumentException("scopes must not be empty");
        String csv = scopes.stream()
                .map(s -> ApiKeyScope.valueOf(s.trim()))
                .map(Enum::name)
                .distinct()
                .reduce((a, b) -> a + "," + b)
                .orElseThrow();
        ApiKeyTokenGenerator.PlainToken plain = generator.issue();
        ApiKey saved = keys.save(new ApiKey(targetUserId, name.trim(), plain.hash(), plain.prefix(), csv));
        return new ApiKeyCreatedView(saved.getId(), saved.getName(), plain.token(), scopes, saved.getCreatedAt().toString());
    }

    @Transactional(readOnly = true)
    public List<ApiKeyView> listFor(UUID actorId, String actorRole) {
        boolean platformAdmin = "PLATFORM_ADMIN".equals(actorRole);
        List<ApiKey> list = platformAdmin ? keys.findAll() : keys.findByUserIdOrderByCreatedAtDesc(actorId);
        return list.stream().map(this::toView).toList();
    }

    @Transactional
    public void revoke(UUID actorId, String actorRole, UUID keyId) {
        ApiKey key = keys.findById(keyId).orElseThrow(() -> new IllegalArgumentException("unknown key"));
        boolean platformAdmin = "PLATFORM_ADMIN".equals(actorRole);
        if (!platformAdmin && !key.getUserId().equals(actorId)) {
            throw new SecurityException("knowledge admin may only revoke own keys");
        }
        if (!key.isRevoked()) key.revoke();
    }

    /** Bearer token → 已认证 key + 用户；无效/吊销/禁用返回 empty。 */
    @Transactional
    public Optional<AuthenticatedKey> authenticate(String bearerToken) {
        if (bearerToken == null || !bearerToken.startsWith("vd_")) return Optional.empty();
        Optional<ApiKey> found = keys.findByTokenHash(generator.hash(bearerToken));
        if (found.isEmpty() || found.get().isRevoked()) return Optional.empty();
        ApiKey key = found.get();
        Optional<PlatformUser> user = users.findById(key.getUserId());
        if (user.isEmpty() || !user.get().isEnabled()) return Optional.empty();
        key.markUsed();
        keys.save(key);
        return Optional.of(new AuthenticatedKey(key, user.get()));
    }

    private ApiKeyView toView(ApiKey k) {
        return new ApiKeyView(k.getId(), k.getName(), k.getTokenPrefix(), k.getUserId(),
                List.of(k.getScopes().split(",")), k.getCreatedAt().toString(),
                k.getRevokedAt() == null ? null : k.getRevokedAt().toString(),
                k.getLastUsedAt() == null ? null : k.getLastUsedAt().toString());
    }
}
```

`PlatformUserRepository` 若无 `findById` 继承外的需求——JPA `JpaRepository` 已提供，无需改。

DTO（record，每个一文件）：

```java
// CreateKeyRequest.java
package io.veridex.iam.api;
public record CreateKeyRequest(String name, java.util.List<String> scopes) {}

// ApiKeyCreatedView.java
package io.veridex.iam.api;
public record ApiKeyCreatedView(java.util.UUID id, String name, String token,
                                java.util.List<String> scopes, String createdAt) {}

// ApiKeyView.java
package io.veridex.iam.api;
public record ApiKeyView(java.util.UUID id, String name, String tokenPrefix, java.util.UUID userId,
                         java.util.List<String> scopes, String createdAt,
                         String revokedAt, String lastUsedAt) {}
```

`ApiKeyController.java`：

```java
package io.veridex.iam.api;

import io.veridex.iam.application.ApiKeyService;
import io.veridex.iam.api.CurrentActor;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/iam/keys")
public class ApiKeyController {

    private final ApiKeyService service;

    public ApiKeyController(ApiKeyService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiKeyCreatedView> create(@RequestBody CreateKeyRequest body) {
        requireAdmin();
        ApiKeyCreatedView created = service.create(CurrentActor.id(), CurrentActor.role().name(), body.name(), body.scopes());
        return ResponseEntity.status(201).body(created);
    }

    @GetMapping
    public List<ApiKeyView> list() {
        requireAdmin();
        return service.listFor(CurrentActor.id(), CurrentActor.role().name());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        requireAdmin();
        service.revoke(CurrentActor.id(), CurrentActor.role().name(), id);
        return ResponseEntity.noContent().build();
    }

    private void requireAdmin() {
        io.veridex.iam.api.Role role = CurrentActor.role();
        if (role != io.veridex.iam.api.Role.PLATFORM_ADMIN && role != io.veridex.iam.api.Role.KNOWLEDGE_ADMIN) {
            throw new SecurityException("api key management requires admin role");
        }
    }
}
```

`ApiKeyExceptionHandler.java`（仿 `KnowledgeApiExceptionHandler`）：

```java
package io.veridex.iam.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiKeyExceptionHandler {

    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    void forbidden() {
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalArgumentException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    void badRequest() {
    }
}
```

注意：`KnowledgeApiExceptionHandler` 也是 `@RestControllerAdvice` 且无 basePackages 限定，会对所有 controller 生效——本类的行为与它一致（403/400），重复声明无害，保持两者一致即可。

- [ ] **Step 3: 追加失败的集成测试**

`ApiKeyIntegrationTest.java`（Session 流，RestTestClient 自动带 JSESSIONID）：

```java
package io.veridex.iam;

import io.veridex.support.PostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureRestTestClient
class ApiKeyIntegrationTest extends PostgresIntegrationTest {

    @Autowired RestTestClient rest;

    private void login(String user) {
        rest.post().uri("/api/auth/login?username=%s&password=veridex".formatted(user))
                .exchange().expectStatus().isOk();
    }

    @Test
    void adminCreatesListsAndRevokesKeysOverSession() {
        login("admin");
        String token = rest.post().uri("/api/iam/keys")
                .body(new CreateKeyBody("集成 key", List.of("qa", "feedback")))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.token").value(v -> assertThat((String) v).startsWith("vd_"))
                .returnResult().getResponseBody() == null ? null : null; // token 从 jsonPath 提取见下
        // 用 jsonPath 断言代替直接取值：
        rest.post().uri("/api/iam/keys")
                .body(new CreateKeyBody("集成 key", List.of("qa", "feedback")))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isNotEmpty()
                .jsonPath("$.token").value(v -> assertThat((String) v).startsWith("vd_"))
                .jsonPath("$.scopes[0]").isEqualTo("qa");

        rest.get().uri("/api/iam/keys").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].tokenPrefix").value(v -> assertThat((String) v).startsWith("vd_"))
                .jsonPath("$[0].token").doesNotExist();
    }

    @Test
    void employeeCannotManageKeys() {
        login("employee");
        rest.post().uri("/api/iam/keys")
                .body(new CreateKeyBody("x", List.of("qa")))
                .exchange().expectStatus().isForbidden();
    }

    record CreateKeyBody(String name, List<String> scopes) {}
}
```

- [ ] **Step 4: 运行确认通过** — `./mvnw -pl backend test "-Dtest=ApiKeyServiceTest,ApiKeyIntegrationTest"`

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/veridex/iam backend/src/test/java/io/veridex/iam
git commit -m "feat: add api key management service and REST API"
```

---

### Task 3: Bearer 认证过滤器与 scope 鉴权

**Files:**
- Create `iam/infrastructure/ApiKeyAuthFilter.java`
- Create `iam/infrastructure/ApiKeyScopeAuthorizationManager.java`
- Modify `iam/infrastructure/SecurityConfig.java`
- Test `backend/src/test/java/io/veridex/iam/ApiKeyIntegrationTest.java`（追加）

**Interfaces:**
- Consumes: `ApiKeyService.authenticate(String)` → `Optional<AuthenticatedKey>`（Task 2）；`PlatformUserDetails`、`SecurityContextRole`（既有）
- Produces: SecurityContext 中 principal 为 `PlatformUserDetails`、credentials 为 `ApiKeyService.AuthenticatedKey`（供授权管理器读取 scope）

- [ ] **Step 1: 追加失败的集成测试**

```java
    @Test
    void bearerKeyAuthenticatesAndScopeGatesNamespace() {
        login("admin");
        // 建 key（scope 仅 qa）
        var created = rest.post().uri("/api/iam/keys")
                .body(new CreateKeyBody("qa only", List.of("qa")))
                .exchange().expectStatus().isCreated()
                .expectBody(ApiKeyCreatedResponse.class).returnResult().getResponseBody();
        String token = created.token();

        // 无 session、用 Bearer 调 qa 命名空间（/api/qa/conversations 是 GET，deterministic 无外部依赖）
        rest.get().uri("/api/qa/conversations")
                .headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isOk();

        // scope 不匹配 → 403
        rest.get().uri("/api/evaluation/datasets")
                .headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isForbidden();

        // key 不可访问 key 管理 → 403
        rest.get().uri("/api/iam/keys")
                .headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void revokedBearerKeyIsRejected() {
        login("admin");
        var created = rest.post().uri("/api/iam/keys")
                .body(new CreateKeyBody("to revoke", List.of("qa")))
                .exchange().expectStatus().isCreated()
                .expectBody(ApiKeyCreatedResponse.class).returnResult().getResponseBody();

        rest.delete().uri("/api/iam/keys/" + created.id())
                .exchange().expectStatus().isNoContent();

        rest.get().uri("/api/qa/conversations")
                .headers(h -> h.setBearerAuth(created.token()))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void invalidBearerIsUnauthorizedWithoutRedirect() {
        rest.get().uri("/api/qa/conversations")
                .headers(h -> h.setBearerAuth("vd_invalidinvalidinvalidinvalidinvalidinvalidin"))
                .exchange().expectStatus().isUnauthorized();
    }

    record ApiKeyCreatedResponse(java.util.UUID id, String name, String token,
                                 java.util.List<String> scopes, String createdAt) {}
```

- [ ] **Step 2: 实现过滤器与授权管理器**

`ApiKeyAuthFilter.java`：

```java
package io.veridex.iam.infrastructure;

import io.veridex.iam.application.ApiKeyService;
import io.veridex.iam.domain.ApiKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer vd_ 开头的 Authorization 头 → 查库认证 → 放 PlatformUserDetails principal。
 * Session 已认证时忽略 key（浏览器优先）。认证失败直接 401 JSON（不走 formLogin 302）。
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer vd_")) {
            chain.doFilter(request, response);
            return;
        }
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing != null && existing.isAuthenticated()
                && !"anonymousUser".equals(String.valueOf(existing.getPrincipal()))) {
            chain.doFilter(request, response); // Session 优先
            return;
        }
        String token = header.substring("Bearer ".length());
        apiKeyService.authenticate(token).ifPresentOrElse(authenticated -> {
            var details = new PlatformUserDetails(authenticated.user());
            var authentication = new ApiKeyAuthentication(details, authenticated.key());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            try {
                chain.doFilter(request, response);
            } catch (IOException | ServletException e) {
                throw new RuntimeException(e);
            }
        }, () -> {
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            try {
                response.getWriter().write("{\"error\":\"invalid_api_key\"}");
            } catch (IOException ignored) {
            }
        });
    }

    /** principal=PlatformUserDetails；credentials=ApiKey（scope 判定用）。 */
    public static class ApiKeyAuthentication extends AbstractAuthenticationToken {

        private final PlatformUserDetails principal;
        private final transient ApiKey key;

        public ApiKeyAuthentication(PlatformUserDetails principal, ApiKey key) {
            super(principal.getAuthorities());
            this.principal = principal;
            this.key = key;
            setAuthenticated(true);
        }

        @Override public Object getCredentials() { return key; }
        @Override public Object getPrincipal() { return principal; }
        public ApiKey key() { return key; }
    }
}
```

`ApiKeyScopeAuthorizationManager.java`：

```java
package io.veridex.iam.infrastructure;

import io.veridex.iam.domain.ApiKeyScope;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * ApiKeyAuthentication 请求：scope 命中放行；Session 请求：交回 delegate（permitAll/authenticated 链）。
 */
@Component
public class ApiKeyScopeAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    @Override
    public AuthorizationDecision check(AuthorizationDecision downstream, RequestAuthorizationContext context) {
        return null; // 不直接注册，见 SecurityConfig 组合逻辑
    }

    public static boolean allows(Authentication authentication, HttpServletRequest request) {
        if (authentication instanceof ApiKeyAuthFilter.ApiKeyAuthentication keyAuth) {
            Set<ApiKeyScope> scopes = ApiKeyScope.parse(keyAuth.key().getScopes());
            String method = request.getMethod();
            String path = request.getRequestURI();
            return scopes.stream().anyMatch(s -> s.allows(method, path));
        }
        return true; // Session 请求不走 scope 限制
    }
}
```

`SecurityConfig.java` 修改 `securityFilterChain`（保持既有 formLogin/logout 不动）：

```java
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyService apiKeyService) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .addFilterBefore(new ApiKeyAuthFilter(apiKeyService), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/api/auth/login", "/api/auth/logout").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/v3/api-docs/**").hasAnyRole("PLATFORM_ADMIN", "KNOWLEDGE_ADMIN")
                .anyRequest().access((authentication, context) -> {
                    if (!authentication.isAuthenticated()) return new AuthorizationDecision(false);
                    if (ApiKeyScopeAuthorizationManager.allows(authentication.get(), context.getRequest())) {
                        return new AuthorizationDecision(true);
                    }
                    return new AuthorizationDecision(false);
                }))
            // formLogin/logout 原样保留
```

补充 import：

```java
import io.veridex.iam.application.ApiKeyService;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
```

`anyRequest().access(...)` 的 lambda 用 `Supplier<Authentication>` + `RequestAuthorizationContext` 签名。若 Boot 4 该 API 签名有出入，等价写法是自定义 `AuthorizationManager<RequestAuthorizationContext>` 类实例传入——以编译为准，语义不变：**Session 放行原逻辑、ApiKey 只放 scope 命中**。

- [ ] **Step 3: 运行确认通过** — `./mvnw -pl backend test "-Dtest=ApiKeyIntegrationTest"`

- [ ] **Step 4: 跑 ArchitectureTest** — `./mvnw -pl backend test -Dtest=ArchitectureTest`（iam 依赖未变，应通过）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/veridex/iam backend/src/test/java/io/veridex/iam
git commit -m "feat: authenticate bearer api keys with scope enforcement"
```

---

### Task 4: Request ID 过滤器与审计补传

**Files:**
- Create `shared/infrastructure/RequestIdFilter.java`
- Create `shared/infrastructure/RequestIds.java`
- Modify `knowledge/api/DocumentUploadHandler.java`
- Modify `ingestion/infrastructure/DocumentIngestionWorker.java`
- Test `backend/src/test/java/io/veridex/shared/RequestIdFilterTest.java`

**Interfaces:**
- Produces: `RequestIds.current(ServletRequest)` → `String`（静态工具，audit 调用点用）；响应头 `X-Request-Id`；MDC key `requestId`

- [ ] **Step 1: 写失败测试**

```java
package io.veridex.shared;

import io.veridex.shared.infrastructure.RequestIdFilter;
import io.veridex.shared.infrastructure.RequestIds;
import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    @Test
    void generatesRequestIdAndExposesHeaderMdcAndAttribute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/qa/conversations");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seen = new String[1];
        new RequestIdFilter().doFilter(request, response, (req, res) -> {
            seen[0] = RequestIds.current((jakarta.servlet.ServletRequest) req);
            assertThat(org.slf4j.MDC.get("requestId")).isEqualTo(seen[0]);
        });

        assertThat(seen[0]).isNotBlank();
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(seen[0]);
        assertThat(org.slf4j.MDC.get("requestId")).isNull(); // finally 清理
    }

    @Test
    void propagatesIncomingRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader("X-Request-Id", "trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new RequestIdFilter().doFilter(request, response, (req, res) -> {});

        assertThat(response.getHeader("X-Request-Id")).isEqualTo("trace-123");
    }

    @Test
    void rejectsRidiculousIncomingLength() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader("X-Request-Id", "x".repeat(200));
        MockHttpServletResponse response = new MockHttpServletResponse();
        new RequestIdFilter().doFilter(request, response, (req, res) -> {});

        assertThat(response.getHeader("X-Request-Id")).hasSizeLessThanOrEqualTo(100).isNotEqualTo("x".repeat(200));
    }
}
```

（MockFilterChain import 未用到则删除；重复的 MockHttpServletResponse import 去重。）

- [ ] **Step 2: 实现**

`RequestIds.java`：

```java
package io.veridex.shared.infrastructure;

import jakarta.servlet.ServletRequest;

public final class RequestIds {

    public static final String ATTRIBUTE = "veridex.requestId";
    public static final String HEADER = "X-Request-Id";

    private RequestIds() {
    }

    public static String current(ServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value == null ? null : value.toString();
    }
}
```

`RequestIdFilter.java`：

```java
package io.veridex.shared.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final int MAX_INCOMING_LENGTH = 100;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(RequestIds.HEADER);
        String requestId = incoming != null && !incoming.isBlank() && incoming.length() <= MAX_INCOMING_LENGTH
                ? incoming
                : UUID.randomUUID().toString();
        request.setAttribute(RequestIds.ATTRIBUTE, requestId);
        response.setHeader(RequestIds.HEADER, requestId);
        MDC.put("requestId", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }
}
```

- [ ] **Step 3: 审计调用点补 requestId**

`DocumentUploadHandler.java`（`audit.record(...)` 现第 5 参为 `null`）：

```java
        audit.record(actorId, "document.upload", "document_version", version.getId(),
                io.veridex.shared.infrastructure.RequestIds.current(request),
                Map.of("knowledgeBaseId", kbId.toString(), "filename", filename));
```

方法签名需含 `HttpServletRequest request` 参数——检查现有方法签名，若 handler 直接拿不到 request（如经 service 层），改为从 `RequestContextHolder.getRequestAttributes()` 取：

```java
String requestId = org.springframework.web.context.request.RequestContextHolder
        .getRequestAttributes() instanceof org.springframework.web.context.request.ServletRequestAttributes sra
        ? io.veridex.shared.infrastructure.RequestIds.current(sra.getRequest()) : null;
```

以最小侵入为准：优先给方法加参数；worker（`DocumentIngestionWorker.java:92`）是异步消费不在请求线程，**保持 null 不动**。

knowledge 模块 `allowedDependencies` 已含 `shared::config`、`shared::outbox`——`shared.infrastructure` 不在白名单内的话 ArchitectureTest 会失败；此时把依赖声明改为 `shared`（整体）或把 `RequestIds` 挪到 `shared` 根包。**以 ArchitectureTest 通过为准**，推荐：knowledge 的 package-info `allowedDependencies` 增加 `"shared"`（替换 `shared::config`, `shared::outbox`）。

- [ ] **Step 4: 运行确认通过** — `./mvnw -pl backend test "-Dtest=RequestIdFilterTest,ArchitectureTest"`

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/veridex/shared backend/src/main/java/io/veridex/knowledge backend/src/main/java/io/veridex/ingestion backend/src/test/java/io/veridex/shared
git commit -m "feat: propagate request ids across filters and audit records"
```

---

### Task 5: 固定窗口限流

**Files:**
- Create `shared/infrastructure/RateLimitProperties.java`
- Create `shared/infrastructure/RateLimitFilter.java`
- Modify `backend/src/main/resources/application.yml`
- Test `backend/src/test/java/io/veridex/shared/RateLimitFilterTest.java`

**Interfaces:**
- Consumes: 认证后的 `CurrentActor.id()`（请求已过 ApiKeyAuthFilter / Session 认证）
- Produces: `veridex.api.rate-limit-per-minute` 配置项（默认 600，0=关闭）；超限 429 + `Retry-After` + body `{"error":"rate_limited"}`

- [ ] **Step 1: 写失败测试**

```java
package io.veridex.shared;

import io.veridex.shared.infrastructure.RateLimitFilter;
import io.veridex.shared.infrastructure.RateLimitProperties;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private final UUID userId = UUID.randomUUID();

    private MockHttpServletResponse run(MockHttpServletRequest request, RateLimitProperties props) throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new RateLimitFilter(props).doFilter(request, response, (req, res) -> res.setStatus(200));
        return response;
    }

    private void authenticateAs(UUID id) {
        var token = new TestingAuthenticationToken("user", "creds");
        token.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @Test
    void blocksAfterLimitWithinSameMinute() throws Exception {
        RateLimitProperties props = new RateLimitProperties();
        props.setPerMinute(3);
        for (int i = 0; i < 3; i++) {
            assertThat(run(new MockHttpServletRequest(), props).getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse blocked = run(new MockHttpServletRequest(), props);
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotBlank();
    }

    @Test
    void zeroDisablesLimit() throws Exception {
        RateLimitProperties props = new RateLimitProperties();
        props.setPerMinute(0);
        for (int i = 0; i < 5; i++) {
            assertThat(run(new MockHttpServletRequest(), props).getStatus()).isEqualTo(200);
        }
    }
}
```

测试线程需在 `@AfterEach` 清 `SecurityContextHolder.clearContext()`。注意：计数 key 是 userId——filter 从 `SecurityContextHolder` 取；单测中 `TestingAuthenticationToken` 的 principal 不是 `PlatformUserDetails`，filter 应有 fallback：取不到 userId 时按「匿名桶」计数（key=`anonymous`），不拒绝认证本身。

- [ ] **Step 2: 实现**

`RateLimitProperties.java`：

```java
package io.veridex.shared.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "veridex.api")
public class RateLimitProperties {

    /** 每用户每分钟允许的请求数；0 关闭限流。 */
    private int rateLimitPerMinute = 600;

    public int getRateLimitPerMinute() { return rateLimitPerMinute; }
    public void setRateLimitPerMinute(int v) { this.rateLimitPerMinute = v; }

    /** 兼容测试属性名 perMinute。 */
    public void setPerMinute(int v) { setRateLimitPerMinute(v); }
}
```

`RateLimitFilter.java`：

```java
package io.veridex.shared.infrastructure;

import io.veridex.iam.infrastructure.PlatformUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 每用户固定窗口限流。挂在认证之后（order = 认证过滤器之后、AuthorizationFilter 之前）。
 * 未认证（permitAll 路径已通过认证链短路的不在此列）按 anonymous 桶。
 */
@Component
@Order(100)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties properties;
    private final Map<Long, Map<String, AtomicLong>> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int limit = properties.getRateLimitPerMinute();
        if (limit <= 0) {
            chain.doFilter(request, response);
            return;
        }
        long minute = Instant.now().getEpochSecond() / 60;
        Map<String, AtomicLong> window = windows.computeIfAbsent(minute, k -> new ConcurrentHashMap<>());
        windows.keySet().retainAll(java.util.Set.of(minute)); // 清理过期窗口
        String key = userKey();
        long count = window.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        if (count > limit) {
            long nextMinuteSeconds = 60 - Instant.now().getEpochSecond() % 60;
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", String.valueOf(Math.max(1, nextMinuteSeconds)));
            response.getWriter().write("{\"error\":\"rate_limited\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private String userKey() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof PlatformUserDetails details) {
            return details.user().getId().toString();
        }
        return "anonymous";
    }
}
```

**架构边界注意**：`shared` 模块依赖 `iam.infrastructure.PlatformUserDetails` 会违反模块边界（shared 不允许依赖 iam）。替代：key 取 `auth.getName()`（username，`PlatformUserDetails.getUsername()` 返回唯一用户名）——语义等价且零跨模块依赖。实现采用：

```java
    private String userKey() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() != null
                && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()))) {
            return auth.getName();
        }
        return "anonymous";
    }
```

`RateLimitProperties` 注册：找到现有 `@ConfigurationProperties` 扫描方式（`shared/infrastructure` 或启动类），若用 `@EnableConfigurationProperties` 则在同包加配置类：

```java
package io.veridex.shared.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {
}
```

`application.yml` 追加：

```yaml
veridex:
  api:
    rate-limit-per-minute: ${VERIDEX_RATE_LIMIT_PER_MINUTE:600}
```

`@Order(100)` 保证在 Spring Security 过滤器链之后执行（Security 默认 order -100）。若实测顺序不对（Boot 4 Security filter 注册 order 有变），用 `FilterRegistrationBean` 显式声明 order——以集成测试（Step 3）429 行为为准。

- [ ] **Step 3: 集成验证限流**

`ApiKeyIntegrationTest.java` 追加（用 `@TestPropertySource` 或本类独立 `@SpringBootTest(properties=...)` 不方便——单独建 `RateLimitIntegrationTest extends PostgresIntegrationTest`，加 `@SpringBootTest(properties = "veridex.api.rate-limit-per-minute=3")` 覆盖基类注解需在类上重声明——直接建独立测试类：

```java
package io.veridex.shared;

import io.veridex.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient;

@AutoConfigureRestTestClient
class RateLimitIntegrationTest extends PostgresIntegrationTest {

    @Autowired RestTestClient rest;

    // 覆盖限流阈值：复用 PostgresIntegrationTest 的机制，在类上加
    // @SpringBootTest(properties = {"veridex.api.rate-limit-per-minute=3",
    //         "spring.jpa.hibernate.ddl-auto=validate"})
    // 注意需完整复制基类注解属性，否则 context 缓存键不同

    @Test
    void fourthRequestWithinMinuteGets429() {
        rest.post().uri("/api/auth/login?username=employee&password=veridex").exchange().expectStatus().isOk();
        for (int i = 0; i < 3; i++) {
            rest.get().uri("/api/qa/conversations").exchange().expectStatus().isOk();
        }
        rest.get().uri("/api/qa/conversations").exchange().expectStatus().isEqualTo(429);
    }
}
```

类注解最终形态（完整复制基类属性 + 覆盖限流值）：

```java
@SpringBootTest(classes = io.veridex.VeridexApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.jpa.hibernate.ddl-auto=validate", "veridex.api.rate-limit-per-minute=3"})
```

- [ ] **Step 4: 运行确认通过** — `./mvnw -pl backend test "-Dtest=RateLimitFilterTest,RateLimitIntegrationTest"`

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/veridex/shared backend/src/main/resources/application.yml backend/src/test/java/io/veridex/shared
git commit -m "feat: add per-user fixed window rate limiting"
```

---

### Task 6: springdoc OpenAPI

**Files:**
- Modify `backend/pom.xml`
- Modify `backend/src/main/resources/application.yml`
- Test `backend/src/test/java/io/veridex/OpenApiDocsTest.java`

**Interfaces:**
- Produces: `GET /v3/api-docs` → OpenAPI 3 JSON（仅 admin Session）；配置 `springdoc.paths-to-match=/api/**`

- [ ] **Step 1: 写失败测试**

```java
package io.veridex;

import io.veridex.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient;

@AutoConfigureRestTestClient
class OpenApiDocsTest extends PostgresIntegrationTest {

    @Autowired RestTestClient rest;

    @Test
    void adminSessionSeesOpenApiJsonWithApiPaths() {
        rest.post().uri("/api/auth/login?username=admin&password=veridex").exchange().expectStatus().isOk();

        rest.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.openapi").isNotEmpty()
                .jsonPath("$.paths./api/qa/ask").exists()
                .jsonPath("$.paths./api/iam/keys").exists();
    }

    @Test
    void anonymousAndEmployeeAreRejected() {
        rest.get().uri("/v3/api-docs").exchange().expectStatus().is4xxClientError();

        rest.post().uri("/api/auth/login?username=employee&password=veridex").exchange().expectStatus().isOk();
        rest.get().uri("/v3/api-docs").exchange().expectStatus().isForbidden();
    }
}
```

- [ ] **Step 2: 加依赖与配置**

`backend/pom.xml` dependencies 追加：

```xml
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-api</artifactId>
            <version>3.1.0</version>
        </dependency>
```

`application.yml` 追加：

```yaml
springdoc:
  paths-to-match: /api/**
  default-support-form-authentication: false
```

Task 3 已在 SecurityConfig 放行规则加了 `/v3/api-docs/**` 的 admin 限定——若 Task 3 用的 matcher 已覆盖，此处无需重复。

- [ ] **Step 3: 运行** — `./mvnw -pl backend test -Dtest=OpenApiDocsTest`

**风险点**：v3.1.0 与 Boot 4.0.0 + Jackson 3（`tools.jackson`）若不兼容（NoClassDefFound / 序列化异常），降级方案：移除 starter，手写最小 `OpenApiController` 返回骨架 JSON（paths 从 `RequestMappingHandlerMapping` 反射枚举），测试断言不变。先验证官方 starter，不行再降级并在提交信息注明。

- [ ] **Step 4: 提交**

```bash
git add backend/pom.xml backend/src/main/resources/application.yml backend/src/test/java/io/veridex/OpenApiDocsTest.java
git commit -m "feat: expose OpenAPI docs for admins via springdoc"
```

---

### Task 7: 前端 key 管理页

**Files:**
- Create `web/src/features/admin/adminApi.ts`
- Create `web/src/features/admin/components/KeyList.tsx`
- Create `web/src/features/admin/components/CreateKeyDialog.tsx`
- Create `web/src/features/admin/ApiKeyAdminPage.tsx`
- Create `web/src/features/admin/ApiKeyAdminPage.test.tsx`
- Modify `web/src/app/routes.tsx`
- Modify `web/src/styles.css`
- Modify `docs/architecture.md`

**Interfaces:**
- Consumes: Task 2 的 REST（`POST/GET/DELETE /api/iam/keys`，DTO 字段名 `id/name/token/tokenPrefix/userId/scopes/createdAt/revokedAt/lastUsedAt`）
- Produces: `adminApi.listKeys()/createKey(name, scopes)/revokeKey(id)`；页面挂在路由 `/admin`

- [ ] **Step 1: adminApi.ts**

```ts
export type ApiKeyScope = 'qa' | 'knowledge:read' | 'knowledge:write' | 'configuration' | 'evaluation' | 'feedback'

export const API_KEY_SCOPES: { value: ApiKeyScope; label: string }[] = [
  { value: 'qa', label: '问答（/api/qa）' },
  { value: 'knowledge:read', label: '知识读取（GET /api/knowledge-bases, /api/documents）' },
  { value: 'knowledge:write', label: '知识写入（上传/发布）' },
  { value: 'configuration', label: '配置版本（/api/configuration）' },
  { value: 'evaluation', label: '评测（/api/evaluation）' },
  { value: 'feedback', label: '反馈（/api/feedback）' },
]

export type ApiKeyView = {
  id: string
  name: string
  tokenPrefix: string
  userId: string
  scopes: string[]
  createdAt: string
  revokedAt: string | null
  lastUsedAt: string | null
}

export type ApiKeyCreated = {
  id: string
  name: string
  token: string
  scopes: string[]
  createdAt: string
}

const json = async <T>(response: Response): Promise<T> => {
  if (!response.ok) {
    throw new Error((await response.text().catch(() => '')) || `请求失败 (${response.status})`)
  }
  return response.json() as Promise<T>
}

export const adminApi = {
  listKeys: (): Promise<ApiKeyView[]> =>
    fetch('/api/iam/keys', { credentials: 'include' }).then((r) => json<ApiKeyView[]>(r)),
  createKey: (name: string, scopes: ApiKeyScope[]): Promise<ApiKeyCreated> =>
    fetch('/api/iam/keys', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, scopes }),
    }).then((r) => json<ApiKeyCreated>(r)),
  revokeKey: (id: string): Promise<void> =>
    fetch(`/api/iam/keys/${id}`, { method: 'DELETE', credentials: 'include' }).then((r) => {
      if (!r.ok) throw new Error(`吊销失败 (${r.status})`)
    }),
}
```

- [ ] **Step 2: KeyList.tsx**

```tsx
import type { ApiKeyView } from '../adminApi'

const fmt = (iso: string | null) => (iso ? iso.replace('T', ' ').slice(0, 19) : '—')

export function KeyList({ keys, loading, error, onRetry, onRevoke }: {
  keys: ApiKeyView[]
  loading: boolean
  error: string | null
  onRetry: () => void
  onRevoke: (key: ApiKeyView) => void
}) {
  if (loading) {
    return <div role="status" className="loading-copy" style={{ padding: '14px' }}>正在加载 API key</div>
  }
  if (error) {
    return <div className="state-block compact"><p role="alert">{error}</p><button className="secondary-button" type="button" onClick={onRetry}>重试</button></div>
  }
  if (keys.length === 0) {
    return <div className="state-block compact"><h3>暂无 API key</h3><p>创建 key 供脚本或外部系统集成访问。</p></div>
  }
  return (
    <ul className="key-list">
      {keys.map((key) => (
        <li key={key.id} className="key-item">
          <div className="key-item-head">
            <strong>{key.name}</strong>
            <span className={key.revokedAt ? 'key-state revoked' : 'key-state active'}>{key.revokedAt ? '已吊销' : '生效中'}</span>
          </div>
          <div className="key-meta">{key.tokenPrefix}… · 创建 {fmt(key.createdAt)} · 最近使用 {fmt(key.lastUsedAt)}</div>
          <div className="key-scopes">{key.scopes.map((s) => <code key={s}>{s}</code>)}</div>
          {!key.revokedAt && (
            <div className="key-item-foot">
              <button className="secondary-button" type="button" onClick={() => onRevoke(key)}>吊销</button>
            </div>
          )}
        </li>
      ))}
    </ul>
  )
}
```

- [ ] **Step 3: CreateKeyDialog.tsx**

```tsx
import { useState } from 'react'
import { adminApi, API_KEY_SCOPES, type ApiKeyScope } from '../adminApi'

export function CreateKeyDialog({ onDone, onCancel, onNotify }: {
  onDone: () => void
  onCancel: () => void
  onNotify: (type: 'success' | 'error', message: string) => void
}) {
  const [name, setName] = useState('')
  const [scopes, setScopes] = useState<ApiKeyScope[]>([])
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [createdToken, setCreatedToken] = useState<string | null>(null)

  const toggleScope = (scope: ApiKeyScope) =>
    setScopes((prev) => (prev.includes(scope) ? prev.filter((s) => s !== scope) : [...prev, scope]))

  const submit = async () => {
    if (!name.trim() || scopes.length === 0) return
    setSubmitting(true)
    setError(null)
    try {
      const created = await adminApi.createKey(name.trim(), scopes)
      setCreatedToken(created.token)
    } catch (e) {
      setError(e instanceof Error ? e.message : '创建失败')
    } finally {
      setSubmitting(false)
    }
  }

  const close = () => {
    if (createdToken) navigator.clipboard?.writeText(createdToken).catch(() => {})
    onDone()
  }

  if (createdToken) {
    return (
      <div className="dialog-layer">
        <div className="dialog" role="dialog" aria-label="key 已创建">
          <h3>API key 已创建</h3>
          <p className="dialog-description">完整 token 只显示这一次，请立即复制保存：</p>
          <div className="key-token-display"><code>{createdToken}</code></div>
          <div className="dialog-actions">
            <button className="primary-button" type="button" onClick={() => void navigator.clipboard?.writeText(createdToken).catch(() => {})}>复制</button>
            <button className="secondary-button" type="button" onClick={close}>我已保存，关闭</button>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="dialog-layer">
      <div className="dialog" role="dialog" aria-label="创建 API key">
        <h3>创建 API key</h3>
        {error && <p className="row-error" role="alert">{error}</p>}
        <label className="dialog-field">名称
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="如：数据同步脚本" />
        </label>
        <div className="dialog-field">作用域（至少选一个）
          <div className="key-scope-list">
            {API_KEY_SCOPES.map(({ value, label }) => (
              <label key={value}>
                <input type="checkbox" checked={scopes.includes(value)} onChange={() => toggleScope(value)} />
                <span>{label}</span>
              </label>
            ))}
          </div>
        </div>
        <div className="dialog-actions">
          <button className="secondary-button" type="button" onClick={onCancel}>取消</button>
          <button className="primary-button" type="button" onClick={() => void submit()} disabled={submitting || !name.trim() || scopes.length === 0}>
            {submitting ? '创建中' : '创建'}
          </button>
        </div>
      </div>
    </div>
  )
}
```

- [ ] **Step 4: ApiKeyAdminPage.tsx**

```tsx
import { useCallback, useEffect, useState } from 'react'
import { PageHeader } from '../../app/PageHeader'
import { Toast } from '../../components/Toast'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { adminApi, type ApiKeyView } from './adminApi'
import { KeyList } from './components/KeyList'
import { CreateKeyDialog } from './components/CreateKeyDialog'

export function ApiKeyAdminPage() {
  const [keys, setKeys] = useState<ApiKeyView[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [revoking, setRevoking] = useState<ApiKeyView | null>(null)
  const [toast, setToast] = useState<{ type: 'success' | 'error'; message: string } | null>(null)

  const load = useCallback(() => adminApi.listKeys().then(setKeys), [])

  useEffect(() => {
    let active = true
    load()
      .catch((e) => { if (active) setError(e instanceof Error ? e.message : '加载失败') })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [load])

  return (
    <div className="panel">
      <PageHeader
        title="API key 管理"
        description="为脚本与外部系统签发可吊销的 scoped 访问凭据。"
        actions={<button className="primary-button" type="button" onClick={() => setCreating(true)}>创建 API key</button>}
      />
      <KeyList
        keys={keys}
        loading={loading}
        error={error}
        onRetry={() => { setError(null); setLoading(true); load().finally(() => setLoading(false)) }}
        onRevoke={(key) => setRevoking(key)}
      />
      {creating && (
        <CreateKeyDialog
          onCancel={() => setCreating(false)}
          onDone={() => { setCreating(false); setLoading(true); load().finally(() => setLoading(false)); setToast({ type: 'success', message: 'API key 已创建' }) }}
          onNotify={(type, message) => setToast({ type, message })}
        />
      )}
      <ConfirmDialog
        open={revoking !== null}
        title="吊销 API key"
        description={`确定吊销「${revoking?.name ?? ''}」（${revoking?.tokenPrefix ?? ''}…）？吊销后立即失效，无法恢复。`}
        confirmLabel="吊销"
        danger
        onConfirm={() => {
          const target = revoking
          setRevoking(null)
          if (!target) return
          adminApi.revokeKey(target.id)
            .then(() => { setToast({ type: 'success', message: '已吊销' }); return load() })
            .catch((e) => setToast({ type: 'error', message: e instanceof Error ? e.message : '吊销失败' }))
        }}
        onCancel={() => setRevoking(null)}
      />
      {toast && <Toast type={toast.type} message={toast.message} onDismiss={() => setToast(null)} />}
    </div>
  )
}
```

`PageHeader` 的 props 若与上面不符（如无 `description`/`actions`），以现有组件实际签名为准调整（`grep -n "export function PageHeader" web/src/app/PageHeader.tsx` 查看）。

- [ ] **Step 5: 路由与样式**

`routes.tsx` 的 `/admin` 项改为：

```tsx
  {
    path: '/admin', label: '平台管理', englishLabel: 'Administration', icon: ShieldCheck,
    content: <ApiKeyAdminPage />,
  },
```

import 区追加 `import { ApiKeyAdminPage } from '../features/admin/ApiKeyAdminPage'`，`ComingSoonPage` 若再无其他引用则删除其 import。

`styles.css` 追加：

```css
/* API key 管理 */
.key-list { margin: 0; padding: 10px; list-style: none; display: grid; gap: 8px; }
.key-item { padding: 12px; border: 1px solid var(--border); border-radius: var(--radius-control); display: grid; gap: 8px; }
.key-item-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.key-state { font-size: 11px; font-weight: 650; padding: 3px 8px; border-radius: 999px; }
.key-state.active { color: var(--text-primary); background: var(--surface-muted); }
.key-state.revoked { color: var(--text-muted); background: var(--surface-muted); text-decoration: line-through; }
.key-meta { font-size: 11px; color: var(--text-muted); }
.key-scopes { display: flex; flex-wrap: wrap; gap: 4px; }
.key-scopes code { font-size: 11px; padding: 2px 6px; border: 1px solid var(--border); border-radius: var(--radius-control); background: var(--surface-muted); }
.key-item-foot { display: flex; justify-content: flex-end; }
.key-scope-list { display: grid; gap: 6px; }
.key-scope-list label { display: flex; align-items: center; gap: 8px; font-weight: 400; }
.key-token-display { margin: 12px 0; padding: 12px; border: 1px dashed var(--border-strong); border-radius: var(--radius-control); background: var(--surface-muted); word-break: break-all; }
.key-token-display code { font-size: 13px; }
```

- [ ] **Step 6: 前端测试**

`ApiKeyAdminPage.test.tsx`：

```tsx
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, test, vi } from 'vitest'
import { ApiKeyAdminPage } from './ApiKeyAdminPage'

beforeEach(() => vi.restoreAllMocks())

const jsonResponse = (body: unknown, status = 200) =>
  Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))

test('lists keys with prefix and scopes', async () => {
  const fetchMock = vi.fn((input: RequestInfo | URL) => {
    void input
    return jsonResponse([{
      id: 'k1', name: '同步脚本', tokenPrefix: 'vd_abcd12', userId: 'u1',
      scopes: ['qa', 'feedback'], createdAt: '2026-08-16T10:00:00Z', revokedAt: null, lastUsedAt: null,
    }])
  })
  vi.stubGlobal('fetch', fetchMock)

  render(<ApiKeyAdminPage />)
  expect(await screen.findByText('同步脚本')).toBeInTheDocument()
  expect(screen.getByText('vd_abcd12…')).toBeInTheDocument()
  expect(screen.getByText('生效中')).toBeInTheDocument()
})

test('create dialog shows token exactly once after submit', async () => {
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url === '/api/iam/keys' && init?.method === 'POST') {
      return jsonResponse({ id: 'k2', name: '新 key', token: 'vd_NewTokenOnlyOnce', scopes: ['qa'], createdAt: '2026-08-16T10:00:00Z' }, 201)
    }
    return jsonResponse([])
  })
  vi.stubGlobal('fetch', fetchMock)

  render(<ApiKeyAdminPage />)
  fireEvent.click(await screen.findByRole('button', { name: '创建 API key' }))
  fireEvent.change(await screen.findByLabelText(/名称/), { target: { value: '新 key' } })
  fireEvent.click(screen.getByLabelText(/问答/))
  fireEvent.click(screen.getByRole('button', { name: '创建' }))

  expect(await screen.findByText('vd_NewTokenOnlyOnce')).toBeInTheDocument()
})

test('revoking a key asks confirmation then refreshes', async () => {
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url.startsWith('/api/iam/keys/k1') && init?.method === 'DELETE') {
      return Promise.resolve(new Response(null, { status: 204 }))
    }
    return jsonResponse([{
      id: 'k1', name: '同步脚本', tokenPrefix: 'vd_abcd12', userId: 'u1',
      scopes: ['qa'], createdAt: '2026-08-16T10:00:00Z', revokedAt: null, lastUsedAt: null,
    }])
  })
  vi.stubGlobal('fetch', fetchMock)

  render(<ApiKeyAdminPage />)
  fireEvent.click(await screen.findByRole('button', { name: '吊销' }))
  fireEvent.click(await screen.findByRole('button', { name: '吊销', exact: true }))

  await waitFor(() => expect(screen.getByText('已吊销')).toBeInTheDocument())
})
```

注意：第三个用例撤销后列表接口仍 mock 返回 revokedAt:null 的话「已吊销」断言会失败——DELETE 后的 list mock 需返回 revoked 状态（mock 内维护小状态或简化断言为 `toHaveBeenCalled`）。实现时把 mock 改成有状态：首次 list 返回 active，DELETE 后返回 revoked。

- [ ] **Step 7: lint + test + build** — `npm --prefix web run lint && npm --prefix web test && npm --prefix web run build`

- [ ] **Step 8: 更新 docs/architecture.md** — §4 表追加 `/api/iam/keys` 三行与 `/v3/api-docs`；§2 路由表 `/admin` 行页面改为 `ApiKeyAdminPage`

- [ ] **Step 9: 提交**

```bash
git add web/src/features/admin web/src/app/routes.tsx web/src/styles.css docs/architecture.md
git commit -m "feat: add api key management page for admins"
```

---

### Task 8: 全量质量门禁

- [ ] **Step 1:** `./scripts/verify.sh`（四段全绿）
- [ ] **Step 2:** 若有失败修复后重跑
- [ ] **Step 3:** `git status` 确认干净
- [ ] **Step 4:** 提交剩余遗漏文件（如有）

---

## Self-Review

**Spec coverage（对照设计文档 §13 验收标准）：**
1. 创建/查看/吊销 + token 只显示一次 → Task 1/2（后端）/Task 7（前端一次性显示）
2. scope 越权与未映射路径 403 → Task 1（scope 单测）/Task 3（集成）
3. 吊销立即生效 → Task 3 `revokedBearerKeyIsRejected`
4. 429 + Retry-After → Task 5
5. X-Request-Id 响应头 + MDC → Task 4（集成断言由 Task 4 单测覆盖；审计 requestId 补传 DocumentUploadHandler）
6. admin Session 访问 /v3/api-docs → Task 6
7. /admin 全生命周期 → Task 7
8. verify.sh → Task 8

**决策对照：** D1（scope 枚举+路径映射）→ Task 1；D2（600/min 固定窗口）→ Task 5；D3（管理页）→ Task 7；D4（vd_+SHA-256+前缀）→ Task 1；D5（复用 PlatformUserDetails）→ Task 3；D6（限流/requestId 在 shared）→ Task 4/5；D7/D8（springdoc v3.1.0、仅 admin）→ Task 6；D9（V12）→ Task 1；D10（kadmin 仅自己）→ Task 2。

**风险与注意：**
- springdoc v3.1.0 × Boot 4.0.0 × Jackson 3 兼容性是最大不确定点——Task 6 已写降级方案。
- `anyRequest().access(...)` 的 lambda 签名在 Boot 4 可能有 API 差异，Task 3 已注明等价改写。
- shared→iam 的依赖方向陷阱在 Task 5 内已规避（用 `auth.getName()`）。
- knowledge `allowedDependencies` 需允许读 `RequestIds`（Task 4 Step 3 注明以 ArchitectureTest 为准）。
- 限流测试用独立 context（不同 properties）会产生第二个 Spring context，测试时长增加约 1 个容器周期内启动——可接受。
