# Phase 4-b 配置 Profile 版本化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 知识管理员能定义并版本化一套完整的 RAG 配置（chunking / retrieval / generation / prompt / model），发布为不可变 Profile 版本；本轮只定义 + 版本化 + 查询，不接运行时。

**Architecture:** 新建 Spring Modulith 模块 `io.veridex.configuration`。领域模型 = 单一聚合 `ConfigurationProfile`（可变 draft 五维 JSONB）+ `ConfigurationProfileVersion`（发布时冻结的快照，versionNo 递增）。五维固定结构序列化为 `jsonb`。API `/api/configuration/profiles/*`，前端新增 `/configuration` 路由。

**Tech Stack:** Java 21、Spring Boot 4、Spring Modulith、Spring Data JPA、PostgreSQL (Flyway)、Jackson 3 (`tools.jackson`)、React 19 + TypeScript + Vite + Vitest。

## Global Constraints

- 五维 JSONB 结构固定为 `{chunking, retrieval, generation, prompt, model}`，草稿与快照共用；字段名与 design doc §3 完全一致：
  - `chunking.maxChars` / `chunking.overlap`
  - `retrieval.topKPerChannel` / `retrieval.rrfK` / `retrieval.contextTopK` / `retrieval.perDocumentMax` / `retrieval.contextMaxChars`
  - `generation.maxHistoryTurns` / `generation.minEvidenceChars`
  - `prompt.systemTemplate`
  - `model.chatModel` / `model.embeddingModel`
- `configuration` 模块 `allowedDependencies = {"shared", "iam::api"}`（只用 `iam::api` 的 `CurrentActor` / `Role` / `SecurityContextRole`）。
- 权限沿用 `PLATFORM_ADMIN` / `KNOWLEDGE_ADMIN`，controller 内手动校验（不用 `@PreAuthorize`）。
- `IllegalArgumentException` → HTTP 400、`SecurityException` → HTTP 403（已有全局 `KnowledgeApiExceptionHandler`，configuration 模块不新增 advice）。
- 迁移编号 `V9`（当前最新 `V8`），只增不改。
- **本轮不接运行时**：不修改 `StructureFirstChunker` / `HybridSearchServiceImpl` / `GenerationServiceImpl` / `RefusalPolicy` / `QuestionAnsweringServiceImpl` / `DeterministicChatModel` / `DeterministicEmbeddingModel` 的任何硬编码常量。
- TDD：先写失败测试再实现，每个 Task 独立提交。

---

## File Structure

**后端（模块 `io.veridex.configuration`，包结构 `domain` / `application` / `api`）：**

- Create `backend/src/main/resources/db/migration/V9__configuration_profile.sql` — 2 张表
- Create `backend/src/main/java/io/veridex/configuration/domain/ChunkingConfig.java`
- Create `backend/src/main/java/io/veridex/configuration/domain/RetrievalConfig.java`
- Create `backend/src/main/java/io/veridex/configuration/domain/GenerationConfig.java`
- Create `backend/src/main/java/io/veridex/configuration/domain/PromptConfig.java`
- Create `backend/src/main/java/io/veridex/configuration/domain/ModelConfig.java`
- Create `backend/src/main/java/io/veridex/configuration/domain/ProfileConfig.java`
- Create `backend/src/main/java/io/veridex/configuration/domain/ProfileDefaults.java`
- Create `backend/src/main/java/io/veridex/configuration/domain/ConfigurationProfile.java`
- Create `backend/src/main/java/io/veridex/configuration/domain/ConfigurationProfileVersion.java`
- Create `backend/src/main/java/io/veridex/configuration/domain/ConfigurationProfileRepository.java`
- Create `backend/src/main/java/io/veridex/configuration/domain/ConfigurationProfileVersionRepository.java`
- Create `backend/src/main/java/io/veridex/configuration/application/ConfigurationProfileService.java`
- Create `backend/src/main/java/io/veridex/configuration/api/ConfigurationAuthorization.java`
- Create `backend/src/main/java/io/veridex/configuration/api/CreateProfileRequest.java`
- Create `backend/src/main/java/io/veridex/configuration/api/UpdateProfileRequest.java`
- Create `backend/src/main/java/io/veridex/configuration/api/ProfileView.java`
- Create `backend/src/main/java/io/veridex/configuration/api/ProfileDetailView.java`
- Create `backend/src/main/java/io/veridex/configuration/api/VersionView.java`
- Create `backend/src/main/java/io/veridex/configuration/api/VersionDetailView.java`
- Create `backend/src/main/java/io/veridex/configuration/api/PublishResult.java`
- Create `backend/src/main/java/io/veridex/configuration/api/ConfigurationController.java`
- Create `backend/src/main/java/io/veridex/configuration/package-info.java`

**后端测试：**

- Modify `backend/src/test/java/io/veridex/support/DatabaseMigrationTest.java` — 追加 V9 断言
- Create `backend/src/test/java/io/veridex/configuration/ConfigurationProfileServiceTest.java`
- Create `backend/src/test/java/io/veridex/configuration/ConfigurationProfileIntegrationTest.java`

**前端：**

- Create `web/src/features/configuration/configurationApi.ts`
- Create `web/src/features/configuration/ConfigurationPage.tsx`
- Create `web/src/features/configuration/components/ProfileList.tsx`
- Create `web/src/features/configuration/components/ProfileEditor.tsx`
- Modify `web/src/app/routes.tsx` — 新增 `/configuration` 路由
- Modify `web/src/app/App.test.tsx` — 更新 workspaces 断言
- Modify `web/src/styles.css` — 追加配置页样式
- Create `web/src/features/configuration/ConfigurationPage.test.tsx`

---

### Task 1: V9 迁移与迁移测试

**Files:**
- Create: `backend/src/main/resources/db/migration/V9__configuration_profile.sql`
- Modify: `backend/src/test/java/io/veridex/support/DatabaseMigrationTest.java`

**Interfaces:**
- Consumes: 无。
- Produces: 2 张表 `configuration_profile`、`configuration_profile_version`；`DatabaseMigrationTest` 断言 flyway version 列表包含 `"9"`。

- [ ] **Step 1: 写失败测试**

修改 `DatabaseMigrationTest.java` 第 41 行版本断言，追加 `"9"`：

```java
assertThat(versions).contains("1", "2", "3", "4", "5", "6", "7", "8", "9");
```

新增测试方法（复用私有辅助方法 `tableNames` / `assertColumn`）：

```java
@Test
void configurationProfileMigrationCreatesTwoTables() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
        assertThat(tableNames(connection)).contains("configuration_profile", "configuration_profile_version");

        var columns = new HashMap<String, ColumnContract>();
        try (var statement = connection.prepareStatement("""
                SELECT table_name, column_name, data_type, character_maximum_length,
                       is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name IN ('configuration_profile', 'configuration_profile_version')
                """); var rows = statement.executeQuery()) {
            while (rows.next()) {
                columns.put(rows.getString("table_name") + "." + rows.getString("column_name"),
                        new ColumnContract(rows.getString("data_type"),
                                rows.getObject("character_maximum_length", Integer.class),
                                "YES".equals(rows.getString("is_nullable")),
                                rows.getString("column_default")));
            }
        }
        assertColumn(columns, "configuration_profile.name", "character varying", 200, false, null);
        assertColumn(columns, "configuration_profile.draft", "jsonb", null, false, null);
        assertColumn(columns, "configuration_profile_version.version_no", "integer", null, false, null);
        assertColumn(columns, "configuration_profile_version.config", "jsonb", null, false, null);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -pl backend test -Dtest=DatabaseMigrationTest`
Expected: FAIL，`versions` 不含 `"9"`，且新表不存在。

- [ ] **Step 3: 写 V9 迁移**

```sql
-- Phase 4-b 配置 Profile 版本化

CREATE TABLE configuration_profile (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    description TEXT,
    draft JSONB NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE configuration_profile_version (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id UUID NOT NULL REFERENCES configuration_profile(id) ON DELETE CASCADE,
    version_no INTEGER NOT NULL,
    config JSONB NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (profile_id, version_no)
);
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -pl backend test -Dtest=DatabaseMigrationTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/resources/db/migration/V9__configuration_profile.sql backend/src/test/java/io/veridex/support/DatabaseMigrationTest.java
git commit -m "feat: add V9 configuration profile tables"
```

---

### Task 2: 领域模型与仓储

**Files:**
- Create: `backend/src/main/java/io/veridex/configuration/domain/ChunkingConfig.java`
- Create: `backend/src/main/java/io/veridex/configuration/domain/RetrievalConfig.java`
- Create: `backend/src/main/java/io/veridex/configuration/domain/GenerationConfig.java`
- Create: `backend/src/main/java/io/veridex/configuration/domain/PromptConfig.java`
- Create: `backend/src/main/java/io/veridex/configuration/domain/ModelConfig.java`
- Create: `backend/src/main/java/io/veridex/configuration/domain/ProfileConfig.java`
- Create: `backend/src/main/java/io/veridex/configuration/domain/ProfileDefaults.java`
- Create: `backend/src/main/java/io/veridex/configuration/domain/ConfigurationProfile.java`
- Create: `backend/src/main/java/io/veridex/configuration/domain/ConfigurationProfileVersion.java`
- Create: `backend/src/main/java/io/veridex/configuration/domain/ConfigurationProfileRepository.java`
- Create: `backend/src/main/java/io/veridex/configuration/domain/ConfigurationProfileVersionRepository.java`

**Interfaces:**
- Consumes: V9 表（Task 1）。
- Produces: 五维 record + 聚合 record + 默认值 + 2 实体 + 2 仓储。仓储方法签名（后续 Task 沿用）：
  - `ConfigurationProfileRepository.findAllByOrderByCreatedAtDesc() -> List<ConfigurationProfile>`
  - `ConfigurationProfileVersionRepository.findByProfileIdOrderByVersionNoDesc(UUID) -> List<ConfigurationProfileVersion>`、`findTopByProfileIdOrderByVersionNoDesc(UUID) -> Optional<ConfigurationProfileVersion>`、`findByProfileIdAndVersionNo(UUID,int) -> Optional<ConfigurationProfileVersion>`

- [ ] **Step 1: 创建五维 record**

`ChunkingConfig.java`：

```java
package io.veridex.configuration.domain;

public record ChunkingConfig(int maxChars, int overlap) {
}
```

`RetrievalConfig.java`：

```java
package io.veridex.configuration.domain;

public record RetrievalConfig(int topKPerChannel, int rrfK, int contextTopK,
                              int perDocumentMax, int contextMaxChars) {
}
```

`GenerationConfig.java`：

```java
package io.veridex.configuration.domain;

public record GenerationConfig(int maxHistoryTurns, int minEvidenceChars) {
}
```

`PromptConfig.java`：

```java
package io.veridex.configuration.domain;

public record PromptConfig(String systemTemplate) {
}
```

`ModelConfig.java`：

```java
package io.veridex.configuration.domain;

public record ModelConfig(String chatModel, String embeddingModel) {
}
```

`ProfileConfig.java`：

```java
package io.veridex.configuration.domain;

public record ProfileConfig(ChunkingConfig chunking, RetrievalConfig retrieval,
                            GenerationConfig generation, PromptConfig prompt, ModelConfig model) {
}
```

- [ ] **Step 2: 创建 `ProfileDefaults`**

```java
package io.veridex.configuration.domain;

public final class ProfileDefaults {

    public static final String SYSTEM_TEMPLATE =
            "你是企业制度问答助手。只允许使用以下证据回答，不得使用模型通用知识补全。\n";

    private ProfileDefaults() {
    }

    public static ProfileConfig defaults() {
        return new ProfileConfig(
                new ChunkingConfig(2000, 80),
                new RetrievalConfig(30, 60, 6, 3, 4000),
                new GenerationConfig(6, 50),
                new PromptConfig(SYSTEM_TEMPLATE),
                new ModelConfig("deterministic", "deterministic"));
    }
}
```

- [ ] **Step 3: 创建 `ConfigurationProfile` 实体**

```java
package io.veridex.configuration.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "configuration_profile")
public class ConfigurationProfile {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "draft", nullable = false, columnDefinition = "jsonb")
    private String draftJson;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ConfigurationProfile() {
    }

    public ConfigurationProfile(String name, String description, String draftJson, UUID createdBy) {
        this.name = name;
        this.description = description;
        this.draftJson = draftJson;
        this.createdBy = createdBy;
    }

    public void update(String name, String description, String draftJson) {
        this.name = name;
        this.description = description;
        this.draftJson = draftJson;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getDraftJson() { return draftJson; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 4: 创建 `ConfigurationProfileVersion` 实体**

```java
package io.veridex.configuration.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "configuration_profile_version")
public class ConfigurationProfileVersion {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "profile_id", nullable = false)
    private UUID profileId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false, columnDefinition = "jsonb")
    private String configJson;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ConfigurationProfileVersion() {
    }

    public ConfigurationProfileVersion(UUID profileId, int versionNo, String configJson, UUID createdBy) {
        this.profileId = profileId;
        this.versionNo = versionNo;
        this.configJson = configJson;
        this.createdBy = createdBy;
    }

    public UUID getId() { return id; }
    public UUID getProfileId() { return profileId; }
    public int getVersionNo() { return versionNo; }
    public String getConfigJson() { return configJson; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 5: 创建 2 个仓储接口**

`ConfigurationProfileRepository.java`：

```java
package io.veridex.configuration.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface ConfigurationProfileRepository extends CrudRepository<ConfigurationProfile, UUID> {
    List<ConfigurationProfile> findAllByOrderByCreatedAtDesc();
}
```

`ConfigurationProfileVersionRepository.java`：

```java
package io.veridex.configuration.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface ConfigurationProfileVersionRepository extends CrudRepository<ConfigurationProfileVersion, UUID> {
    List<ConfigurationProfileVersion> findByProfileIdOrderByVersionNoDesc(UUID profileId);
    Optional<ConfigurationProfileVersion> findTopByProfileIdOrderByVersionNoDesc(UUID profileId);
    Optional<ConfigurationProfileVersion> findByProfileIdAndVersionNo(UUID profileId, int versionNo);
}
```

- [ ] **Step 6: 编译验证**

Run: `./mvnw -pl backend test-compile`
Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/io/veridex/configuration/domain
git commit -m "feat: add configuration profile domain model and repositories"
```

---

### Task 3: 配置服务与校验

**Files:**
- Create: `backend/src/main/java/io/veridex/configuration/application/ConfigurationProfileService.java`
- Create: `backend/src/test/java/io/veridex/configuration/ConfigurationProfileServiceTest.java`

**Interfaces:**
- Consumes: Task 2 实体与仓储；Jackson 3 `tools.jackson.databind.json.JsonMapper`（Spring Boot 自动配置 bean）。
- Produces: `ConfigurationProfileService` 公开方法（Task 4 控制器沿用）：
  - `ConfigurationProfile create(UUID actorId, String name, String description, ProfileConfig draft)` — draft 为 null 时用 `ProfileDefaults.defaults()`
  - `ConfigurationProfile require(UUID profileId)`
  - `List<ConfigurationProfile> listAll()`
  - `ConfigurationProfile update(UUID profileId, String name, String description, ProfileConfig draft)`
  - `ConfigurationProfileVersion publish(UUID profileId, UUID actorId)`
  - `List<ConfigurationProfileVersion> listVersions(UUID profileId)`
  - `ConfigurationProfileVersion requireVersion(UUID profileId, int versionNo)`
  - `Integer latestVersionNo(UUID profileId)`

- [ ] **Step 1: 写失败测试**

创建 `ConfigurationProfileServiceTest.java`：

```java
package io.veridex.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.veridex.configuration.application.ConfigurationProfileService;
import io.veridex.configuration.domain.ConfigurationProfile;
import io.veridex.configuration.domain.ConfigurationProfileRepository;
import io.veridex.configuration.domain.ConfigurationProfileVersion;
import io.veridex.configuration.domain.ConfigurationProfileVersionRepository;
import io.veridex.configuration.domain.ModelConfig;
import io.veridex.configuration.domain.ProfileConfig;
import io.veridex.configuration.domain.ProfileDefaults;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ConfigurationProfileServiceTest {

    private ConfigurationProfileRepository profiles;
    private ConfigurationProfileVersionRepository versions;
    private ConfigurationProfileService service;

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID PROFILE = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        profiles = mock(ConfigurationProfileRepository.class);
        versions = mock(ConfigurationProfileVersionRepository.class);
        service = new ConfigurationProfileService(profiles, versions, new JsonMapper());
    }

    private static String serialize(ProfileConfig config) {
        try {
            return new JsonMapper().writeValueAsString(config);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void createTrimsNameAndDefaultsMissingDraft() {
        when(profiles.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ConfigurationProfile created = service.create(ACTOR, "  检索基线  ", null, null);
        assertThat(created.getName()).isEqualTo("检索基线");
        assertThat(created.getDraftJson()).contains("\"maxChars\":2000");
    }

    @Test
    void createRejectsBlankName() {
        assertThatThrownBy(() -> service.create(ACTOR, "  ", null, ProfileDefaults.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publishRejectsIncompleteConfig() {
        when(profiles.findById(PROFILE)).thenReturn(Optional.of(new ConfigurationProfile("p", null, "{}", ACTOR)));
        assertThatThrownBy(() -> service.publish(PROFILE, ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunking");
    }

    @Test
    void publishFreezesDraftIntoVersion() {
        ConfigurationProfile profile = new ConfigurationProfile("p", null,
                serialize(ProfileDefaults.defaults()), ACTOR);
        when(profiles.findById(PROFILE)).thenReturn(Optional.of(profile));
        when(versions.findTopByProfileIdOrderByVersionNoDesc(PROFILE)).thenReturn(Optional.empty());
        when(versions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ConfigurationProfileVersion version = service.publish(PROFILE, ACTOR);

        assertThat(version.getVersionNo()).isEqualTo(1);
        assertThat(version.getConfigJson()).contains("\"chatModel\":\"deterministic\"");
    }

    @Test
    void publishIncrementsVersionNo() {
        ConfigurationProfile profile = new ConfigurationProfile("p", null,
                serialize(ProfileDefaults.defaults()), ACTOR);
        when(profiles.findById(PROFILE)).thenReturn(Optional.of(profile));
        when(versions.findTopByProfileIdOrderByVersionNoDesc(PROFILE)).thenReturn(Optional.of(
                new ConfigurationProfileVersion(PROFILE, 3, "{}", ACTOR)));
        when(versions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ConfigurationProfileVersion version = service.publish(PROFILE, ACTOR);
        assertThat(version.getVersionNo()).isEqualTo(4);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -pl backend test -Dtest=ConfigurationProfileServiceTest`
Expected: FAIL（`ConfigurationProfileService` 不存在，编译失败）。

- [ ] **Step 3: 实现服务**

创建 `ConfigurationProfileService.java`：

```java
package io.veridex.configuration.application;

import io.veridex.configuration.domain.ConfigurationProfile;
import io.veridex.configuration.domain.ConfigurationProfileRepository;
import io.veridex.configuration.domain.ConfigurationProfileVersion;
import io.veridex.configuration.domain.ConfigurationProfileVersionRepository;
import io.veridex.configuration.domain.ProfileConfig;
import io.veridex.configuration.domain.ProfileDefaults;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional
public class ConfigurationProfileService {

    private final ConfigurationProfileRepository profiles;
    private final ConfigurationProfileVersionRepository versions;
    private final JsonMapper jsonMapper;

    public ConfigurationProfileService(ConfigurationProfileRepository profiles,
                                       ConfigurationProfileVersionRepository versions,
                                       JsonMapper jsonMapper) {
        this.profiles = profiles;
        this.versions = versions;
        this.jsonMapper = jsonMapper;
    }

    public ConfigurationProfile create(UUID actorId, String name, String description, ProfileConfig draft) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        ProfileConfig effective = draft == null ? ProfileDefaults.defaults() : draft;
        return profiles.save(new ConfigurationProfile(trimmed, description, serialize(effective), actorId));
    }

    public ConfigurationProfile require(UUID profileId) {
        return profiles.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("unknown profile " + profileId));
    }

    public List<ConfigurationProfile> listAll() {
        return profiles.findAllByOrderByCreatedAtDesc();
    }

    public ConfigurationProfile update(UUID profileId, String name, String description, ProfileConfig draft) {
        ConfigurationProfile existing = require(profileId);
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        validateConfig(draft);
        existing.update(trimmed, description, serialize(draft));
        return profiles.save(existing);
    }

    public ConfigurationProfileVersion publish(UUID profileId, UUID actorId) {
        ConfigurationProfile existing = require(profileId);
        ProfileConfig config = deserialize(existing.getDraftJson());
        validateConfig(config);
        int nextVersionNo = versions.findTopByProfileIdOrderByVersionNoDesc(profileId)
                .map(v -> v.getVersionNo() + 1)
                .orElse(1);
        return versions.save(new ConfigurationProfileVersion(profileId, nextVersionNo,
                serialize(config), actorId));
    }

    public List<ConfigurationProfileVersion> listVersions(UUID profileId) {
        require(profileId);
        return versions.findByProfileIdOrderByVersionNoDesc(profileId);
    }

    public ConfigurationProfileVersion requireVersion(UUID profileId, int versionNo) {
        return versions.findByProfileIdAndVersionNo(profileId, versionNo)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown version " + versionNo + " for profile " + profileId));
    }

    public Integer latestVersionNo(UUID profileId) {
        return versions.findTopByProfileIdOrderByVersionNoDesc(profileId)
                .map(ConfigurationProfileVersion::getVersionNo)
                .orElse(null);
    }

    private void validateConfig(ProfileConfig config) {
        if (config == null || config.chunking() == null) {
            throw new IllegalArgumentException("config.chunking is required");
        }
        if (config.retrieval() == null) {
            throw new IllegalArgumentException("config.retrieval is required");
        }
        if (config.generation() == null) {
            throw new IllegalArgumentException("config.generation is required");
        }
        if (config.prompt() == null || config.prompt().systemTemplate() == null
                || config.prompt().systemTemplate().isBlank()) {
            throw new IllegalArgumentException("config.prompt.systemTemplate is required");
        }
        if (config.model() == null || config.model().chatModel() == null
                || config.model().chatModel().isBlank()
                || config.model().embeddingModel() == null
                || config.model().embeddingModel().isBlank()) {
            throw new IllegalArgumentException("config.model.chatModel and embeddingModel are required");
        }
    }

    private String serialize(ProfileConfig config) {
        try {
            return jsonMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize profile config", e);
        }
    }

    private ProfileConfig deserialize(String json) {
        try {
            return jsonMapper.readValue(json, ProfileConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize profile config", e);
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -pl backend test -Dtest=ConfigurationProfileServiceTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/veridex/configuration/application/ConfigurationProfileService.java backend/src/test/java/io/veridex/configuration/ConfigurationProfileServiceTest.java
git commit -m "feat: add configuration profile service with publish freeze semantics"
```

---

### Task 4: REST API 与模块边界

**Files:**
- Create: `backend/src/main/java/io/veridex/configuration/package-info.java`
- Create: `backend/src/main/java/io/veridex/configuration/api/ConfigurationAuthorization.java`
- Create: `backend/src/main/java/io/veridex/configuration/api/CreateProfileRequest.java`
- Create: `backend/src/main/java/io/veridex/configuration/api/UpdateProfileRequest.java`
- Create: `backend/src/main/java/io/veridex/configuration/api/ProfileView.java`
- Create: `backend/src/main/java/io/veridex/configuration/api/ProfileDetailView.java`
- Create: `backend/src/main/java/io/veridex/configuration/api/VersionView.java`
- Create: `backend/src/main/java/io/veridex/configuration/api/VersionDetailView.java`
- Create: `backend/src/main/java/io/veridex/configuration/api/PublishResult.java`
- Create: `backend/src/main/java/io/veridex/configuration/api/ConfigurationController.java`

**Interfaces:**
- Consumes: Task 3 服务；`iam.api` 的 `CurrentActor` / `Role` / `SecurityContextRole`；Jackson 3 `JsonMapper`。
- Produces: `/api/configuration/profiles` 全套接口与 DTO 字段名（前端 Task 6/7 严格沿用）：
  - `ProfileView { id, name, description, versionCount, latestVersionNo }`
  - `ProfileDetailView { id, name, description, draft }`，`draft` 为 `ProfileConfig`（Jackson 自动展开为五维对象）
  - `VersionView { id, versionNo, createdAt }`
  - `VersionDetailView { id, versionNo, createdAt, config }`，`config` 为 `ProfileConfig`
  - `PublishResult { versionId, versionNo }`

- [ ] **Step 1: 创建 `package-info`**

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Configuration",
        allowedDependencies = {"shared", "iam::api"}
)
package io.veridex.configuration;
```

- [ ] **Step 2: 创建 `ConfigurationAuthorization`**

```java
package io.veridex.configuration.api;

import io.veridex.iam.api.Role;
import io.veridex.iam.api.SecurityContextRole;
import org.springframework.stereotype.Component;

@Component
public class ConfigurationAuthorization {

    public boolean isAdmin() {
        Role role = SecurityContextRole.currentRole();
        return role == Role.PLATFORM_ADMIN || role == Role.KNOWLEDGE_ADMIN;
    }
}
```

- [ ] **Step 3: 创建请求与视图 DTO**

`CreateProfileRequest.java`：

```java
package io.veridex.configuration.api;

import io.veridex.configuration.domain.ProfileConfig;

public record CreateProfileRequest(String name, String description, ProfileConfig draft) {
}
```

`UpdateProfileRequest.java`：

```java
package io.veridex.configuration.api;

import io.veridex.configuration.domain.ProfileConfig;

public record UpdateProfileRequest(String name, String description, ProfileConfig draft) {
}
```

`ProfileView.java`：

```java
package io.veridex.configuration.api;

import java.util.UUID;

public record ProfileView(UUID id, String name, String description, int versionCount, Integer latestVersionNo) {
}
```

`ProfileDetailView.java`：

```java
package io.veridex.configuration.api;

import io.veridex.configuration.domain.ProfileConfig;
import java.util.UUID;

public record ProfileDetailView(UUID id, String name, String description, ProfileConfig draft) {
}
```

`VersionView.java`：

```java
package io.veridex.configuration.api;

import java.util.UUID;

public record VersionView(UUID id, int versionNo, String createdAt) {
}
```

`VersionDetailView.java`：

```java
package io.veridex.configuration.api;

import io.veridex.configuration.domain.ProfileConfig;
import java.util.UUID;

public record VersionDetailView(UUID id, int versionNo, String createdAt, ProfileConfig config) {
}
```

`PublishResult.java`：

```java
package io.veridex.configuration.api;

import java.util.UUID;

public record PublishResult(UUID versionId, int versionNo) {
}
```

- [ ] **Step 4: 创建 `ConfigurationController`**

```java
package io.veridex.configuration.api;

import io.veridex.configuration.application.ConfigurationProfileService;
import io.veridex.configuration.domain.ConfigurationProfile;
import io.veridex.configuration.domain.ConfigurationProfileVersion;
import io.veridex.configuration.domain.ProfileConfig;
import io.veridex.iam.api.CurrentActor;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/configuration/profiles")
public class ConfigurationController {

    private final ConfigurationProfileService service;
    private final ConfigurationAuthorization authorization;
    private final JsonMapper jsonMapper;

    public ConfigurationController(ConfigurationProfileService service,
                                   ConfigurationAuthorization authorization,
                                   JsonMapper jsonMapper) {
        this.service = service;
        this.authorization = authorization;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping
    public ResponseEntity<ProfileDetailView> create(@RequestBody CreateProfileRequest body) {
        requireAdmin();
        ConfigurationProfile profile = service.create(CurrentActor.id(), body.name(), body.description(), body.draft());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDetail(profile));
    }

    @GetMapping
    public List<ProfileView> list() {
        requireAdmin();
        return service.listAll().stream().map(this::toView).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProfileDetailView> get(@PathVariable UUID id) {
        requireAdmin();
        return ResponseEntity.ok(toDetail(service.require(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProfileDetailView> update(@PathVariable UUID id, @RequestBody UpdateProfileRequest body) {
        requireAdmin();
        ConfigurationProfile profile = service.update(id, body.name(), body.description(), body.draft());
        return ResponseEntity.ok(toDetail(profile));
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<PublishResult> publish(@PathVariable UUID id) {
        requireAdmin();
        ConfigurationProfileVersion version = service.publish(id, CurrentActor.id());
        return ResponseEntity.ok(new PublishResult(version.getId(), version.getVersionNo()));
    }

    @GetMapping("/{id}/versions")
    public List<VersionView> listVersions(@PathVariable UUID id) {
        requireAdmin();
        return service.listVersions(id).stream().map(this::toVersionView).toList();
    }

    @GetMapping("/{id}/versions/{versionNo}")
    public ResponseEntity<VersionDetailView> version(@PathVariable UUID id, @PathVariable int versionNo) {
        requireAdmin();
        ConfigurationProfileVersion version = service.requireVersion(id, versionNo);
        return ResponseEntity.ok(new VersionDetailView(version.getId(), version.getVersionNo(),
                version.getCreatedAt().toString(), deserialize(version.getConfigJson())));
    }

    private void requireAdmin() {
        if (!authorization.isAdmin()) {
            throw new SecurityException("configuration management requires admin role");
        }
    }

    private ProfileView toView(ConfigurationProfile p) {
        return new ProfileView(p.getId(), p.getName(), p.getDescription(),
                (int) service.listVersions(p.getId()).size(), service.latestVersionNo(p.getId()));
    }

    private ProfileDetailView toDetail(ConfigurationProfile p) {
        return new ProfileDetailView(p.getId(), p.getName(), p.getDescription(), deserialize(p.getDraftJson()));
    }

    private VersionView toVersionView(ConfigurationProfileVersion v) {
        return new VersionView(v.getId(), v.getVersionNo(), v.getCreatedAt().toString());
    }

    private ProfileConfig deserialize(String json) {
        try {
            return jsonMapper.readValue(json, ProfileConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize profile config", e);
        }
    }
}
```

- [ ] **Step 5: 编译验证**

Run: `./mvnw -pl backend test-compile`
Expected: PASS。

- [ ] **Step 6: 运行 ArchitectureTest**

Run: `./mvnw -pl backend test -Dtest=ArchitectureTest`
Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/io/veridex/configuration
git commit -m "feat: expose configuration profile REST API"
```

---

### Task 5: 发布语义真库集成测试

**Files:**
- Create: `backend/src/test/java/io/veridex/configuration/ConfigurationProfileIntegrationTest.java`

**Interfaces:**
- Consumes: Task 3 服务；Task 1 V9 迁移；`io.veridex.support.PostgresIntegrationTest`。
- Produces: 验证「发布后改 draft 不影响已发布版本」「versionNo 递增」「五维不完整被拒」。

- [ ] **Step 1: 写失败测试**

```java
package io.veridex.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.veridex.configuration.application.ConfigurationProfileService;
import io.veridex.configuration.domain.ChunkingConfig;
import io.veridex.configuration.domain.ConfigurationProfile;
import io.veridex.configuration.domain.ConfigurationProfileVersion;
import io.veridex.configuration.domain.GenerationConfig;
import io.veridex.configuration.domain.ModelConfig;
import io.veridex.configuration.domain.ProfileConfig;
import io.veridex.configuration.domain.ProfileDefaults;
import io.veridex.configuration.domain.PromptConfig;
import io.veridex.configuration.domain.RetrievalConfig;
import io.veridex.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ConfigurationProfileIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    ConfigurationProfileService service;

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static ProfileConfig config(int maxChars) {
        return new ProfileConfig(
                new ChunkingConfig(maxChars, 80),
                new RetrievalConfig(30, 60, 6, 3, 4000),
                new GenerationConfig(6, 50),
                new PromptConfig(ProfileDefaults.SYSTEM_TEMPLATE),
                new ModelConfig("deterministic", "deterministic"));
    }

    @Test
    void publishFreezesDraftAndIncrementsVersionNo() {
        ConfigurationProfile profile = service.create(ACTOR, "检索基线", "描述", config(2000));
        ConfigurationProfileVersion v1 = service.publish(profile.getId(), ACTOR);
        assertThat(v1.getVersionNo()).isEqualTo(1);

        service.update(profile.getId(), "检索基线", "描述", config(3000));
        ConfigurationProfileVersion v2 = service.publish(profile.getId(), ACTOR);
        assertThat(v2.getVersionNo()).isEqualTo(2);

        ConfigurationProfileVersion v1Loaded = service.requireVersion(profile.getId(), 1);
        assertThat(v1Loaded.getConfigJson()).contains("\"maxChars\":2000");
        ConfigurationProfileVersion v2Loaded = service.requireVersion(profile.getId(), 2);
        assertThat(v2Loaded.getConfigJson()).contains("\"maxChars\":3000");
    }

    @Test
    void publishRejectsIncompleteConfig() {
        ConfigurationProfile profile = service.create(ACTOR, "空配置", null,
                new ProfileConfig(null, null, null, null, null));
        assertThatThrownBy(() -> service.publish(profile.getId(), ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunking");
    }
}
```

- [ ] **Step 2: 运行测试确认通过**

Run: `./mvnw -pl backend test -Dtest=ConfigurationProfileIntegrationTest`
Expected: PASS（需要 Docker）。

- [ ] **Step 3: 提交**

```bash
git add backend/src/test/java/io/veridex/configuration/ConfigurationProfileIntegrationTest.java
git commit -m "test: cover configuration profile publish freeze semantics against Postgres"
```

---

### Task 6: 前端 API 客户端

**Files:**
- Create: `web/src/features/configuration/configurationApi.ts`

**Interfaces:**
- Consumes: Task 4 DTO 字段名。
- Produces: `configurationApi` 对象。类型与字段与后端完全一致。

- [ ] **Step 1: 创建 `configurationApi.ts`**

```ts
export type ChunkingConfig = { maxChars: number; overlap: number }
export type RetrievalConfig = {
  topKPerChannel: number
  rrfK: number
  contextTopK: number
  perDocumentMax: number
  contextMaxChars: number
}
export type GenerationConfig = { maxHistoryTurns: number; minEvidenceChars: number }
export type PromptConfig = { systemTemplate: string }
export type ModelConfig = { chatModel: string; embeddingModel: string }

export type ProfileConfig = {
  chunking: ChunkingConfig
  retrieval: RetrievalConfig
  generation: GenerationConfig
  prompt: PromptConfig
  model: ModelConfig
}

export type ProfileView = {
  id: string
  name: string
  description: string | null
  versionCount: number
  latestVersionNo: number | null
}

export type ProfileDetail = {
  id: string
  name: string
  description: string | null
  draft: ProfileConfig
}

export type VersionView = { id: string; versionNo: number; createdAt: string }

export type VersionDetail = {
  id: string
  versionNo: number
  createdAt: string
  config: ProfileConfig
}

export type PublishResult = { versionId: string; versionNo: number }

const json = async <T>(response: Response): Promise<T> => {
  if (!response.ok) {
    const message = await response.text().catch(() => '')
    throw new Error(message || `请求失败 (${response.status})`)
  }
  return (response.status === 204 ? null : await response.json()) as T
}

const send = (method: string, payload: unknown): RequestInit => ({
  method,
  credentials: 'include',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(payload),
})

export const configurationApi = {
  list: (): Promise<ProfileView[]> =>
    fetch('/api/configuration/profiles', { credentials: 'include' }).then((r) => json<ProfileView[]>(r)),
  get: (id: string): Promise<ProfileDetail> =>
    fetch(`/api/configuration/profiles/${id}`, { credentials: 'include' }).then((r) => json<ProfileDetail>(r)),
  create: (name: string, description: string | null, draft: ProfileConfig): Promise<ProfileDetail> =>
    fetch('/api/configuration/profiles', send('POST', { name, description, draft })).then((r) => json<ProfileDetail>(r)),
  update: (id: string, name: string, description: string | null, draft: ProfileConfig): Promise<ProfileDetail> =>
    fetch(`/api/configuration/profiles/${id}`, send('PUT', { name, description, draft })).then((r) => json<ProfileDetail>(r)),
  publish: (id: string): Promise<PublishResult> =>
    fetch(`/api/configuration/profiles/${id}/publish`, { method: 'POST', credentials: 'include' }).then((r) => json<PublishResult>(r)),
  versions: (id: string): Promise<VersionView[]> =>
    fetch(`/api/configuration/profiles/${id}/versions`, { credentials: 'include' }).then((r) => json<VersionView[]>(r)),
  version: (id: string, versionNo: number): Promise<VersionDetail> =>
    fetch(`/api/configuration/profiles/${id}/versions/${versionNo}`, { credentials: 'include' }).then((r) => json<VersionDetail>(r)),
}
```

- [ ] **Step 2: 类型检查**

Run: `npm --prefix web run build`
Expected: PASS。

- [ ] **Step 3: 提交**

```bash
git add web/src/features/configuration/configurationApi.ts
git commit -m "feat: add configuration profile API client"
```

---

### Task 7: 前端配置管理页

**Files:**
- Create: `web/src/features/configuration/components/ProfileEditor.tsx`
- Create: `web/src/features/configuration/components/ProfileList.tsx`
- Create: `web/src/features/configuration/ConfigurationPage.tsx`
- Modify: `web/src/styles.css`

**Interfaces:**
- Consumes: Task 6 的 `configurationApi` 类型；`PageHeader`、`Toast` 等既有组件。
- Produces: `ConfigurationPage`，供 Task 8 挂载。

- [ ] **Step 1: 创建 `ProfileEditor`**

```tsx
import { useState } from 'react'
import { configurationApi, type ProfileConfig } from '../configurationApi'

export const defaultConfig = (): ProfileConfig => ({
  chunking: { maxChars: 2000, overlap: 80 },
  retrieval: { topKPerChannel: 30, rrfK: 60, contextTopK: 6, perDocumentMax: 3, contextMaxChars: 4000 },
  generation: { maxHistoryTurns: 6, minEvidenceChars: 50 },
  prompt: { systemTemplate: '你是企业制度问答助手。只允许使用以下证据回答，不得使用模型通用知识补全。\n' },
  model: { chatModel: 'deterministic', embeddingModel: 'deterministic' },
})

export function ProfileEditor({ profileId, name, description, initial, onSaved, onNotify }: {
  profileId: string
  name: string
  description: string | null
  initial: ProfileConfig
  onSaved: () => void
  onNotify: (type: 'success' | 'error', message: string) => void
}) {
  const [config, setConfig] = useState<ProfileConfig>(initial)
  const [saving, setSaving] = useState(false)

  const patch = (patch: Partial<ProfileConfig>) => setConfig((c) => ({ ...c, ...patch }))

  const save = async () => {
    setSaving(true)
    try {
      await configurationApi.update(profileId, name, description, config)
      onNotify('success', '草稿已保存')
      onSaved()
    } catch (e) {
      onNotify('error', e instanceof Error ? e.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="case-editor panel">
      <div className="panel-header"><h2>五维配置草稿</h2></div>
      <div className="case-editor-body">
        <fieldset className="config-section">
          <legend>Chunking</legend>
          <label>maxChars<input type="number" value={config.chunking.maxChars} onChange={(e) => patch({ chunking: { ...config.chunking, maxChars: Number(e.target.value) } })} /></label>
          <label>overlap<input type="number" value={config.chunking.overlap} onChange={(e) => patch({ chunking: { ...config.chunking, overlap: Number(e.target.value) } })} /></label>
        </fieldset>
        <fieldset className="config-section">
          <legend>Retrieval</legend>
          <label>topKPerChannel<input type="number" value={config.retrieval.topKPerChannel} onChange={(e) => patch({ retrieval: { ...config.retrieval, topKPerChannel: Number(e.target.value) } })} /></label>
          <label>rrfK<input type="number" value={config.retrieval.rrfK} onChange={(e) => patch({ retrieval: { ...config.retrieval, rrfK: Number(e.target.value) } })} /></label>
          <label>contextTopK<input type="number" value={config.retrieval.contextTopK} onChange={(e) => patch({ retrieval: { ...config.retrieval, contextTopK: Number(e.target.value) } })} /></label>
          <label>perDocumentMax<input type="number" value={config.retrieval.perDocumentMax} onChange={(e) => patch({ retrieval: { ...config.retrieval, perDocumentMax: Number(e.target.value) } })} /></label>
          <label>contextMaxChars<input type="number" value={config.retrieval.contextMaxChars} onChange={(e) => patch({ retrieval: { ...config.retrieval, contextMaxChars: Number(e.target.value) } })} /></label>
        </fieldset>
        <fieldset className="config-section">
          <legend>Generation</legend>
          <label>maxHistoryTurns<input type="number" value={config.generation.maxHistoryTurns} onChange={(e) => patch({ generation: { ...config.generation, maxHistoryTurns: Number(e.target.value) } })} /></label>
          <label>minEvidenceChars<input type="number" value={config.generation.minEvidenceChars} onChange={(e) => patch({ generation: { ...config.generation, minEvidenceChars: Number(e.target.value) } })} /></label>
        </fieldset>
        <fieldset className="config-section">
          <legend>Prompt</legend>
          <label>systemTemplate<textarea value={config.prompt.systemTemplate} onChange={(e) => patch({ prompt: { systemTemplate: e.target.value } })} rows={4} /></label>
        </fieldset>
        <fieldset className="config-section">
          <legend>Model</legend>
          <label>chatModel<input value={config.model.chatModel} onChange={(e) => patch({ model: { ...config.model, chatModel: e.target.value } })} /></label>
          <label>embeddingModel<input value={config.model.embeddingModel} onChange={(e) => patch({ model: { ...config.model, embeddingModel: e.target.value } })} /></label>
        </fieldset>
        <div className="case-editor-actions">
          <button className="primary-button" type="button" onClick={() => void save()} disabled={saving}>{saving ? '保存中' : '保存草稿'}</button>
        </div>
      </div>
    </div>
  )
}
```

- [ ] **Step 2: 创建 `ProfileList`**

```tsx
import { useState } from 'react'
import type { ProfileView } from '../configurationApi'

export function ProfileList({ profiles, selectedId, loading, error, creating, onSelect, onRetry, onCreate }: {
  profiles: ProfileView[]
  selectedId: string | null
  loading: boolean
  error: string | null
  creating: boolean
  onSelect: (p: ProfileView) => void
  onRetry: () => void
  onCreate: (name: string) => Promise<void>
}) {
  const [name, setName] = useState('')

  const submit = async () => {
    if (!name.trim()) return
    await onCreate(name.trim())
    setName('')
  }

  return (
    <div className="panel knowledge-base-panel">
      <div className="panel-header"><h2>配置</h2></div>
      <form className="inline-create-form" onSubmit={(e) => { e.preventDefault(); void submit() }}>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="配置名称" aria-label="配置名称" />
        <button className="primary-button" type="submit" disabled={creating || !name.trim()}>{creating ? '创建中' : '新建配置'}</button>
      </form>
      {loading && <div role="status" className="loading-copy" style={{ padding: '14px' }}>正在加载配置</div>}
      {error && <div className="state-block compact"><p role="alert">{error}</p><button className="secondary-button" type="button" onClick={onRetry}>重试</button></div>}
      <ul className="knowledge-base-list">
        {profiles.map((p) => (
          <li key={p.id}><button className={p.id === selectedId ? 'active' : ''} type="button" onClick={() => onSelect(p)}><span className="kb-icon">C</span><span><strong>{p.name}</strong><small>v{p.latestVersionNo ?? 0} · {p.versionCount} 版本</small></span></button></li>
        ))}
        {!loading && !error && profiles.length === 0 && <li className="state-block compact"><h3>创建第一个配置</h3><p>配置用于版本化 RAG 参数，供评测对比引用。</p></li>}
      </ul>
    </div>
  )
}
```

- [ ] **Step 3: 创建 `ConfigurationPage`**

```tsx
import { FolderOpen, RocketLaunch } from '@phosphor-icons/react'
import { useCallback, useEffect, useState } from 'react'
import { PageHeader } from '../../app/PageHeader'
import { Toast } from '../../components/Toast'
import { configurationApi, type ProfileConfig, type ProfileView, type VersionDetail, type VersionView } from './configurationApi'
import { ProfileEditor, defaultConfig } from './components/ProfileEditor'
import { ProfileList } from './components/ProfileList'

export function ConfigurationPage() {
  const [profiles, setProfiles] = useState<ProfileView[]>([])
  const [selected, setSelected] = useState<ProfileView | null>(null)
  const [draft, setDraft] = useState<ProfileConfig | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [publishing, setPublishing] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)
  const [versions, setVersions] = useState<VersionView[]>([])
  const [versionDetail, setVersionDetail] = useState<VersionDetail | null>(null)
  const [toast, setToast] = useState<{ type: 'success' | 'error'; message: string } | null>(null)

  const loadProfiles = useCallback(() => {
    return configurationApi.list().then((next) => {
      setProfiles(next)
      setSelected((current) => next.find((p) => p.id === current?.id) ?? next[0] ?? null)
    })
  }, [])

  useEffect(() => {
    let active = true
    configurationApi.list()
      .then((next) => { if (active) { setProfiles(next); setSelected(next[0] ?? null) } })
      .catch((e) => { if (active) setError(e instanceof Error ? e.message : '配置加载失败') })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [])

  useEffect(() => {
    if (!selected) { setDraft(null); return }
    configurationApi.get(selected.id).then((d) => setDraft(d.draft)).catch(() => setDraft(null))
  }, [selected, refreshKey])

  useEffect(() => {
    if (!selected) return
    configurationApi.versions(selected.id).then(setVersions).catch(() => setVersions([]))
  }, [selected, refreshKey])

  const createProfile = async (name: string) => {
    setCreating(true)
    try {
      await configurationApi.create(name, null, defaultConfig())
      await loadProfiles()
      setRefreshKey((k) => k + 1)
    } catch (e) {
      setToast({ type: 'error', message: e instanceof Error ? e.message : '创建失败' })
    } finally {
      setCreating(false)
    }
  }

  const publish = async () => {
    if (!selected) return
    setPublishing(true)
    setToast(null)
    try {
      const result = await configurationApi.publish(selected.id)
      setToast({ type: 'success', message: `已发布版本 v${result.versionNo}` })
      setRefreshKey((k) => k + 1)
    } catch (e) {
      setToast({ type: 'error', message: e instanceof Error ? e.message : '发布失败' })
    } finally {
      setPublishing(false)
    }
  }

  const openVersion = async (versionNo: number) => {
    if (!selected) return
    try {
      setVersionDetail(await configurationApi.version(selected.id, versionNo))
    } catch (e) {
      setToast({ type: 'error', message: e instanceof Error ? e.message : '版本加载失败' })
    }
  }

  return (
    <section className="workspace-page configuration-page">
      <PageHeader title="配置版本" description="版本化 RAG 配置，供评测对比引用。" meta={`${profiles.length} 个配置`} />
      <div className="knowledge-layout">
        <ProfileList profiles={profiles} selectedId={selected?.id ?? null} loading={loading} error={error} creating={creating} onSelect={setSelected} onRetry={() => void loadProfiles()} onCreate={createProfile} />
        <div className="knowledge-workspace">
          {selected && draft ? (
            <>
              <header className="knowledge-workspace-header"><div><p className="section-kicker">当前配置</p><h2>{selected.name}</h2><p>{selected.description || '管理 RAG 五维配置并发布不可变版本。'}</p></div><div className="workspace-header-actions"><button className="primary-button" type="button" onClick={() => void publish()} disabled={publishing}><RocketLaunch size={18} aria-hidden="true" />{publishing ? '正在发布' : '发布版本'}</button></div></header>
              <ProfileEditor key={selected.id + ':' + refreshKey} profileId={selected.id} name={selected.name} description={selected.description} initial={draft} onSaved={() => setRefreshKey((k) => k + 1)} onNotify={(type, message) => setToast({ type, message })} />
              <div className="panel version-panel">
                <div className="panel-header"><h2>版本历史</h2></div>
                <ul className="version-list">
                  {versions.map((v) => <li key={v.id}><button type="button" onClick={() => void openVersion(v.versionNo)}><strong>v{v.versionNo}</strong><small>{v.createdAt}</small></button></li>)}
                  {versions.length === 0 && <li className="case-list-empty">尚未发布版本</li>}
                </ul>
              </div>
              {versionDetail && (
                <div className="panel version-detail">
                  <div className="panel-header"><h2>版本 v{versionDetail.versionNo}（已冻结）</h2><button className="text-button" type="button" onClick={() => setVersionDetail(null)}>关闭</button></div>
                  <pre className="config-preview">{JSON.stringify(versionDetail.config, null, 2)}</pre>
                </div>
              )}
            </>
          ) : (
            <div className="panel state-block workspace-empty"><FolderOpen size={34} aria-hidden="true" /><h2>选择一个配置</h2><p>从左侧选择配置，或创建第一个配置开始管理。</p></div>
          )}
        </div>
      </div>
      {toast && <Toast type={toast.type} message={toast.message} onDismiss={() => setToast(null)} />}
    </section>
  )
}
```

- [ ] **Step 4: 追加样式**

在 `styles.css` 末尾（`@media (prefers-reduced-motion: reduce)` 之前）追加：

```css
/* Configuration */
.config-section { border: 1px solid var(--border); border-radius: var(--radius-control); padding: 12px; display: grid; gap: 10px; }
.config-section legend { padding: 0 6px; color: var(--text-muted); font-size: 11px; font-weight: 650; }
.config-section label { display: grid; gap: 5px; font-size: 11px; color: var(--text-secondary); }
.config-section input, .config-section textarea { width: 100%; padding: 8px 10px; border: 1px solid var(--border-strong); border-radius: var(--radius-control); background: #f8fafa; color: var(--text-primary); font: inherit; }
.config-section textarea { resize: vertical; }
.config-preview { margin: 0; padding: 14px; font: 12px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace; background: var(--surface-muted); overflow-x: auto; white-space: pre-wrap; }
```

- [ ] **Step 5: lint + build**

Run: `npm --prefix web run lint && npm --prefix web run build`
Expected: 0 errors（`QaPage` 既有 1 warning 可忽略），build 通过。

- [ ] **Step 6: 提交**

```bash
git add web/src/features/configuration web/src/styles.css
git commit -m "feat: add configuration profile management page"
```

---

### Task 8: 路由替换与前端测试

**Files:**
- Modify: `web/src/app/routes.tsx`
- Modify: `web/src/app/App.test.tsx`
- Create: `web/src/features/configuration/ConfigurationPage.test.tsx`

**Interfaces:**
- Consumes: Task 7 的 `ConfigurationPage`。
- Produces: `/configuration` 路由渲染管理页；`App.test.tsx` 与 `ConfigurationPage.test.tsx` 全绿。

- [ ] **Step 1: 新增路由**

修改 `routes.tsx`：import 追加 `SlidersHorizontal`，新增 `ConfigurationPage` import，并在 `workspaceRoutes` 中插入 `/configuration`（放在 `/evaluation` 之后、`/admin` 之前）：

```tsx
import { ChatCircleText, Database, Gauge, ShieldCheck, SlidersHorizontal } from '@phosphor-icons/react'
// ...
import { EvaluationPage } from '../features/evaluation/EvaluationPage'
import { ConfigurationPage } from '../features/configuration/ConfigurationPage'
import { ComingSoonPage } from './ComingSoonPage'
```

```tsx
  {
    path: '/configuration', label: '配置版本', englishLabel: 'Configuration', icon: SlidersHorizontal,
    content: <ConfigurationPage />,
  },
```

- [ ] **Step 2: 更新 `App.test.tsx`**

把 `workspaces` 数组加入 `['/configuration', '配置版本']`（在 `/evaluation` 后）：

```tsx
const workspaces = [
  ['/workbench', '员工问答'],
  ['/knowledge', '知识管理'],
  ['/evaluation', '质量评测'],
  ['/configuration', '配置版本'],
  ['/admin', '平台管理'],
] as const
```

注意：`workspaces.filter(([p]) => p !== '/knowledge' && p !== '/workbench')` 的 `test.each` 已在 P4-a 被拆开，`/configuration` 会落到「renders the administration coming soon page for /admin」之外——由于该测试块已被 P4-a 改写，`/configuration` 需要单独加一个断言（或验证 navigation links 已覆盖）。在 `App.test.tsx` 里，`navigation links` 测试用 `for (const [path, title] of workspaces)` 遍历，新增数组项后会自动断言 `/configuration` 的 link 存在；额外补一条：

```tsx
  test('renders the configuration workspace page for /configuration', async () => {
    render(
      <MemoryRouter initialEntries={['/configuration']}>
        <App />
      </MemoryRouter>,
    )

    expect(await screen.findByRole('heading', { name: '配置版本', level: 1 })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: '配置', level: 2 })).toBeInTheDocument()
  })
```

- [ ] **Step 3: 写失败测试 `ConfigurationPage.test.tsx`**

```tsx
import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, expect, test, vi } from 'vitest'
import { ConfigurationPage } from './ConfigurationPage'

beforeEach(() => vi.restoreAllMocks())

test('shows the empty configuration workspace after loading', async () => {
  vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }))))

  render(<ConfigurationPage />)

  expect(await screen.findByRole('heading', { name: '创建第一个配置' })).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: '选择一个配置' })).toBeInTheDocument()
})

test('publishes the selected profile and refreshes versions', async () => {
  let published = false
  const draft = { chunking: { maxChars: 2000, overlap: 80 }, retrieval: { topKPerChannel: 30, rrfK: 60, contextTopK: 6, perDocumentMax: 3, contextMaxChars: 4000 }, generation: { maxHistoryTurns: 6, minEvidenceChars: 50 }, prompt: { systemTemplate: '模板' }, model: { chatModel: 'deterministic', embeddingModel: 'deterministic' } }
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    if (url === '/api/configuration/profiles') return json([{ id: 'p-1', name: '检索基线', description: null, versionCount: 0, latestVersionNo: null }])
    if (url === '/api/configuration/profiles/p-1') return json({ id: 'p-1', name: '检索基线', description: null, draft })
    if (url.endsWith('/publish') && init?.method === 'POST') { published = true; return json({ versionId: 'v-1', versionNo: 1 }) }
    if (url.endsWith('/versions')) return json(published ? [{ id: 'v-1', versionNo: 1, createdAt: '2026-08-15T00:00:00Z' }] : [])
    return json([])
  })
  vi.stubGlobal('fetch', fetchMock)

  render(<ConfigurationPage />)

  fireEvent.click(await screen.findByRole('button', { name: '发布版本' }))

  expect(await screen.findByRole('status')).toHaveTextContent('已发布版本 v1')
  expect(await screen.findByText('v1')).toBeInTheDocument()
})
```

- [ ] **Step 4: 运行测试确认通过**

Run: `npm --prefix web test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add web/src/app/routes.tsx web/src/app/App.test.tsx web/src/features/configuration/ConfigurationPage.test.tsx
git commit -m "feat: wire configuration page into console and cover interactions"
```

---

### Task 9: 全量质量门禁

**Files:** 无新增。

- [ ] **Step 1: 运行完整门禁**

Run: `./scripts/verify.sh`
Expected: 四段全部通过，输出 `verify.sh: all checks passed`。

- [ ] **Step 2: 若有失败，修复后重跑**

不局部跳过；修复后重跑完整 `./scripts/verify.sh` 直到全绿。

- [ ] **Step 3: 确认工作树状态**

Run: `git status`
Expected: 只有本计划文件变更，无意外文件。

---

## Self-Review

**Spec coverage：**
- C1 仅定义不可变配置 → 全程不修改运行时模块
- C2 单一聚合 Profile → `ProfileConfig` + `ConfigurationProfile` + `ConfigurationProfileVersion`
- C3 全维度纳入 → 五维 record + prompt 模板 + model 标识
- C4 版本化 = 草稿 + 快照 → `draft` JSONB + `publish` 冻结
- C5 固定结构 JSONB → `@JdbcTypeCode(SqlTypes.JSON)` + `columnDefinition = "jsonb"`
- C6 模块边界 → Task 4 `package-info` + ArchitectureTest
- C7 迁移 V9 → Task 1
- C8 API 命名空间 → Task 4
- C9 权限 → `ConfigurationAuthorization`

**验收标准对照：**
- 建 Profile / 编辑五维 / 发布 / 列表 → Task 7 UI + Task 8 测试
- 发布后改 draft 不影响版本 → Task 5 集成测试
- 五维不完整拒绝发布 → Task 3 单测 + Task 5 集成测试
- `./scripts/verify.sh` 全绿 → Task 9

**风险与注意：**
- Jackson 3 `JsonMapper` 由 Spring Boot 自动配置提供 bean，不新增 `@Bean`。
- `ProfileView.versionCount` 在 controller 的 `toView` 里调用 `service.listVersions(p.getId()).size()`（每个 profile 一次查询，列表规模小，可接受；若后续规模变大再优化为一次聚合查询）。
- 五维 record 直接作为请求/响应体（Jackson 自动序列化/反序列化），实体内部用 JSONB string 存储，二者在 service/controller 边界用 `JsonMapper` 转换。
