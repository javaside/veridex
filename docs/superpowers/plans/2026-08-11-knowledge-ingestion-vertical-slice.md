# 知识入库垂直切片（Phase 2）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付知识入库垂直切片：管理员创建知识库、上传 PDF/DOCX/TXT/Markdown 文档、观察持久化异步处理、预览解析后的分块、发布不可变的文档/索引发布（IndexRelease），且重复投递不产生重复 chunk、worker 重启可续传、失败的新版本不影响旧发布、离线文档立即从检索中排除。

**Architecture:** 在 Phase 1 的 Spring Boot 4 模块化单体上实现。处理管道（parse → chunk → embed → index）由 `ingestion` 模块编排，通过 `indexing` 模块的 `ChunkIndexer` 端口写入 OpenSearch；平台拥有 `Document`、`DocumentVersion`、`IndexRelease` 三个分离的概念；数据库（PostgreSQL）是唯一事实源，Redis/OpenSearch 不持有业务事实；`shared` 模块承载事务 Outbox 与 RabbitMQ 发布、确定性 Embedding 模型。本阶段内 worker 以同进程 `@RabbitListener` 实现（后续可按需拆分为独立进程）。

**Tech Stack:** Java 21, Maven, Spring Boot 4.x, Spring Data JPA, Spring Security, Spring AMQP (RabbitMQ), Spring AI 2.0.0 GA（`EmbeddingModel`、`Document`、`VectorStore` 接口）, Apache Tika 3.3.1, MinIO Java SDK 8.6.0, opensearch-java 3.9.0, Testcontainers, React + TypeScript + Vite + Vitest。

## Global Constraints

- 单一私有化企业部署，非多租户 SaaS；面向 500–5000 用户、1–5 百万 chunk 验证。
- `Document`、`DocumentVersion`、`IndexRelease` 保持为平台拥有的分离概念；PostgreSQL 是事实源。
- 使用 Spring AI 2.0 GA 作为集成框架（`EmbeddingModel`、`Document`、`VectorStore` 接口），不由其持有平台域状态；OpenSearch 索引生命周期（index/alias）由 `indexing` 模块完全控制。
- 本阶段不实现 GraphRAG、通用 agent、工作流构建器、计费、多租户。
- 上传解析器有沙箱限制（类型白名单、大小上限）；不默认记录 prompt/completion/向量响应体（Phase 1 已锁定，禁止改回）。
- TDD、真实基础设施集成测试（Testcontainers）、每任务一个聚焦提交。
- **架构决策（显式）：** 本阶段调整两条 Spring Modulith 依赖规则并保留 `ArchitectureTest` 回归门禁：
  1. `ingestion` 的 `allowedDependencies` 从 `{"shared","knowledge"}` 改为 `{"shared","knowledge","indexing"}`（worker 编排需调用 indexing 的 `ChunkIndexer` 端口）；
  2. `knowledge` 的 `allowedDependencies` 从 `{"shared","iam"}` 改为 `{"shared","iam","audit"}`（上传/授权操作需记录审计事件）。
  其余模块边界不变。
- 索引 chunk 的 `_id` 固定为 `{documentVersionId}:{chunkIndex}`，使重复投递幂等（bulk 覆盖写同一文档）。

## 关键外部 API（已验证，2026-08-11）

- Spring AI 2.0.0 GA：`org.springframework.ai.document.Document`（构造 `Document(String)`、`Document(String, Map)`、`Document(String id, String, Map)`；方法 `getId()`/`getText()`/`getMetadata()`，无 `getContent()`）；`org.springframework.ai.embedding.EmbeddingModel`（`float[] embed(String)`、`List<float[]> embed(List<String>)`、`int dimensions()`）；`org.springframework.ai.vectorstore.VectorStore`（`add(List<Document>)`、`delete(List<String>)`）；`DocumentReader/DocumentTransformer/DocumentWriter` 位于 `org.springframework.ai.document` 顶层包。
- Spring Modulith 2.0 模块暴露规则（实测确认）：**unnamed interface 只包含模块根包的 public 类**，`api`/`domain`/`application`/`infrastructure` 子包的类**不会**自动对外可见。跨模块引用必须：
  1. 被依赖模块的 `api` 子包声明 `@org.springframework.modulith.NamedInterface(name = "api")`（写在 `api/package-info.java`）；
  2. 依赖方 `allowedDependencies` 写 `"模块名::api"`（如 `"iam::api"`）。
  违反时报 `Module 'X' depends on module 'Y' via ... Allowed targets: ...`（尽管目标在 allowedDependencies 里也报）。同时 **`application` 子包的类不能直接依赖其他模块**——跨模块逻辑要放到本模块 `api` 子包的门面/端口（例：`knowledge.api.KnowledgeBaseAuthorization` 承载授权判断，`knowledge.application` 委托它）。
- Spring Boot 4 / Spring Framework 7 迁移到 **Jackson 3**：`JsonMapper`（`tools.jackson.databind.json.JsonMapper`）取代 Jackson 2 的 `ObjectMapper` 成为自动配置的 bean；`com.fasterxml.jackson.databind.ObjectMapper`（Jackson 2）仍随 web starter 传递但在自动配置中不再注册。所有序列化代码统一用 `JsonMapper`。
- opensearch-java `3.9.0` + opensearch-rest-client `3.8.0`（配 OpenSearch 3.2.0）；`OpenSearchClient` 构造：`new OpenSearchClient(new RestClientTransport(RestClient.builder(HttpHost.create(uri)).build(), new JacksonJsonpMapper()))`。
- MinIO Java SDK `8.6.0`：`MinioClient.builder().endpoint(uri).credentials(ak, sk).build()`；`bucketExists`、`makeBucket`、`putObject`、`getObject`、`removeObject`、`statObject`。
- Apache Tika `3.3.1`（`tika-parsers-standard-package`）：`AutoDetectParser` + `BodyContentHandler(-1)` + `Metadata` + `ParseContext`。注意：该包经 Apache POI 传递依赖，与 Spring Boot 存在冲突风险，Task 1 必须做 `mvn dependency:tree` + `clean verify` 验证。
- RabbitMQ：Spring Boot `spring-boot-starter-amqp` 提供 `RabbitTemplate`、`@RabbitListener`、`AcknowledgeMode.MANUAL`。
- Spring Security 6：`spring-boot-starter-security`，`DaoAuthenticationProvider` + `BCryptPasswordEncoder` + 基于 session 的 `HttpSecurity`。

---

## 计划文件结构（新增/修改一览）

```text
backend/pom.xml                                        Modify: 新增依赖
backend/src/main/resources/application.yml             Modify: rabbitmq/minio/opensearch/embedding 配置
backend/src/main/resources/db/migration/V2__identity.sql                    Create
backend/src/main/resources/db/migration/V3__knowledge.sql                   Create
backend/src/main/resources/db/migration/V4__index_release.sql              Create
backend/src/main/java/io/veridex/shared/outbox/OutboxEventEntity.java    Create
backend/src/main/java/io/veridex/shared/outbox/OutboxEventRepository.java
backend/src/main/java/io/veridex/shared/outbox/OutboxWriter.java
backend/src/main/java/io/veridex/shared/outbox/OutboxPublisher.java
backend/src/main/java/io/veridex/shared/infrastructure/messaging/RabbitTopology.java
backend/src/main/java/io/veridex/shared/infrastructure/embedding/DeterministicEmbeddingModel.java
backend/src/main/java/io/veridex/iam/domain/Role.java
backend/src/main/java/io/veridex/iam/domain/PlatformUser.java
backend/src/main/java/io/veridex/iam/domain/PlatformUserRepository.java
backend/src/main/java/io/veridex/iam/infrastructure/{SecurityConfig,PlatformUserDetailsService,PlatformUserDetails,SecurityContextRole}.java
backend/src/main/java/io/veridex/iam/application/CurrentActor.java
backend/src/main/java/io/veridex/iam/api/AuthController.java
backend/src/main/java/io/veridex/knowledge/domain/{KnowledgeBase,Document,DocumentVersion,KnowledgeBaseGrant}.java
backend/src/main/java/io/veridex/knowledge/domain/{DocumentVersionStatus,GrantLevel,KnowledgeBaseStatus}.java
backend/src/main/java/io/veridex/knowledge/domain/*Repository.java
backend/src/main/java/io/veridex/knowledge/application/{KnowledgeBaseService,DocumentService,ObjectStorage,DocumentUploadHandler}.java
backend/src/main/java/io/veridex/knowledge/infrastructure/MinioObjectStorage.java
backend/src/main/java/io/veridex/knowledge/api/{KnowledgeBaseController,DocumentController,PreviewController}.java
backend/src/main/java/io/veridex/ingestion/domain/{ParsedDocument,Chunk}.java
backend/src/main/java/io/veridex/ingestion/application/{DocumentParser,StructureChunker}.java
backend/src/main/java/io/veridex/ingestion/domain/StructureFirstChunker.java
backend/src/main/java/io/veridex/ingestion/infrastructure/TikaDocumentParser.java
backend/src/main/java/io/veridex/ingestion/application/DocumentIngestionWorker.java
backend/src/main/java/io/veridex/indexing/domain/{IndexRelease,IndexReleaseStatus}.java
backend/src/main/java/io/veridex/indexing/domain/IndexReleaseRepository.java
backend/src/main/java/io/veridex/indexing/api/ChunkRecord.java
backend/src/main/java/io/veridex/indexing/application/{ChunkIndexer,IndexReleaseService,SearchIndexGateway}.java
backend/src/main/java/io/veridex/indexing/infrastructure/{OpenSearchIndexGateway,ReleaseChunkIndexer}.java
backend/src/main/java/io/veridex/indexing/api/IndexReleaseController.java
backend/src/main/java/io/veridex/audit/application/AuditRecorder.java
backend/src/main/java/io/veridex/audit/infrastructure/JpaAuditRecorder.java
backend/src/main/java/io/veridex/shared/infrastructure/config/{MinioProperties,OpenSearchProperties,EmbeddingProperties,InfrastructurePropertiesConfiguration,InfrastructureBeans,OpenSearchClientConfig}.java
backend/src/main/java/io/veridex/{knowledge,ingestion}/package-info.java   Modify: 依赖规则
backend/src/test/java/io/veridex/support/{MinioContainerConfiguration,RabbitContainerConfiguration,OpenSearchContainerConfiguration}.java  Create
backend/src/test/java/io/veridex/... 各任务测试
web/src/features/auth/{LoginPage.tsx,authApi.ts}
web/src/features/knowledge/{KnowledgePage.tsx,knowledgeApi.ts,components/*,KnowledgePage.test.tsx}
web/src/app/routes.tsx                Modify: /knowledge 挂真实页面、/login
web/src/app/App.tsx                   Modify: 登录守卫
```

---

### Task 1: 基础设施依赖与配置属性

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/io/veridex/shared/infrastructure/config/MinioProperties.java`
- Create: `backend/src/main/java/io/veridex/shared/infrastructure/config/OpenSearchProperties.java`
- Create: `backend/src/main/java/io/veridex/shared/infrastructure/config/EmbeddingProperties.java`
- Create: `backend/src/main/java/io/veridex/shared/infrastructure/config/InfrastructurePropertiesConfiguration.java`
- Create: `backend/src/test/java/io/veridex/ConfigurationPropertiesBindingTest.java`

**Interfaces:**
- Consumes: Phase 1 的 `VeridexApplication`、`ConfigurationSafetyTest`。
- Produces: 绑定前缀为 `veridex.storage.minio.*`、`veridex.search.opensearch.*`、`veridex.embedding.*` 的配置属性类；供 Task 4/8/9 使用。

- [ ] **Step 1: 写失败的配置绑定测试**

```java
package io.veridex;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.shared.infrastructure.config.EmbeddingProperties;
import io.veridex.shared.infrastructure.config.MinioProperties;
import io.veridex.shared.infrastructure.config.OpenSearchProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
class ConfigurationPropertiesBindingTest {

    @Autowired MinioProperties minio;
    @Autowired OpenSearchProperties openSearch;
    @Autowired EmbeddingProperties embedding;

    @Test
    void minioPropertiesBindWithDefaults() {
        assertThat(minio.endpoint()).isEqualTo("http://localhost:9000");
        assertThat(minio.accessKey()).isEqualTo("veridex");
        assertThat(minio.secretKey()).isEqualTo("veridex-local-secret");
        assertThat(minio.bucket()).isEqualTo("veridex-documents");
    }

    @Test
    void openSearchPropertiesBindWithDefaults() {
        assertThat(openSearch.uris()).containsExactly("http://localhost:9200");
        assertThat(openSearch.indexPrefix()).isEqualTo("veridex");
        assertThat(openSearch.dimensions()).isEqualTo(128);
    }

    @Test
    void embeddingPropertiesBindWithDefaults() {
        assertThat(embedding.provider()).isEqualTo("deterministic");
        assertThat(embedding.dimensions()).isEqualTo(128);
    }
}
```

- [ ] **Step 2: 运行并确认失败**

Run: `./mvnw -pl backend test -Dtest=ConfigurationPropertiesBindingTest`
Expected: FAIL（编译失败：`MinioProperties` 等类不存在）。

- [ ] **Step 3: 在 `backend/pom.xml` 增加依赖**

在 `<dependencies>` 中追加（版本由父 POM/显式声明管理）：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
<dependency>
    <groupId>io.minio</groupId>
    <artifactId>minio</artifactId>
    <version>8.6.0</version>
</dependency>
<dependency>
    <groupId>org.opensearch.client</groupId>
    <artifactId>opensearch-java</artifactId>
    <version>3.9.0</version>
</dependency>
<dependency>
    <groupId>org.opensearch.client</groupId>
    <artifactId>opensearch-rest-client</artifactId>
    <version>3.8.0</version>
</dependency>
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-parsers-standard-package</artifactId>
    <version>3.3.1</version>
</dependency>
```

- [ ] **Step 4: 创建配置属性类与装配**

```java
package io.veridex.shared.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "veridex.storage.minio")
public record MinioProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket) {
}
```

```java
package io.veridex.shared.infrastructure.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "veridex.search.opensearch")
public record OpenSearchProperties(
        List<String> uris,
        String indexPrefix,
        int dimensions) {
}
```

```java
package io.veridex.shared.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "veridex.embedding")
public record EmbeddingProperties(
        String provider,
        int dimensions) {
}
```

```java
package io.veridex.shared.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({MinioProperties.class, OpenSearchProperties.class, EmbeddingProperties.class})
public class InfrastructurePropertiesConfiguration {
}
```

- [ ] **Step 5: 在 `application.yml` 增加配置**

在 `spring:` 同级追加：

```yaml
spring:
  rabbitmq:
    host: ${VERIDEX_RABBITMQ_HOST:localhost}
    port: ${VERIDEX_RABBITMQ_PORT:5672}
    username: ${VERIDEX_RABBITMQ_USERNAME:veridex}
    password: ${VERIDEX_RABBITMQ_PASSWORD:veridex-local}
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 55MB

veridex:
  storage:
    minio:
      endpoint: ${VERIDEX_MINIO_ENDPOINT:http://localhost:9000}
      access-key: ${VERIDEX_MINIO_ACCESS_KEY:veridex}
      secret-key: ${VERIDEX_MINIO_SECRET_KEY:veridex-local-secret}
      bucket: ${VERIDEX_MINIO_BUCKET:veridex-documents}
  search:
    opensearch:
      uris: ${VERIDEX_OPENSEARCH_URIS:http://localhost:9200}
      index-prefix: ${VERIDEX_OPENSEARCH_INDEX_PREFIX:veridex}
      dimensions: ${VERIDEX_EMBEDDING_DIMENSIONS:128}
  embedding:
    provider: ${VERIDEX_EMBEDDING_PROVIDER:deterministic}
    dimensions: ${VERIDEX_EMBEDDING_DIMENSIONS:128}
```

注意：`spring.rabbitmq` 与 Compose 默认凭据（`veridex`/`veridex-local`）对齐，可直接连本地拓扑。

- [ ] **Step 6: 运行绑定测试 + 全量验证，检查依赖冲突**

Run:
```bash
./mvnw -pl backend test -Dtest=ConfigurationPropertiesBindingTest,ConfigurationSafetyTest
./mvnw -pl backend dependency:tree -Dincludes=org.apache.poi,org.apache.tika,io.minio,org.opensearch.client
./mvnw -pl backend test
```
Expected: 三个测试全过；`dependency:tree` 无异常冲突（Tika 传递的 POI 与 Spring Boot 管理版本若冲突，Maven 仲裁以 spring-boot-dependencies 为准；若仲裁后编译/测试失败，在提交说明里记录最终版本）。

- [ ] **Step 7: 提交**

```bash
git add backend/pom.xml backend/src/main/resources/application.yml backend/src/main/java/io/veridex/shared backend/src/test/java/io/veridex/ConfigurationPropertiesBindingTest.java
git commit -m "build: add phase 2 infrastructure dependencies and configuration"
```

---

### Task 2: IAM 最小集（用户、角色、会话登录）

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__identity.sql`（users 表 + 种子数据——迁移文件一旦提交并被 Flyway 应用后不可修改，后续任务使用新迁移编号）
- Create: `backend/src/main/java/io/veridex/iam/domain/Role.java`
- Create: `backend/src/main/java/io/veridex/iam/domain/PlatformUser.java`
- Create: `backend/src/main/java/io/veridex/iam/domain/PlatformUserRepository.java`
- Create: `backend/src/main/java/io/veridex/iam/infrastructure/SecurityConfig.java`
- Create: `backend/src/main/java/io/veridex/iam/infrastructure/PlatformUserDetailsService.java`
- Create: `backend/src/main/java/io/veridex/iam/api/AuthController.java`
- Create: `backend/src/test/java/io/veridex/iam/AuthFlowIntegrationTest.java`

**Interfaces:**
- Consumes: `PostgresIntegrationTest`（Phase 1）、`DataSource`。
- Produces: `Role` 枚举；`PlatformUser` 实体（`id: UUID, username, passwordHash, displayName, role, enabled`）；`PlatformUserRepository`（`Optional<PlatformUser> findByUsername(String)`）；`AuthController`（`POST /api/auth/login` 表单登录、`POST /api/auth/logout`、`GET /api/auth/me` 返回当前用户 JSON）；Security 规则 `/api/**` 需认证、`/actuator/**` 与 `/api/auth/login` 匿名。

- [ ] **Step 1: 创建 V2 迁移（完整版，本任务只用 users 表）**

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    role VARCHAR(50) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO users (username, password_hash, display_name, role) VALUES
    ('admin', '__REPLACE_WITH_BCRYPT_OF_veridex__', '平台管理员', 'PLATFORM_ADMIN'),
    ('kadmin', '__REPLACE_WITH_BCRYPT_OF_veridex__', '知识管理员', 'KNOWLEDGE_ADMIN'),
    ('employee', '__REPLACE_WITH_BCRYPT_OF_veridex__', '员工', 'EMPLOYEE');

-- 后续任务将继续在同一迁移中追加 knowledge_base/document/document_version/knowledge_base_grant 表。
```

**重要：** 三个 `__REPLACE_WITH_BCRYPT_OF_veridex__` 是**必须替换的占位符**。实施时用 Step 2 生成的真实 `BCryptPasswordEncoder.encode("veridex")` 哈希替换（三个用户可共用同一哈希），禁止提交占位符。

- [ ] **Step 2: 生成真实 BCrypt 哈希**

写一个一次性临时测试 `backend/src/test/java/io/veridex/support/PasswordHashGeneratorTest.java`，运行后把打印的三个哈希复制到 V2 迁移，然后删除该临时文件：

```java
package io.veridex.support;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PasswordHashGeneratorTest {

    @Test
    void printHashes() {
        var encoder = new BCryptPasswordEncoder();
        System.out.println("HASH=" + encoder.encode("veridex"));
    }
}
```

Run: `./mvnw -pl backend test -Dtest=PasswordHashGeneratorTest`
Expected: 控制台打印 `HASH=$2a$10$...`（真实哈希）。

用打印值替换 V2 迁移中三处 `__REPLACE_WITH_BCRYPT_OF_veridex__`（三个用户共用同一哈希即可），随后**删除临时测试文件**。提交前确认迁移文件中不含占位符。

- [ ] **Step 3: 写失败的身份流程集成测试**

```java
package io.veridex.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient;

@AutoConfigureRestTestClient
class AuthFlowIntegrationTest extends PostgresIntegrationTest {

    @Autowired RestTestClient rest;

    @Test
    void loginWithSeedUserEstablishesSessionAndMeReturnsProfile() {
        rest.post().uri("/api/auth/login?username=admin&password=veridex")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("admin");

        // RestTestClient 自动保持 session：第二次请求携带 JSESSIONID
        rest.get().uri("/api/auth/me")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("admin");
    }

    @Test
    void unknownUserLoginIsRejected() {
        rest.post().uri("/api/auth/login?username=nobody&password=veridex")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
```

**实现要点（实测修正，Spring Boot 4）：**
- `TestRestTemplate` 已移到 `org.springframework.boot.resttestclient` 包，且 Boot 4 的 resttestclient **不再自动管理 JSESSIONID cookie**——认证成功后建立的 session 不会带到后续请求，导致 `/api/auth/me` 变匿名。**统一改用 `RestTestClient`**（MockMvc 系，session 自动保持）+ `@AutoConfigureRestTestClient`。
- `PostgresIntegrationTest` 必须是**共享单例容器**（见 Step 5），否则每个测试类各起一个 `@Container` 会让 Spring context 缓存指向已停止的容器。

- [ ] **Step 4: 运行并确认失败**

Run: `./mvnw -pl backend test -Dtest=AuthFlowIntegrationTest`
Expected: FAIL（无 Security 配置 → 401/无 `/api/auth/login` 端点）。

- [ ] **Step 5: 增强 `PostgresIntegrationTest` 支持 web 环境**

修改 `backend/src/test/java/io/veridex/support/PostgresIntegrationTest.java`：

```java
package io.veridex.support;

import io.veridex.VeridexApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(classes = VeridexApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.hibernate.ddl-auto=validate")
public abstract class PostgresIntegrationTest {

    /**
     * 共享单例 PostgreSQL 容器：静态初始化只执行一次，所有继承类复用同一容器与
     * 同一 JDBC URL，避免 Spring context 缓存指向已停止的容器（每个测试类各起
     * 一个 @Container 会导致此问题）。
     */
    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("veridex")
                .withUsername("veridex")
                .withPassword("veridex");
        POSTGRES.start();
        Runtime.getRuntime().addShutdownHook(new Thread(POSTGRES::stop));
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
```

> 说明（实测修正）：不要用 `@Testcontainers` + 每个测试类独立的 `@Container` 静态字段——多个继承类会让每个类各起一个容器，而 Spring context 缓存只记住第一个容器的 JDBC URL，第一个类结束后容器被销毁，后续类连不上数据库。改用**共享单例容器**（静态初始化 + shutdown hook + `@DynamicPropertySource`），整个 JVM 只启动一次。`spring.jpa.hibernate.ddl-auto=validate` 让 Hibernate 校验实体与 Flyway schema 一致（Phase 2 起有 JPA 实体）。

- [ ] **Step 6: 实现 IAM 代码**

```java
package io.veridex.iam.domain;

public enum Role {
    PLATFORM_ADMIN, KNOWLEDGE_ADMIN, EMPLOYEE
}
```

```java
package io.veridex.iam.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class PlatformUser {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private Role role;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected PlatformUser() {
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public Role getRole() { return role; }
    public boolean isEnabled() { return enabled; }
}
```

```java
package io.veridex.iam.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface PlatformUserRepository extends CrudRepository<PlatformUser, UUID> {
    Optional<PlatformUser> findByUsername(String username);
}
```

```java
package io.veridex.iam.infrastructure;

import io.veridex.iam.domain.PlatformUser;
import io.veridex.iam.domain.Role;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public record PlatformUserDetails(PlatformUser user) implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    @Override
    public String getPassword() { return user.getPasswordHash(); }

    @Override
    public String getUsername() { return user.getUsername(); }

    @Override
    public boolean isEnabled() { return user.isEnabled(); }
}
```

```java
package io.veridex.iam.infrastructure;

import io.veridex.iam.domain.PlatformUserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class PlatformUserDetailsService implements UserDetailsService {

    private final PlatformUserRepository users;

    public PlatformUserDetailsService(PlatformUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return users.findByUsername(username)
                .map(PlatformUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("unknown user: " + username));
    }
}
```

```java
package io.veridex.iam.infrastructure;

import tools.jackson.databind.json.JsonMapper;
import io.veridex.iam.domain.PlatformUser;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JsonMapper objectMapper;

    public SecurityConfig(JsonMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/api/auth/login", "/api/auth/logout").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginProcessingUrl("/api/auth/login")
                .successHandler((req, res, auth) -> {
                    res.setStatus(200);
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    if (auth.getPrincipal() instanceof PlatformUserDetails details) {
                        PlatformUser user = details.user();
                        res.getWriter().write(objectMapper.writeValueAsString(Map.of(
                                "id", user.getId().toString(),
                                "username", user.getUsername(),
                                "displayName", user.getDisplayName(),
                                "role", user.getRole().name())));
                    }
                })
                .failureHandler((req, res, exc) -> res.sendError(401)))
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

```java
package io.veridex.iam.api;

import io.veridex.iam.domain.PlatformUser;
import io.veridex.iam.infrastructure.PlatformUserDetails;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal PlatformUserDetails principal) {
        PlatformUser user = principal.user();
        return Map.of(
                "id", user.getId().toString(),
                "username", user.getUsername(),
                "displayName", user.getDisplayName(),
                "role", user.getRole().name());
    }
}
```

（`PlatformUserDetails` 是 record，位于 `io.veridex.iam.infrastructure`，accessor 为 `user()`。）

- [ ] **Step 7: 运行测试**

Run:
```bash
./mvnw -pl backend test -Dtest=AuthFlowIntegrationTest,VeridexApplicationTest,ArchitectureTest,DatabaseMigrationTest
```
Expected: 全部通过。若 `DatabaseMigrationTest` 因 V2 迁移种子数据断言受影响，检查其只断言 `installation/outbox_event/audit_event` 存在（V1 表未动，应仍通过）。

- [ ] **Step 8: 提交**

```bash
git add backend/src/main/resources/db/migration backend/src/main/java/io/veridex/iam backend/src/test/java/io/veridex/support/PostgresIntegrationTest.java backend/src/test/java/io/veridex/iam
git commit -m "feat: add minimal identity and session authentication"
```

---

### Task 3: 知识域模型（KnowledgeBase、Document、DocumentVersion、授权）

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__knowledge.sql`（knowledge_base/document/document_version/knowledge_base_grant 四表——独立的迁移编号，因 V2 已提交且被 Flyway 应用，不可修改）
- Create: `backend/src/main/java/io/veridex/knowledge/domain/KnowledgeBaseStatus.java`
- Create: `backend/src/main/java/io/veridex/knowledge/domain/GrantLevel.java`
- Create: `backend/src/main/java/io/veridex/knowledge/domain/KnowledgeBase.java`
- Create: `backend/src/main/java/io/veridex/knowledge/domain/Document.java`
- Create: `backend/src/main/java/io/veridex/knowledge/domain/DocumentVersion.java`
- Create: `backend/src/main/java/io/veridex/knowledge/domain/DocumentVersionStatus.java`
- Create: `backend/src/main/java/io/veridex/knowledge/domain/KnowledgeBaseGrant.java`
- Create: `backend/src/main/java/io/veridex/knowledge/domain/{KnowledgeBaseRepository,DocumentRepository,DocumentVersionRepository,KnowledgeBaseGrantRepository}.java`
- Create: `backend/src/main/java/io/veridex/knowledge/application/KnowledgeBaseService.java`
- Create: `backend/src/test/java/io/veridex/knowledge/DocumentLifecycleIntegrationTest.java`

**Interfaces:**
- Consumes: `PostgresIntegrationTest`、`PlatformUser`（owner 外键）。
- Produces:
  - `KnowledgeBaseService.createKnowledgeBase(UUID actorId, String name, String description)` → `KnowledgeBase`
  - `KnowledgeBaseService.grantAccess(UUID actorId, UUID kbId, UUID userId, GrantLevel level)`、`revokeAccess(...)`（同签名，level 为 null 即撤销）
  - `KnowledgeBaseService.canView(UUID kbId, UUID userId, Role role)` / `canManage(...)`：PLATFORM_ADMIN 恒真；KNOWLEDGE_ADMIN 恒真（管理）；否则查 `knowledge_base_grant`。
  - `DocumentService.upload(UUID actorId, UUID kbId, String filename, String contentType, long sizeBytes, byte[] sha256)` → `DocumentVersion`（校验：扩展名白名单、大小上限、授权；建 `Document`（首次）与 `DocumentVersion(UPLOADED)`）。
  - `DocumentService.markProcessing(UUID versionId)`、`markReady(UUID versionId, int chunkCount)`、`markFailed(UUID versionId, String reason)`、`setParsedObjectKey(UUID versionId, String key)`、`listDocuments(UUID kbId)`、`listVersions(UUID documentId)`、`findVersion(UUID versionId)`。
  - `DocumentVersionStatus`：`UPLOADED, PROCESSING, READY, FAILED, OFFLINE`。
  - `KnowledgeBaseStatus`：`ACTIVE, ARCHIVED`。

- [ ] **Step 1: 在 V3 迁移中创建表**

在 V2 之后新增迁移（V2 已提交并被 Flyway 应用，故用新编号 V3）：

```sql
CREATE TABLE knowledge_base (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    slug VARCHAR(200) NOT NULL UNIQUE,
    description TEXT,
    owner_id UUID NOT NULL REFERENCES users(id),
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE knowledge_base_grant (
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    level VARCHAR(50) NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (knowledge_base_id, user_id)
);

CREATE TABLE document (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    filename VARCHAR(300) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE document_version (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    version_no INTEGER NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    file_sha256 CHAR(64) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'UPLOADED',
    parsed_object_key VARCHAR(500),
    chunk_count INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    UNIQUE (document_id, version_no)
);
```

- [ ] **Step 2: 写失败的文档生命周期测试**

```java
package io.veridex.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.veridex.iam.domain.Role;
import io.veridex.knowledge.application.KnowledgeBaseService;
import io.veridex.knowledge.domain.DocumentVersionStatus;
import io.veridex.knowledge.domain.GrantLevel;
import io.veridex.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class DocumentLifecycleIntegrationTest extends PostgresIntegrationTest {

    @Autowired KnowledgeBaseService knowledgeBases;
    @Autowired DocumentService documents;

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void createKnowledgeBaseGrantsOwnerManageAccess() {
        var kb = knowledgeBases.createKnowledgeBase(ACTOR, "人事制度", "员工人事政策");
        assertThat(kb.getSlug()).isNotBlank();
        assertThat(knowledgeBases.canManage(kb.getId(), ACTOR, Role.EMPLOYEE)).isTrue();
    }

    @Test
    void uploadCreatesUploadedVersionAndSecondUploadBumpsVersion() {
        var kb = knowledgeBases.createKnowledgeBase(ACTOR, "产品手册", null);
        var v1 = documents.upload(ACTOR, kb.getId(), "guide.md", "text/markdown", 1024L, "a".repeat(64));
        assertThat(v1.getStatus()).isEqualTo(DocumentVersionStatus.UPLOADED);
        assertThat(v1.getVersionNo()).isEqualTo(1);

        var v2 = documents.upload(ACTOR, kb.getId(), "guide.md", "text/markdown", 2048L, "b".repeat(64));
        assertThat(v2.getVersionNo()).isEqualTo(2);
    }

    @Test
    void unknownActorCannotManageWithoutGrant() {
        var kb = knowledgeBases.createKnowledgeBase(ACTOR, "财务", null);
        var other = UUID.fromString("00000000-0000-0000-0000-000000000002");
        assertThat(knowledgeBases.canManage(kb.getId(), other, Role.EMPLOYEE)).isFalse();
        assertThatThrownBy(() -> documents.upload(other, kb.getId(), "x.md", "text/markdown", 1L, "c".repeat(64)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void unsupportedExtensionIsRejected() {
        var kb = knowledgeBases.createKnowledgeBase(ACTOR, "白名单", null);
        assertThatThrownBy(() -> documents.upload(ACTOR, kb.getId(), "virus.exe", "application/octet-stream", 1L, "d".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 3: 运行并确认失败**

Run: `./mvnw -pl backend test -Dtest=DocumentLifecycleIntegrationTest`
Expected: FAIL（编译失败：类不存在）。

- [ ] **Step 4: 创建实体与枚举**

```java
package io.veridex.knowledge.domain;

public enum KnowledgeBaseStatus { ACTIVE, ARCHIVED }
```

```java
package io.veridex.knowledge.domain;

public enum GrantLevel { VIEW, MANAGE }
```

```java
package io.veridex.knowledge.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "knowledge_base")
public class KnowledgeBase {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, unique = true, length = 200)
    private String slug;

    @Column
    private String description;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private KnowledgeBaseStatus status = KnowledgeBaseStatus.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected KnowledgeBase() {
    }

    public KnowledgeBase(String name, String slug, String description, UUID ownerId) {
        this.name = name;
        this.slug = slug;
        this.description = description;
        this.ownerId = ownerId;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public String getDescription() { return description; }
    public UUID getOwnerId() { return ownerId; }
    public KnowledgeBaseStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
```

（`Document`、`DocumentVersion` 实体类似；`DocumentVersion` 含上表全部列。`KnowledgeBaseGrant` 用 `@EmbeddedId`/复合主键，简单起见用 `@IdClass`；本计划建议用组合唯一键 + 显式 `repository.findByKnowledgeBaseIdAndUserId`。）

- [ ] **Step 5: 实现服务**

```java
package io.veridex.knowledge.application;

import io.veridex.iam.domain.Role;
import io.veridex.knowledge.domain.GrantLevel;
import io.veridex.knowledge.domain.KnowledgeBase;
import io.veridex.knowledge.domain.KnowledgeBaseGrant;
import io.veridex.knowledge.domain.KnowledgeBaseRepository;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository knowledgeBases;
    private final KnowledgeBaseGrantRepository grants;

    public KnowledgeBaseService(KnowledgeBaseRepository knowledgeBases, KnowledgeBaseGrantRepository grants) {
        this.knowledgeBases = knowledgeBases;
        this.grants = grants;
    }

    public KnowledgeBase createKnowledgeBase(UUID actorId, String name, String description) {
        String slug = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        KnowledgeBase kb = new KnowledgeBase(name.trim(), slug, description, actorId);
        knowledgeBases.save(kb);
        grants.save(new KnowledgeBaseGrant(kb.getId(), actorId, GrantLevel.MANAGE));
        return kb;
    }

    public void grantAccess(UUID kbId, UUID userId, GrantLevel level) {
        grants.save(new KnowledgeBaseGrant(kbId, userId, level));
    }

    public void revokeAccess(UUID kbId, UUID userId) {
        grants.deleteByKnowledgeBaseIdAndUserId(kbId, userId);
    }

    public boolean canManage(UUID kbId, UUID userId, Role role) {
        if (role == Role.PLATFORM_ADMIN || role == Role.KNOWLEDGE_ADMIN) {
            return true;
        }
        return grants.findByKnowledgeBaseIdAndUserId(kbId, userId)
                .map(g -> g.getLevel() == GrantLevel.MANAGE)
                .orElse(false);
    }

    public boolean canView(UUID kbId, UUID userId, Role role) {
        if (role == Role.PLATFORM_ADMIN || role == Role.KNOWLEDGE_ADMIN) {
            return true;
        }
        return grants.findByKnowledgeBaseIdAndUserId(kbId, userId).isPresent();
    }
}
```

```java
package io.veridex.knowledge.application;

import io.veridex.iam.domain.Role;
import io.veridex.knowledge.domain.Document;
import io.veridex.knowledge.domain.DocumentVersion;
import io.veridex.knowledge.domain.DocumentVersionStatus;
import io.veridex.knowledge.domain.DocumentRepository;
import io.veridex.knowledge.domain.DocumentVersionRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class DocumentService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "txt", "md");
    private static final long MAX_BYTES = 50L * 1024 * 1024;

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final KnowledgeBaseService knowledgeBases;

    public DocumentService(DocumentRepository documents, DocumentVersionRepository versions,
                           KnowledgeBaseService knowledgeBases) {
        this.documents = documents;
        this.versions = versions;
        this.knowledgeBases = knowledgeBases;
    }

    public DocumentVersion upload(UUID actorId, UUID kbId, String filename, String contentType,
                                  long sizeBytes, String sha256Hex) {
        String ext = extensionOf(filename);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("unsupported file type: " + ext);
        }
        if (sizeBytes > MAX_BYTES) {
            throw new IllegalArgumentException("file exceeds 50MB limit");
        }
        if (!knowledgeBases.canManage(kbId, actorId, currentRole())) {
            throw new SecurityException("no MANAGE grant on knowledge base " + kbId);
        }
        Document document = documents.findByKnowledgeBaseIdAndFilename(kbId, filename)
                .orElseGet(() -> documents.save(new Document(kbId, filename, contentType, sizeBytes, actorId)));
        int nextVersion = versions.countByDocumentId(document.getId()) + 1;
        return versions.save(new DocumentVersion(document.getId(), nextVersion,
                objectKey(kbId, document.getId(), nextVersion, filename), sha256Hex));
    }

    private static String objectKey(UUID kbId, UUID documentId, int versionNo, String filename) {
        return kbId + "/" + documentId + "/v" + versionNo + "/" + filename;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    // Phase 2 简化：当前登录角色由调用方传入上下文；本任务通过静态上下文读取，避免引入全局状态。
    // 见 Step 6 说明。
    private Role currentRole() {
        return io.veridex.iam.infrastructure.SecurityContextRole.currentRole();
    }
}
```

- [ ] **Step 6: 当前角色上下文**

创建 `backend/src/main/java/io/veridex/iam/infrastructure/SecurityContextRole.java`（iam 模块，被 knowledge 依赖方使用）：

```java
package io.veridex.iam.infrastructure;

import io.veridex.iam.domain.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityContextRole {

    private SecurityContextRole() {
    }

    public static Role currentRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return Role.EMPLOYEE;
        }
        if (auth.getPrincipal() instanceof PlatformUserDetails details) {
            return details.user().getRole();
        }
        return Role.EMPLOYEE;
    }
}
```

> 说明：`SecurityContextRole` 属于 iam 模块的 infrastructure，knowledge 允许依赖 iam，因此可用。`canView/canManage` 的 actor 角色参数在测试中直接传入（测试未登录时为 EMPLOYEE，符合语义）。

- [ ] **Step 7: 运行测试 + 全量回归**

Run:
```bash
./mvnw -pl backend test -Dtest=DocumentLifecycleIntegrationTest,AuthFlowIntegrationTest,ArchitectureTest,DatabaseMigrationTest
./mvnw -pl backend test
```
Expected: 全部通过。

- [ ] **Step 8: 提交**

```bash
git add backend/src/main/resources/db/migration/V2__identity_and_knowledge.sql backend/src/main/java/io/veridex/knowledge backend/src/main/java/io/veridex/iam/infrastructure/SecurityContextRole.java backend/src/test/java/io/veridex/knowledge
git commit -m "feat: add knowledge base, document and version lifecycle"
```

---

### Task 4: 对象存储适配器（MinIO）

**Files:**
- Create: `backend/src/main/java/io/veridex/knowledge/application/ObjectStorage.java`
- Create: `backend/src/main/java/io/veridex/knowledge/infrastructure/MinioObjectStorage.java`
- Create: `backend/src/test/java/io/veridex/support/MinioContainerConfiguration.java`
- Create: `backend/src/test/java/io/veridex/knowledge/MinioObjectStorageTest.java`

**Interfaces:**
- Consumes: `MinioProperties`（Task 1）。
- Produces: `ObjectStorage`（knowledge.application 端口）：
  - `void put(String objectKey, InputStream data, String contentType, long size)`
  - `InputStream get(String objectKey)`
  - `void delete(String objectKey)`
  - `boolean exists(String objectKey)`
  - `String bucket()`
- 实现：`MinioObjectStorage`（`MinioClient`，启动时 `ensureBucket()`：`bucketExists` → `makeBucket`）。

- [ ] **Step 1: 写失败的容器测试**

```java
package io.veridex.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.knowledge.application.ObjectStorage;
import io.veridex.support.MinioContainerConfiguration;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(classes = io.veridex.VeridexApplication.class,
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
                "spring.rabbitmq.listener.simple.auto-startup=false"
        })
@Import(MinioContainerConfiguration.class)
class MinioObjectStorageTest {

    @Autowired ObjectStorage storage;

    @Test
    void putGetAndDeleteRoundTrip() {
        String key = "kb/doc/v1/guide.md";
        storage.put(key, new ByteArrayInputStream("hello world".getBytes(StandardCharsets.UTF_8)),
                "text/markdown", 11L);

        assertThat(storage.exists(key)).isTrue();
        try (var in = storage.get(key)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello world");
        }
        storage.delete(key);
        assertThat(storage.exists(key)).isFalse();
    }
}
```

- [ ] **Step 2: 运行并确认失败**

Run: `./mvnw -pl backend test -Dtest=MinioObjectStorageTest`
Expected: FAIL（编译失败：`ObjectStorage`/`MinioContainerConfiguration` 不存在）。

- [ ] **Step 3: 创建 MinIO 容器配置**

```java
package io.veridex.support;

import io.minio.MinioClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class MinioContainerConfiguration {

    private static final String ACCESS_KEY = "veridex";
    private static final String SECRET_KEY = "veridex-local-secret";

    @Bean(destroyMethod = "stop")
    GenericContainer<?> minioContainer() {
        return new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2025-07-23T15-54-02Z"))
                .withCommand("server", "/data", "--console-address", ":9001")
                .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                .withExposedPorts(9000, 9001);
    }

    @Bean
    MinioClient minioClient(GenericContainer<?> minioContainer) {
        String endpoint = "http://" + minioContainer.getHost() + ":" + minioContainer.getMappedPort(9000);
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(ACCESS_KEY, SECRET_KEY)
                .build();
    }
}
```

- [ ] **Step 4: 实现端口与适配器**

```java
package io.veridex.knowledge.application;

import java.io.InputStream;

public interface ObjectStorage {
    void put(String objectKey, InputStream data, String contentType, long size);
    InputStream get(String objectKey);
    void delete(String objectKey);
    boolean exists(String objectKey);
    String bucket();
}
```

```java
package io.veridex.knowledge.infrastructure;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.veridex.knowledge.application.ObjectStorage;
import io.veridex.shared.infrastructure.config.MinioProperties;
import java.io.InputStream;
import org.springframework.stereotype.Component;

@Component
public class MinioObjectStorage implements ObjectStorage {

    private final MinioClient client;
    private final String bucket;

    public MinioObjectStorage(MinioClient minioClient, MinioProperties properties) throws Exception {
        this.client = minioClient;
        this.bucket = properties.bucket();
        ensureBucket();
    }

    private void ensureBucket() throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    @Override
    public void put(String objectKey, InputStream data, String contentType, long size) {
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket).object(objectKey).stream(data, size, -1).contentType(contentType).build());
        } catch (Exception e) {
            throw new RuntimeException("failed to put object " + objectKey, e);
        }
    }

    @Override
    public InputStream get(String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw new RuntimeException("failed to get object " + objectKey, e);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw new RuntimeException("failed to delete object " + objectKey, e);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String bucket() {
        return bucket;
    }
}
```

> 注意：`MinioObjectStorage` 依赖 `MinioClient` bean。生产环境需在 `shared.infrastructure.config` 增加 `MinioClient` bean（Task 4 用测试容器 bean 代替；生产 bean 见 Task 6 的 `InfrastructureBeans`，避免重复定义冲突——实现时把 `MinioClient` bean 放到一个 `@Configuration` 并用 `@ConditionalOnMissingBean` 保护）。

- [ ] **Step 5: 运行测试**

Run: `./mvnw -pl backend test -Dtest=MinioObjectStorageTest`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/io/veridex/knowledge/application/ObjectStorage.java backend/src/main/java/io/veridex/knowledge/infrastructure/MinioObjectStorage.java backend/src/test/java/io/veridex/support/MinioContainerConfiguration.java backend/src/test/java/io/veridex/knowledge/MinioObjectStorageTest.java
git commit -m "feat: add MinIO object storage adapter"
```

---

### Task 5: 事务 Outbox 发布到 RabbitMQ

**Files:**
- Create: `backend/src/main/java/io/veridex/shared/outbox/OutboxEventEntity.java`
- Create: `backend/src/main/java/io/veridex/shared/outbox/OutboxEventRepository.java`
- Create: `backend/src/main/java/io/veridex/shared/outbox/OutboxWriter.java`
- Create: `backend/src/main/java/io/veridex/shared/outbox/OutboxPublisher.java`
- Create: `backend/src/main/java/io/veridex/shared/infrastructure/messaging/RabbitTopology.java`
- Create: `backend/src/test/java/io/veridex/shared/OutboxPublishingIntegrationTest.java`
- Modify: `backend/src/main/resources/application.yml`（rabbit listener 手动 ack 配置）

**Interfaces:**
- Consumes: V1 的 `outbox_event` 表、`RabbitTemplate`。
- Produces:
  - `OutboxWriter.record(String aggregateType, UUID aggregateId, String eventType, Object payload)`：事务内写 `outbox_event`（payload 经 Jackson 序列化为 JSONB，`schema_version = 1`，`occurred_at = now`）。
  - `OutboxPublisher`：`@TransactionalEventListener(phase = AFTER_COMMIT)` 轮询未发布事件 → 发送到 `RabbitTopology.INGESTION_EXCHANGE` / routing key `document.ingest` → 成功更新 `published_at`；失败更新 `attempts`/`last_error`（留给后续重试）。
  - `RabbitTopology`：声明 direct exchange `veridex.ingestion`、queue `ingestion.document`、binding `document.ingest`；DLX `veridex.dlx` + DLQ `ingestion.document.dlq`，queue 的 DLQ 绑定；队列 `x-dead-letter-exchange: veridex.dlx`。
  - 配置：`spring.rabbitmq.listener.simple.acknowledge-mode: manual`。

- [ ] **Step 1: 写失败的集成测试**

```java
package io.veridex.shared;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.shared.outbox.OutboxWriter;
import io.veridex.support.PostgresContainerConfiguration;
import io.veridex.support.RabbitContainerConfiguration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(classes = io.veridex.VeridexApplication.class,
        properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@Import({PostgresContainerConfiguration.class, RabbitContainerConfiguration.class})
class OutboxPublishingIntegrationTest {

    @Autowired OutboxWriter outboxWriter;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin amqpAdmin;

    @Test
    void transactionCommitPublishesRecordedEventToRabbit() throws Exception {
        UUID versionId = UUID.randomUUID();
        outboxWriter.record("document_version", versionId, "document.version.uploaded",
                Map.of("documentVersionId", versionId.toString()));

        QueueInformation info = awaitQueueDepth("ingestion.document", 1);
        assertThat(info.getMessageCount()).isEqualTo(1);

        var message = rabbit.receive("ingestion.document", 5000);
        assertThat(message).isNotNull();
        String body = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains(versionId.toString());
    }

    private QueueInformation awaitQueueDepth(String queue, int minDepth) throws InterruptedException {
        QueueInformation info = null;
        for (int i = 0; i < 20; i++) {
            info = amqpAdmin.getQueueInfo(queue);
            if (info != null && info.getMessageCount() >= minDepth) {
                return info;
            }
            Thread.sleep(200);
        }
        return info;
    }
}
```

- [ ] **Step 2: 运行并确认失败**

Run: `./mvnw -pl backend test -Dtest=OutboxPublishingIntegrationTest`
Expected: FAIL（编译失败：类不存在）。

- [ ] **Step 3: Rabbit 容器配置**

```java
package io.veridex.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.RabbitMQContainer;

@TestConfiguration(proxyBeanMethods = false)
public class RabbitContainerConfiguration {

    @Bean(destroyMethod = "stop")
    RabbitMQContainer rabbitContainer() {
        return new RabbitMQContainer("rabbitmq:4-management-alpine")
                .withUser("veridex", "veridex-local", "administrator");
    }
}
```

（`@ServiceConnection` 或手动把 `spring.rabbitmq.host/port/username/password` 指到容器——用 `org.springframework.boot.testcontainers.service.connection.ServiceConnection` 最省事，注解在 bean 上即可自动接线。）

- [ ] **Step 4: 实现 Outbox 与发布器**

```java
package io.veridex.shared.outbox;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_event")
public class OutboxEventEntity {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 200)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion = 1;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "last_error")
    private String lastError;

    protected OutboxEventEntity() {
    }

    public OutboxEventEntity(String aggregateType, UUID aggregateId, String eventType, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
    }

    public UUID getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public UUID getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Instant getOccurredAt() { return occurredAt; }
    public void markPublished() { this.publishedAt = Instant.now(); }
    public void recordFailure(String error) {
        this.attempts++;
        this.lastError = error;
    }
}
```

```java
package io.veridex.shared.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends CrudRepository<OutboxEventEntity, UUID> {

    @Query("select e from OutboxEventEntity e where e.publishedAt is null order by e.occurredAt asc")
    List<OutboxEventEntity> findUnpublished();

    @Query("select e from OutboxEventEntity e where e.aggregateType = :type and e.aggregateId = :id and e.publishedAt is null")
    List<OutboxEventEntity> findUnpublishedFor(@Param("type") String type, @Param("id") UUID id);

    default List<OutboxEventEntity> findUnpublishedBefore(Instant cutoff) {
        return findUnpublished();
    }
}
```

```java
package io.veridex.shared.outbox;

import tools.jackson.databind.json.JsonMapper;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxWriter {

    private final OutboxEventRepository repository;
    private final JsonMapper objectMapper;

    public OutboxWriter(OutboxEventRepository repository, JsonMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void record(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            repository.save(new OutboxEventEntity(aggregateType, aggregateId, eventType, json));
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot serialize outbox payload for " + eventType, e);
        }
    }
}
```

```java
package io.veridex.shared.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class OutboxPublisher {

    private final OutboxEventRepository repository;
    private final RabbitTemplate rabbit;
    private final JsonMapper objectMapper;

    public OutboxPublisher(OutboxEventRepository repository, RabbitTemplate rabbit, JsonMapper objectMapper) {
        this.repository = repository;
        this.rabbit = rabbit;
        this.objectMapper = objectMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishPending() {
        List<OutboxEventEntity> pending = repository.findUnpublished();
        for (OutboxEventEntity event : pending) {
            try {
                JsonNode body = objectMapper.readTree(event.getPayload());
                rabbit.convertAndSend(
                        io.veridex.shared.infrastructure.messaging.RabbitTopology.INGESTION_EXCHANGE,
                        routingKeyFor(event.getEventType()),
                        body.toString().getBytes(StandardCharsets.UTF_8));
                event.markPublished();
            } catch (Exception e) {
                event.recordFailure(e.getMessage());
            } finally {
                repository.save(event);
            }
        }
    }

    private static String routingKeyFor(String eventType) {
        return "document.ingest";
    }
}
```

```java
package io.veridex.shared.infrastructure.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopology {

    public static final String INGESTION_EXCHANGE = "veridex.ingestion";
    public static final String INGESTION_QUEUE = "ingestion.document";
    public static final String INGESTION_ROUTING_KEY = "document.ingest";
    public static final String DLX = "veridex.dlx";
    public static final String DLQ = "ingestion.document.dlq";

    @Bean
    DirectExchange ingestionExchange() {
        return new DirectExchange(INGESTION_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    Queue ingestionQueue() {
        return QueueBuilder.durable(INGESTION_QUEUE)
                .deadLetterExchange(DLX)
                .build();
    }

    @Bean
    Queue ingestionDlq() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    Binding ingestionBinding(Queue ingestionQueue, DirectExchange ingestionExchange) {
        return BindingBuilder.bind(ingestionQueue).to(ingestionExchange).with(INGESTION_ROUTING_KEY);
    }
}
```

- [ ] **Step 5: 配置手动 ack**

在 `application.yml` 的 `spring.rabbitmq` 下追加：

```yaml
    listener:
      simple:
        acknowledge-mode: manual
        default-requeue-rejected: false
```

- [ ] **Step 6: 运行测试**

Run: `./mvnw -pl backend test -Dtest=OutboxPublishingIntegrationTest`
Expected: PASS（事务提交后消息出现在 `ingestion.document` 队列）。

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/io/veridex/shared backend/src/main/resources/application.yml backend/src/test/java/io/veridex/support/RabbitContainerConfiguration.java backend/src/test/java/io/veridex/shared
git commit -m "feat: publish transactional outbox events to RabbitMQ"
```

---

### Task 6: 上传 API 与处理触发

**Files:**
- Create: `backend/src/main/java/io/veridex/knowledge/api/KnowledgeBaseController.java`
- Create: `backend/src/main/java/io/veridex/knowledge/api/DocumentController.java`
- Create: `backend/src/main/java/io/veridex/knowledge/api/ApiModels.java`（DTO：`CreateKnowledgeBaseRequest`、`KnowledgeBaseView`、`DocumentView`、`DocumentVersionView`）
- Create: `backend/src/main/java/io/veridex/shared/infrastructure/config/InfrastructureBeans.java`（生产 `MinioClient` bean，`@ConditionalOnMissingBean`）
- Create: `backend/src/main/java/io/veridex/knowledge/application/DocumentUploadHandler.java`（组合 upload + 存对象 + outbox 的用例）
- Create: `backend/src/test/java/io/veridex/knowledge/KnowledgeApiIntegrationTest.java`

**Interfaces:**
- Consumes: `ObjectStorage`、`DocumentService`、`KnowledgeBaseService`、`OutboxWriter`、`AuditRecorder`（Task 5 已建 outbox；`AuditRecorder` 见 Task 6 附带的 audit 最小实现）。
- Produces:
  - `POST /api/knowledge-bases`（body `{name, description?}`）→ 201 + `KnowledgeBaseView`
  - `GET /api/knowledge-bases` → 200 + `List<KnowledgeBaseView>`
  - `GET /api/knowledge-bases/{id}` → 200 + `KnowledgeBaseView`（含文档数与版本数）
  - `POST /api/knowledge-bases/{id}/documents`（`multipart/form-data`：`file`）→ 202 + `DocumentVersionView`
  - `GET /api/knowledge-bases/{id}/documents` → `List<DocumentView>`
  - `GET /api/documents/{documentId}/versions` → `List<DocumentVersionView>`
  - `GET /api/documents/{documentId}/versions/{versionId}/parsed`（Task 10 实现预览体，本任务只返回 200 空体占位——见 Task 10）
- 上传流程：鉴权（MANAGE）→ 校验扩展名/大小 → SHA-256 → `ObjectStorage.put` → `DocumentService.upload`（返回版本）→ `OutboxWriter.record("document_version", versionId, "document.version.uploaded", {...})` → 审计。
- 新增 `AuditRecorder`（audit 模块最小实现，见 Task 6 Step 5）。

- [ ] **Step 1: 写失败的 API 集成测试**

```java
package io.veridex.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

@AutoConfigureRestTestClient
@SpringBootTest(classes = io.veridex.VeridexApplication.class,
        properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@Import({MinioContainerConfiguration.class, RabbitContainerConfiguration.class})
class KnowledgeApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired RestTestClient rest;

    private void loginAs(String username) {
        rest.post().uri("/api/auth/login?username=" + username + "&password=veridex")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void createKnowledgeBaseThenUploadDocument() {
        loginAs("admin");

        var create = rest.post().uri("/api/knowledge-bases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"产品手册\",\"description\":\"产品文档\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isNotEmpty();
        String kbId = extractIdFromPath(create);

        var upload = rest.post().uri("/api/knowledge-bases/{kbId}/documents", kbId)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(part("intro.md", "# 产品介绍\n\n内容"))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UPLOADED");

        rest.get().uri("/api/knowledge-bases/{kbId}/documents", kbId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].filename").isEqualTo("intro.md");
    }

    @Test
    void employeeWithoutGrantCannotUpload() {
        // admin 建库（owner=admin），employee 无 MANAGE grant，上传被拒
        loginAs("admin");
        var create = rest.post().uri("/api/knowledge-bases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"受限库\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isNotEmpty();

        // 切换到 employee 会话
        loginAs("employee");
        rest.post().uri("/api/knowledge-bases/{kbId}/documents", create.returnResult().getResponseBodyAsString())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(part("x.md", "x"))
                .exchange()
                .expectStatus().isForbidden();
    }

    private static MockMultipartFile part(String filename, String content) {
        return new MockMultipartFile("file", filename, "text/markdown", content.getBytes());
    }

    private static String extractIdFromPath(org.springframework.test.web.servlet.MvcResult result) {
        return null; // 占位——实现时改为从 response 解析 id
    }
}
```

> **实现要点（实测修正，Spring Boot 4）：** 本测试改用 `RestTestClient` + `MockMultipartFile`（而非 `TestRestTemplate`/`ByteArrayResource`），原因同 Task 2：Boot 4 resttestclient 不自动管理 JSESSIONID。**同一个 `RestTestClient` 实例的连续请求共享 session**——`loginAs("admin")` 后再用 `loginAs("employee")` 会切换会话（新登录覆盖 session）。解析新建知识库 id 用 `MvcResult.getResponse().getContentAsString()` 提取 `id` 字段（删除上面 `extractIdFromPath` 占位方法，直接内联解析）。

- [ ] **Step 2: 运行并确认失败**

Run: `./mvnw -pl backend test -Dtest=KnowledgeApiIntegrationTest`
Expected: FAIL（编译失败：Controller/DTO 不存在）。

- [ ] **Step 3: 调整 `knowledge` 模块依赖（本任务将使用 audit）**

`backend/src/main/java/io/veridex/knowledge/package-info.java`：

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Knowledge",
        allowedDependencies = {"shared", "iam::api", "audit::api"}
)
package io.veridex.knowledge;
```

`backend/src/main/java/io/veridex/audit/api/package-info.java`（新建，声明 audit 的 api 命名接口）：

```java
@org.springframework.modulith.NamedInterface(name = "api")
package io.veridex.audit.api;
```

这是 Global Constraints 中记录的显式架构决策之一；`ArchitectureTest` 会在本任务验证。注意 Modulith 2.0 规则：跨模块引用必须经对方 `api` 子包的 `@NamedInterface`，依赖声明用 `"模块名::api"`。

- [ ] **Step 4: 生产 `MinioClient` bean 与 audit 最小实现**

```java
package io.veridex.shared.infrastructure.config;

import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InfrastructureBeans {

    @Bean
    @ConditionalOnMissingBean
    MinioClient minioClient(MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }
}
```

```java
package io.veridex.audit.application;

import java.util.Map;
import java.util.UUID;

public interface AuditRecorder {
    void record(UUID actorId, String action, String resourceType, UUID resourceId, String requestId, Map<String, Object> details);
}
```

```java
package io.veridex.audit.infrastructure;

import io.veridex.audit.application.AuditRecorder;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcAuditRecorder implements AuditRecorder {

    private final JdbcTemplate jdbc;

    public JdbcAuditRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(UUID actorId, String action, String resourceType, UUID resourceId,
                       String requestId, Map<String, Object> details) {
        jdbc.update("""
                INSERT INTO audit_event (actor_id, action, resource_type, resource_id, request_id, details)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, actorId, action, resourceType, resourceId, requestId, toJson(details));
    }

    private static String toJson(Map<String, Object> details) {
        try {
            return new tools.jackson.databind.json.JsonMapper().writeValueAsString(details);
        } catch (Exception e) {
            return "{}";
        }
    }
}
```

> 说明：`JdbcAuditRecorder` 直接写 `audit_event` 表（V1 已建）。`knowledge` 对 `audit` 的依赖已在 Step 3 调整，`ArchitectureTest` 将强制执行。

- [ ] **Step 5: 上传用例与 Controller**

```java
package io.veridex.knowledge.application;

import io.veridex.audit.application.AuditRecorder;
import io.veridex.knowledge.domain.DocumentVersion;
import io.veridex.shared.outbox.OutboxWriter;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DocumentUploadHandler {

    private final DocumentService documents;
    private final ObjectStorage storage;
    private final OutboxWriter outbox;
    private final AuditRecorder audit;

    public DocumentUploadHandler(DocumentService documents, ObjectStorage storage,
                                 OutboxWriter outbox, AuditRecorder audit) {
        this.documents = documents;
        this.storage = storage;
        this.outbox = outbox;
        this.audit = audit;
    }

    @Transactional
    public DocumentVersion upload(UUID actorId, UUID kbId, String filename, String contentType,
                                  byte[] content) throws Exception {
        // content 来自 MultipartFile.getBytes()（≤50MB，内存可容纳），保证 sha256 与 put 用同一份字节
        String sha256 = sha256Hex(content);
        DocumentVersion version = documents.upload(actorId, kbId, filename, contentType, content.length, sha256);
        storage.put(version.getObjectKey(),
                new java.io.ByteArrayInputStream(content), contentType, content.length);

        outbox.record("document_version", version.getId(), "document.version.uploaded",
                Map.of("documentVersionId", version.getId().toString(),
                        "knowledgeBaseId", kbId.toString(),
                        "objectKey", version.getObjectKey(),
                        "filename", filename,
                        "contentType", contentType));
        audit.record(actorId, "document.upload", "document_version", version.getId(), null,
                Map.of("knowledgeBaseId", kbId.toString(), "filename", filename));
        return version;
    }

    private static String sha256Hex(byte[] content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(content));
    }
}
```

> 注意：`DocumentVersion` 需暴露 `getObjectKey()`。Controller 层从 `MultipartFile` 取 `getBytes()` 后调用本方法（`DocumentUploadHandler` 不直接读流，避免双读问题）。

- [ ] **Step 6: 实现 Controller（知识库 + 文档）**

```java
package io.veridex.knowledge.api;

import io.veridex.knowledge.application.DocumentService;
import io.veridex.knowledge.application.DocumentUploadHandler;
import io.veridex.knowledge.application.KnowledgeBaseService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBases;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBases) {
        this.knowledgeBases = knowledgeBases;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateKnowledgeBaseRequest body) {
        var kb = knowledgeBases.createKnowledgeBase(CurrentActor.id(), body.name(), body.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(KnowledgeBaseView.from(kb));
    }

    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(knowledgeBases.listForView(CurrentActor.id(), CurrentActor.role()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable UUID id) {
        if (!knowledgeBases.canView(id, CurrentActor.id(), CurrentActor.role())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(KnowledgeBaseView.from(knowledgeBases.findByIdOrThrow(id)));
    }
}
```

（`DocumentController` 类似：`POST /{kbId}/documents` 处理 `MultipartFile`，校验扩展名/大小，`DocumentUploadHandler.upload`，返回 202；`GET /{kbId}/documents`、`GET /api/documents/{documentId}/versions`。`CurrentActor` 读取 `SecurityContextHolder` 返回当前 `UUID` 与 `Role`，放 `io.veridex.iam.application.CurrentActor`。）

- [ ] **Step 7: 运行测试**

Run:
```bash
./mvnw -pl backend test -Dtest=KnowledgeApiIntegrationTest
```
Expected: PASS。

- [ ] **Step 8: 提交**

```bash
git add backend/src/main/java/io/veridex/knowledge/package-info.java backend/src/main/java/io/veridex/knowledge/api backend/src/main/java/io/veridex/knowledge/application/DocumentUploadHandler.java backend/src/main/java/io/veridex/audit backend/src/main/java/io/veridex/iam/application/CurrentActor.java backend/src/main/java/io/veridex/shared/infrastructure/config/InfrastructureBeans.java backend/src/test/java/io/veridex/knowledge/KnowledgeApiIntegrationTest.java
git commit -m "feat: add knowledge base and document upload APIs"
```

---

### Task 7: 解析与结构优先分块

**Files:**
- Create: `backend/src/main/java/io/veridex/ingestion/domain/ParsedDocument.java`
- Create: `backend/src/main/java/io/veridex/ingestion/domain/Chunk.java`
- Create: `backend/src/main/java/io/veridex/ingestion/application/DocumentParser.java`
- Create: `backend/src/main/java/io/veridex/ingestion/application/StructureChunker.java`
- Create: `backend/src/main/java/io/veridex/ingestion/domain/StructureFirstChunker.java`
- Create: `backend/src/main/java/io/veridex/ingestion/infrastructure/TikaDocumentParser.java`
- Create: `backend/src/test/resources/sample/docs.md`
- Create: `backend/src/test/resources/sample/memo.txt`
- Create: `backend/src/test/java/io/veridex/ingestion/StructureFirstChunkerTest.java`
- Create: `backend/src/test/java/io/veridex/ingestion/TikaDocumentParserTest.java`

**Interfaces:**
- Consumes: 无外部依赖（纯域 + Tika）。
- Produces:
  - `DocumentParser.parse(InputStream content, String filename, String contentType)` → `ParsedDocument(text, sourceFilename, contentType)`
  - `StructureChunker.chunk(ParsedDocument doc)` → `List<Chunk>`；`Chunk(index, text, title, structurePath)`（record）
  - `StructureFirstChunker`：按 Markdown 标题（`#` 1-3 级）与纯文本标题启发式（短行、非句尾标点、连续编号模式）切分；超长块按 `maxChars=2000` 硬切，块间 `overlap=80`；`structurePath` 如 `1.2` 表示层级路径。
  - 约束：`chunkCount == chunk.size()`；每个 chunk 的 `text` 非空白。

- [ ] **Step 1: 创建测试资源**

`backend/src/test/resources/sample/docs.md`：

```markdown
# 入职指南

## 第一天

欢迎加入公司。请到 HR 领取工牌并完成入职培训。

## 请假制度

请假需提前一天在系统提交申请，由直属上级审批。

# 薪酬福利

## 薪资结构

薪酬由基本工资与绩效奖金构成，绩效按季度考核发放。

### 补充说明

加班补贴按小时计算，上限每月 36 小时。
```

`backend/src/test/resources/sample/memo.txt`：

```text
会议纪要 2026-08-05

主题：Q3 产品规划

1. 需求评审通过三个新功能
2. 研发排期从九月开始
3. 市场部负责对外发布材料

决议：下周召开启动会。
```

- [ ] **Step 2: 写失败的分块单元测试**

```java
package io.veridex.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.ingestion.application.StructureChunker;
import io.veridex.ingestion.domain.ParsedDocument;
import io.veridex.ingestion.domain.StructureFirstChunker;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StructureFirstChunkerTest {

    private final StructureChunker chunker = new StructureFirstChunker();

    private String load(String resource) throws Exception {
        return Files.readString(Path.of("src/test/resources/sample/" + resource), StandardCharsets.UTF_8);
    }

    @Test
    void markdownHeadingStructureProducesChunksWithPaths() throws Exception {
        var chunks = chunker.chunk(new ParsedDocument(load("docs.md"), "docs.md", "text/markdown"));
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(5);
        assertThat(chunks.get(0).title()).isEqualTo("入职指南");
        assertThat(chunks.get(0).structurePath()).isEqualTo("1");
        assertThat(chunks.stream().anyMatch(c -> c.title().equals("薪酬福利"))).isTrue();
        assertThat(chunks.stream().allMatch(c -> !c.text().isBlank())).isTrue();
    }

    @Test
    void plainTextWithoutHeadingsStillChunksByParagraph() throws Exception {
        var chunks = chunker.chunk(new ParsedDocument(load("memo.txt"), "memo.txt", "text/plain"));
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(1);
        assertThat(chunks.stream().allMatch(c -> !c.text().isBlank())).isTrue();
    }

    @Test
    void longBodyIsHardSplitUnderMaxChars() {
        String longText = "段落开始。" + "x".repeat(5000);
        var chunks = chunker.chunk(new ParsedDocument(longText, "long.txt", "text/plain"));
        assertThat(chunks.stream().allMatch(c -> c.text().length() <= 2000)).isTrue();
    }
}
```

- [ ] **Step 3: 运行并确认失败**

Run: `./mvnw -pl backend test -Dtest=StructureFirstChunkerTest`
Expected: FAIL（编译失败）。

- [ ] **Step 4: 写失败的解析器测试**

```java
package io.veridex.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.ingestion.application.DocumentParser;
import io.veridex.ingestion.infrastructure.TikaDocumentParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TikaDocumentParserTest {

    private final DocumentParser parser = new TikaDocumentParser();

    @Test
    void parsesMarkdownToText() {
        var text = "# 标题\n\n正文内容".getBytes(StandardCharsets.UTF_8);
        var parsed = parser.parse(new java.io.ByteArrayInputStream(text), "doc.md", "text/markdown");
        assertThat(parsed.text()).contains("正文内容");
    }

    @Test
    void parsesPlainText() {
        var parsed = parser.parse(new java.io.ByteArrayInputStream("纯文本".getBytes(StandardCharsets.UTF_8)),
                "memo.txt", "text/plain");
        assertThat(parsed.text()).contains("纯文本");
    }

    @Test
    void parsesDocxGeneratedByPoi() throws Exception {
        try (org.apache.poi.xwpf.usermodel.XWPFDocument docx = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            docx.createParagraph().createRun().setText("Word 文档正文");
            var bytes = new java.io.ByteArrayOutputStream();
            docx.write(bytes);
            var parsed = parser.parse(new java.io.ByteArrayInputStream(bytes.toByteArray()),
                    "letter.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            assertThat(parsed.text()).contains("Word 文档正文");
        }
    }
}
```

- [ ] **Step 5: 运行并确认失败**

Run: `./mvnw -pl backend test -Dtest=TikaDocumentParserTest`
Expected: FAIL（编译失败：类不存在）。

- [ ] **Step 6: 实现域类与端口**

```java
package io.veridex.ingestion.domain;

import java.util.Map;

public record ParsedDocument(String text, String sourceFilename, String contentType, Map<String, String> metadata) {

    public ParsedDocument(String text, String sourceFilename, String contentType) {
        this(text, sourceFilename, contentType, Map.of());
    }
}
```

```java
package io.veridex.ingestion.domain;

public record Chunk(int index, String text, String title, String structurePath) {
}
```

```java
package io.veridex.ingestion.application;

import io.veridex.ingestion.domain.ParsedDocument;
import java.io.InputStream;

public interface DocumentParser {
    ParsedDocument parse(InputStream content, String filename, String contentType);
}
```

```java
package io.veridex.ingestion.application;

import io.veridex.ingestion.domain.Chunk;
import io.veridex.ingestion.domain.ParsedDocument;
import java.util.List;

public interface StructureChunker {
    List<Chunk> chunk(ParsedDocument document);
}
```

```java
package io.veridex.ingestion.infrastructure;

import io.veridex.ingestion.application.DocumentParser;
import io.veridex.ingestion.domain.ParsedDocument;
import java.io.InputStream;
import java.util.Map;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

@Component
public class TikaDocumentParser implements DocumentParser {

    private final AutoDetectParser parser = new AutoDetectParser();

    @Override
    public ParsedDocument parse(InputStream content, String filename, String contentType) {
        try {
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            metadata.set(Metadata.RESOURCE_NAME_KEY, filename);
            if (contentType != null) {
                metadata.set(Metadata.CONTENT_TYPE, contentType);
            }
            parser.parse(content, handler, metadata, new ParseContext());
            return new ParsedDocument(handler.toString(), filename, contentType,
                    Map.of("pageCount", metadata.get("xmpTPg:NPages") == null ? "" : metadata.get("xmpTPg:NPages")));
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to parse document " + filename, e);
        }
    }
}
```

- [ ] **Step 7: 实现结构优先分块器**

```java
package io.veridex.ingestion.domain;

import io.veridex.ingestion.application.StructureChunker;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StructureFirstChunker implements StructureChunker {

    private static final int MAX_CHARS = 2000;
    private static final int OVERLAP = 80;

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,3})\\s+(.+)$");

    @Override
    public List<Chunk> chunk(ParsedDocument document) {
        List<Section> sections = new ArrayList<>();
        Section current = null;
        String[] lines = document.text().split("\n", -1);
        List<String> path = new ArrayList<>();

        for (String line : lines) {
            Matcher heading = MARKDOWN_HEADING.matcher(line);
            if (heading.matches()) {
                int level = heading.group(1).length();
                trimPath(path, level);
                String title = heading.group(2).trim();
                path.add(title);
                if (current != null && !current.body.isEmpty()) {
                    sections.add(current);
                }
                current = new Section(title, path.size());
                continue;
            }
            if (current == null) {
                current = new Section(document.sourceFilename(), 0);
            }
            current.body.add(line);
        }
        if (current != null && !current.body.isEmpty()) {
            sections.add(current);
        }

        List<Chunk> chunks = new ArrayList<>();
        int index = 0;
        for (Section section : sections) {
            String body = String.join("\n", section.body).trim();
            if (body.isBlank()) {
                continue;
            }
            for (String part : hardSplit(body)) {
                chunks.add(new Chunk(index++, part, section.title,
                        section.path == 0 ? String.valueOf(chunks.size() + 1) : String.valueOf(section.path)));
            }
        }
        return chunks;
    }

    private static void trimPath(List<String> path, int level) {
        while (path.size() > level) {
            path.remove(path.size() - 1);
        }
    }

    private static List<String> hardSplit(String body) {
        if (body.length() <= MAX_CHARS) {
            return List.of(body);
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < body.length()) {
            int end = Math.min(start + MAX_CHARS, body.length());
            parts.add(body.substring(start, end));
            start = end - OVERLAP;
        }
        return parts;
    }

    private record Section(String title, int path) {
        private final List<String> body = new ArrayList<>();
    }
}
```

> 说明：这是可运行的 v1 实现。纯文本段落走 section 聚合 + 硬切，Markdown 标题驱动层级路径；后续可增强纯文本标题启发式，保持接口稳定即可。

- [ ] **Step 8: 运行测试**

Run:
```bash
./mvnw -pl backend test -Dtest=StructureFirstChunkerTest,TikaDocumentParserTest
```
Expected: 全部通过。

- [ ] **Step 9: 提交**

```bash
git add backend/src/main/java/io/veridex/ingestion backend/src/test/java/io/veridex/ingestion backend/src/test/resources/sample
git commit -m "feat: add tika parsing and structure-first chunking"
```

---

### Task 8: OpenSearch 索引网关

**Files:**
- Create: `backend/src/main/java/io/veridex/indexing/api/ChunkRecord.java`（record `(int index, String text, String title, String structurePath)`——Task 9 复用）
- Create: `backend/src/main/java/io/veridex/indexing/application/SearchIndexGateway.java`
- Create: `backend/src/main/java/io/veridex/indexing/infrastructure/OpenSearchIndexGateway.java`
- Create: `backend/src/main/java/io/veridex/shared/infrastructure/config/OpenSearchClientConfig.java`（生产 `OpenSearchClient` bean，`@ConditionalOnMissingBean`）
- Create: `backend/src/main/java/io/veridex/shared/infrastructure/embedding/DeterministicEmbeddingModel.java`
- Create: `backend/src/test/java/io/veridex/support/OpenSearchContainerConfiguration.java`
- Create: `backend/src/test/java/io/veridex/indexing/OpenSearchIndexGatewayTest.java`

**Interfaces:**
- Consumes: `OpenSearchProperties`、`EmbeddingModel`（Spring AI）。
- Produces: `SearchIndexGateway`：
  - `void createIndex(String indexName, int dimensions)`（mapping：`text`（text+BM25）、`embedding`（knn_vector dims）、`document_version_id`、`knowledge_base_id`、`release_id`、`chunk_index`、`structure_path`、`title`）
  - `void deleteIndex(String indexName)`
  - `void aliasTo(String aliasName, String indexName)`（原子切换；旧 alias 先删再建）
  - `void removeAlias(String aliasName)`
  - `void indexChunks(String indexName, UUID knowledgeBaseId, UUID documentVersionId, UUID releaseId, List<ChunkRecord> chunks)`（`_id = documentVersionId:chunkIndex` 幂等；embedding 由 `EmbeddingModel.embed(text)` 生成）
  - `List<ChunkRecord> findChunksByDocumentVersion(String indexName, UUID documentVersionId)`（供预览；chunk 预览也可走 MinIO 存储，本接口用于验证写入）
  - `boolean indexExists(String indexName)`

- [ ] **Step 1: 写失败的网关测试**

```java
package io.veridex.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.indexing.api.ChunkRecord;
import io.veridex.indexing.application.SearchIndexGateway;
import io.veridex.shared.infrastructure.embedding.DeterministicEmbeddingModel;
import io.veridex.support.OpenSearchContainerConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(classes = io.veridex.VeridexApplication.class,
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
                "spring.rabbitmq.listener.simple.auto-startup=false"
        })
@Import({OpenSearchContainerConfiguration.class, DeterministicEmbeddingModel.class})
class OpenSearchIndexGatewayTest {

    @Autowired SearchIndexGateway gateway;
    @Autowired DeterministicEmbeddingModel embeddings;

    @Test
    void createAliasAndIndexChunksIdempotently() {
        String index = "veridex-test-release-1";
        String alias = "veridex-test-active";
        UUID kb = UUID.randomUUID();
        UUID docVersion = UUID.randomUUID();
        UUID release = UUID.randomUUID();

        gateway.deleteIndex(index);
        gateway.createIndex(index, 128);
        gateway.aliasTo(alias, index);

        var chunks = List.of(
                new ChunkRecord(0, "第一条内容", "标题A", "1"),
                new ChunkRecord(1, "第二条内容", "标题B", "1.1"));
        gateway.indexChunks(index, kb, docVersion, release, chunks);
        // 重复投递：再次写入相同 chunks，必须仍是 2 条
        gateway.indexChunks(index, kb, docVersion, release, chunks);

        var found = gateway.findChunksByDocumentVersion(index, docVersion);
        assertThat(found).hasSize(2);

        assertThat(gateway.indexExists(alias)).isTrue();
        gateway.deleteIndex(index);
    }

    @Test
    void deterministicEmbeddingHasFixedDimension() {
        assertThat(embeddings.dimensions()).isEqualTo(128);
        assertThat(embeddings.embed("测试文本")).hasSize(128);
    }
}
```

- [ ] **Step 2: 运行并确认失败**

Run: `./mvnw -pl backend test -Dtest=OpenSearchIndexGatewayTest`
Expected: FAIL（编译失败：类不存在）。

- [ ] **Step 3: 容器配置与客户端 bean**

```java
package io.veridex.support;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class OpenSearchContainerConfiguration {

    @Bean(destroyMethod = "stop")
    OpenSearchContainer<?> openSearchContainer() {
        return new OpenSearchContainer<>(DockerImageName.parse("opensearchproject/opensearch:3.2.0"))
                .withEnv("DISABLE_SECURITY_PLUGIN", "true");
    }

    @Bean
    OpenSearchClient openSearchClient(OpenSearchContainer<?> openSearchContainer) {
        var restClient = org.opensearch.client.RestClient.builder(
                        org.apache.http.HttpHost.create("http://" + openSearchContainer.getHttpHostAddress()))
                .build();
        return new OpenSearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
    }
}
```

```java
package io.veridex.shared.infrastructure.config;

import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenSearchClientConfig {

    @Bean
    @ConditionalOnMissingBean
    OpenSearchClient openSearchClient(OpenSearchProperties properties) {
        var restClient = org.opensearch.client.RestClient.builder(
                        properties.uris().stream()
                                .map(org.apache.http.HttpHost::create)
                                .toArray(org.apache.http.HttpHost[]::new))
                .build();
        return new OpenSearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
    }
}
```

- [ ] **Step 4: 实现网关**

```java
package io.veridex.indexing.application;

import io.veridex.indexing.api.ChunkRecord;
import java.util.List;
import java.util.UUID;

public interface SearchIndexGateway {
    void createIndex(String indexName, int dimensions);
    void deleteIndex(String indexName);
    void aliasTo(String aliasName, String indexName);
    void removeAlias(String aliasName);
    void indexChunks(String indexName, UUID knowledgeBaseId, UUID documentVersionId, UUID releaseId, List<ChunkRecord> chunks);
    List<ChunkRecord> findChunksByDocumentVersion(String indexName, UUID documentVersionId);
    boolean indexExists(String indexName);
}
```

```java
package io.veridex.indexing.infrastructure;

import io.veridex.indexing.api.ChunkRecord;
import io.veridex.indexing.application.SearchIndexGateway;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.opensearch.client.opensearch.indices.PutMappingRequest;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

@Component
public class OpenSearchIndexGateway implements SearchIndexGateway {

    private static final int BULK_BATCH = 50;

    private final OpenSearchClient client;
    private final EmbeddingModel embeddings;

    public OpenSearchIndexGateway(OpenSearchClient client, EmbeddingModel embeddings) {
        this.client = client;
        this.embeddings = embeddings;
    }

    @Override
    public void createIndex(String indexName, int dimensions) {
        try {
            client.indices().create(c -> c
                    .index(indexName)
                    .settings(new IndexSettings.Builder().numberOfShards("1").numberOfReplicas("0").build())
                    .mappings(new TypeMapping.Builder()
                            .properties(Map.of(
                                    "text", Property.of(p -> p.text(t -> t)),
                                    "embedding", Property.of(p -> p.knnVector(k -> k.dimension(dimensions))),
                                    "document_version_id", Property.of(p -> p.keyword(k -> k)),
                                    "knowledge_base_id", Property.of(p -> p.keyword(k -> k)),
                                    "release_id", Property.of(p -> p.keyword(k -> k)),
                                    "chunk_index", Property.of(p -> p.integer(i -> i)),
                                    "structure_path", Property.of(p -> p.keyword(k -> k)),
                                    "title", Property.of(p -> p.keyword(k -> k)))
                            .build())
                    .build());
        } catch (IOException e) {
            throw new RuntimeException("failed to create index " + indexName, e);
        }
    }

    @Override
    public void indexChunks(String indexName, UUID knowledgeBaseId, UUID documentVersionId, UUID releaseId, List<ChunkRecord> chunks) {
        for (int start = 0; start < chunks.size(); start += BULK_BATCH) {
            List<ChunkRecord> batch = chunks.subList(start, Math.min(start + BULK_BATCH, chunks.size()));
            var request = new org.opensearch.client.opensearch.core.BulkRequest.Builder();
            for (ChunkRecord chunk : batch) {
                String docId = documentVersionId + ":" + chunk.index();
                float[] vector = embeddings.embed(chunk.text());
                request.operations(op -> op.index(idx -> idx
                        .index(indexName).id(docId)
                        .document(Map.of(
                                "text", chunk.text(),
                                "embedding", vector,
                                "document_version_id", documentVersionId.toString(),
                                "knowledge_base_id", knowledgeBaseId.toString(),
                                "release_id", releaseId.toString(),
                                "chunk_index", chunk.index(),
                                "structure_path", chunk.structurePath(),
                                "title", chunk.title()))));
            }
            try {
                var response = client.bulk(request.build());
                if (response.errors()) {
                    throw new IllegalStateException("bulk index reported errors for " + indexName);
                }
            } catch (IOException e) {
                throw new RuntimeException("bulk index failed for " + indexName, e);
            }
        }
    }

    @Override
    public List<ChunkRecord> findChunksByDocumentVersion(String indexName, UUID documentVersionId) {
        try {
            var response = client.search(SearchRequest.of(s -> s
                    .index(indexName)
                    .query(q -> q.term(t -> t.field("document_version_id").value(documentVersionId.toString())))
                    .size(10_000)), Map.class);
            List<ChunkRecord> out = new ArrayList<>();
            for (var hit : response.hits().hits()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> src = (Map<String, Object>) hit.source();
                if (src == null) {
                    continue;
                }
                out.add(new ChunkRecord(((Number) src.get("chunk_index")).intValue(),
                        String.valueOf(src.get("text")),
                        String.valueOf(src.get("title")),
                        String.valueOf(src.get("structure_path"))));
            }
            out.sort(java.util.Comparator.comparingInt(ChunkRecord::index));
            return out;
        } catch (IOException e) {
            throw new RuntimeException("search failed on " + indexName, e);
        }
    }

    @Override
    public void aliasTo(String aliasName, String indexName) {
        try {
            if (indexExists(aliasName)) {
                client.indices().deleteAlias(d -> d.index("*").name(aliasName));
            }
            client.indices().putAlias(a -> a.index(indexName).name(aliasName));
        } catch (IOException e) {
            throw new RuntimeException("alias switch failed for " + aliasName, e);
        }
    }

    @Override
    public void removeAlias(String aliasName) {
        try {
            if (indexExists(aliasName)) {
                client.indices().deleteAlias(d -> d.index("*").name(aliasName));
            }
        } catch (IOException e) {
            throw new RuntimeException("alias removal failed for " + aliasName, e);
        }
    }

    @Override
    public boolean indexExists(String indexName) {
        try {
            return client.indices().exists(ExistsRequest.of(e -> e.index(indexName))).value();
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void deleteIndex(String indexName) {
        try {
            if (indexExists(indexName)) {
                client.indices().delete(d -> d.index(indexName));
            }
        } catch (IOException e) {
            throw new RuntimeException("index deletion failed for " + indexName, e);
        }
    }
}
```

（`ChunkRecord` 为 record：`(int index, String text, String title, String structurePath)`。）

- [ ] **Step 5: Deterministic Embedding 模型**

```java
package io.veridex.shared.infrastructure.embedding;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.stereotype.Component;

@Component
public class DeterministicEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSIONS = 128;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        var embeddings = request.getInstructions().stream()
                .map(text -> new Embedding(embed(text), 0))
                .toList();
        return new EmbeddingResponse(embeddings, EmbeddingResponseMetadata.EMPTY);
    }

    @Override
    public float[] embed(String text) {
        return deterministic(text);
    }

    @Override
    public float[] embed(Document document) {
        return deterministic(document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    private static float[] deterministic(String text) {
        float[] vector = new float[DIMENSIONS];
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            vector[i % DIMENSIONS] += ((bytes[i] & 0xff) - 128) / 128f;
        }
        return normalize(vector);
    }

    private static float[] normalize(float[] v) {
        double norm = 0;
        for (float x : v) {
            norm += (double) x * x;
        }
        if (norm == 0) {
            return v;
        }
        norm = Math.sqrt(norm);
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) (v[i] / norm);
        }
        return v;
    }
}
```

> 说明：这是本地可运行的确定性 embedding（128 维、归一化），用于开发与集成测试；生产环境按 `veridex.embedding.provider` 切换真实模型（后续阶段实现 OpenAI/本地 ONNX 适配器，本阶段不实现）。OpenSearch mapping 的 `dimension` 必须与此一致（`veridex.embedding.dimensions` 默认 128）。

- [ ] **Step 6: 运行测试**

Run: `./mvnw -pl backend test -Dtest=OpenSearchIndexGatewayTest`
Expected: PASS（注意：容器测试需 Docker，首次会拉 `opensearchproject/opensearch:3.2.0`）。

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/io/veridex/indexing backend/src/main/java/io/veridex/shared/infrastructure/embedding backend/src/main/java/io/veridex/shared/infrastructure/config/OpenSearchClientConfig.java backend/src/test/java/io/veridex/support/OpenSearchContainerConfiguration.java backend/src/test/java/io/veridex/indexing
git commit -m "feat: add OpenSearch index gateway with alias management"
```

---

### Task 9: 消费 Worker 与 IndexRelease 工作流

**Files:**
- Modify: `backend/src/main/java/io/veridex/ingestion/package-info.java`（`allowedDependencies` 增加 `indexing`）
- Create: `backend/src/main/resources/db/migration/V4__index_release.sql`
- Create: `backend/src/main/java/io/veridex/indexing/domain/IndexReleaseStatus.java`
- Create: `backend/src/main/java/io/veridex/indexing/domain/IndexRelease.java`
- Create: `backend/src/main/java/io/veridex/indexing/domain/IndexReleaseRepository.java`
- Create: `backend/src/main/java/io/veridex/indexing/application/IndexReleaseService.java`
- Create: `backend/src/main/java/io/veridex/indexing/application/ChunkIndexer.java`
- Create: `backend/src/main/java/io/veridex/indexing/infrastructure/ReleaseChunkIndexer.java`
- Create: `backend/src/main/java/io/veridex/ingestion/application/DocumentIngestionWorker.java`
- Create: `backend/src/test/java/io/veridex/ingestion/IngestionWorkerIntegrationTest.java`
- Create: `backend/src/test/java/io/veridex/indexing/IndexReleaseServiceTest.java`

**Interfaces:**
- Consumes: `ObjectStorage`、`DocumentParser`、`StructureChunker`、`SearchIndexGateway`、`EmbeddingModel`、`DocumentService`、`AuditRecorder`、`RabbitTopology`。
- Produces:
  - `IndexReleaseStatus`：`DRAFT, PUBLISHED, ROLLED_BACK, OFFLINE`
  - `IndexReleaseService`：
    - `IndexRelease createDraft(UUID knowledgeBaseId, UUID documentVersionId, String aliasName)`（版本号 = 该知识库已有 release 数 + 1；indexName = `{prefix}-{slug}-{releaseNo}`）
    - `IndexRelease publish(UUID releaseId)`：`SearchIndexGateway.createIndex`（幂等）→ `aliasTo(alias, index)` → 状态 `PUBLISHED`；旧 release 状态置 `ROLLED_BACK`（仅当它曾 PUBLISHED）
    - `IndexRelease rollback(UUID releaseId)`：仅允许在 `PUBLISHED` 时执行 → `aliasTo(alias, previousIndex)` 或移除 alias → 状态 `ROLLED_BACK`；被回滚后 alias 指向该知识库当前 `PUBLISHED` 的最新其他 release（若没有，移除 alias）
    - `IndexRelease offline(UUID releaseId)`：仅 `PUBLISHED` → 从 alias 摘除 → `OFFLINE`
    - `IndexRelease delete(UUID releaseId)`：删除 index（幂等）→ 状态保留 `DELETE` 语义用 `deleteIndex`
    - 辅助：`findCurrentPublished(knowledgeBaseId)`、`previousPublished(releaseId)`（按 publishedAt 倒序）
  - `ChunkIndexer.index(UUID knowledgeBaseId, UUID documentVersionId, List<ChunkRecord> chunks, String indexName, UUID releaseId)`：调 `SearchIndexGateway.indexChunks`
  - `DocumentIngestionWorker`：`@RabbitListener(queues = INGESTION_QUEUE)` 手动 ack：
    1. 解析消息（`documentVersionId`、`knowledgeBaseId`、`objectKey`、`filename`、`contentType`）
    2. `DocumentService.markProcessing(versionId)`（仅 UPLOADED 可转 PROCESSING，否则幂等返回）
    3. `ObjectStorage.get(objectKey)` → `DocumentParser.parse` → `StructureChunker.chunk`
    4. 把 parsed 全文与 chunks 序列化为 JSON 存回 `ObjectStorage`（key：`{objectKey}.parsed.json`、`{objectKey}.chunks.json`），`DocumentService.setParsedObjectKey`
    5. `IndexReleaseService`：若该版本尚无 release → `createDraft`；`publish(releaseId)`（内部先 `createIndex` + `indexChunks` + `aliasTo`）
    6. `DocumentService.markReady(versionId, chunkCount)`
    7. `AuditRecorder.record(... "ingestion.completed" ...)` + `channel.basicAck(deliveryTag, false)`
    8. 任何异常：`DocumentService.markFailed(versionId, reason)` + `channel.basicReject(deliveryTag, false)`（进 DLQ）；对幂等冲突（已 READY）直接 ack。

- [ ] **Step 1: 创建 V4 迁移（index_release 表）**

```sql
CREATE TABLE index_release (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    document_version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
    version_no INTEGER NOT NULL,
    index_name VARCHAR(300) NOT NULL,
    alias_name VARCHAR(300) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    UNIQUE (knowledge_base_id, version_no)
);
```

- [ ] **Step 2: 调整 `ingestion` 模块依赖（worker 将调用 indexing 端口）**

`backend/src/main/java/io/veridex/ingestion/package-info.java`：

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Ingestion",
        allowedDependencies = {"shared", "knowledge::api", "indexing::api"}
)
package io.veridex.ingestion;
```

`backend/src/main/java/io/veridex/indexing/api/package-info.java`（新建，声明 indexing 的 api 命名接口）：

```java
@org.springframework.modulith.NamedInterface(name = "api")
package io.veridex.indexing.api;
```

这是 Global Constraints 中记录的显式架构决策；`ArchitectureTest` 在本任务验证 `ingestion → indexing` 新规则。`ChunkIndexer`/`IndexReleaseService` 等供 ingestion 调用的类型放在 `indexing.api`（或由 `indexing.application` 实现、经 `api` 端口暴露）。

- [ ] **Step 3: 写失败的 IndexRelease 服务测试（域逻辑，用 mock gateway）**

```java
package io.veridex.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.veridex.indexing.application.IndexReleaseService;
import io.veridex.indexing.application.SearchIndexGateway;
import io.veridex.indexing.domain.IndexRelease;
import io.veridex.indexing.domain.IndexReleaseRepository;
import io.veridex.indexing.domain.IndexReleaseStatus;
import io.veridex.shared.infrastructure.config.OpenSearchProperties;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IndexReleaseServiceTest {

    @Mock IndexReleaseRepository releases;
    @Mock SearchIndexGateway gateway;
    @Mock OpenSearchProperties properties;
    @InjectMocks IndexReleaseService service;

    private static final UUID KB = UUID.randomUUID();
    private static final UUID DV = UUID.randomUUID();

    @Test
    void publishCreatesIndexAliasAndMarksPublished() {
        when(properties.dimensions()).thenReturn(128);
        when(releases.countByKnowledgeBaseId(KB)).thenReturn(0L);
        IndexRelease draft = service.createDraft(KB, DV, "prod-active");
        assertThat(draft.getStatus()).isEqualTo(IndexReleaseStatus.DRAFT);
        assertThat(draft.getIndexName()).isEqualTo("veridex-1");

        when(releases.findById(draft.getId())).thenReturn(Optional.of(draft));
        service.publish(draft.getId());

        verify(gateway).createIndex("veridex-1", 128);
        verify(gateway).aliasTo("prod-active", "veridex-1");
        assertThat(draft.getStatus()).isEqualTo(IndexReleaseStatus.PUBLISHED);
    }

    @Test
    void offlineRemovesAliasAndMarksOffline() {
        IndexRelease published = new IndexRelease(KB, DV, "veridex-2", "prod-active", IndexReleaseStatus.PUBLISHED);
        when(releases.findById(published.getId())).thenReturn(Optional.of(published));

        service.offline(published.getId());

        verify(gateway).removeAlias("prod-active");
        assertThat(published.getStatus()).isEqualTo(IndexReleaseStatus.OFFLINE);
    }
}
```

- [ ] **Step 4: 运行并确认失败**

Run: `./mvnw -pl backend test -Dtest=IndexReleaseServiceTest`
Expected: FAIL（编译失败）。

- [ ] **Step 5: 实现 IndexRelease 域与服务**

```java
package io.veridex.indexing.domain;

public enum IndexReleaseStatus { DRAFT, PUBLISHED, ROLLED_BACK, OFFLINE }
```

```java
package io.veridex.indexing.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "index_release")
public class IndexRelease {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "knowledge_base_id", nullable = false)
    private UUID knowledgeBaseId;

    @Column(name = "document_version_id", nullable = false)
    private UUID documentVersionId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "index_name", nullable = false, length = 300)
    private String indexName;

    @Column(name = "alias_name", nullable = false, length = 300)
    private String aliasName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private IndexReleaseStatus status = IndexReleaseStatus.DRAFT;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;

    protected IndexRelease() {
    }

    public IndexRelease(UUID knowledgeBaseId, UUID documentVersionId, int versionNo,
                        String indexName, String aliasName) {
        this.knowledgeBaseId = knowledgeBaseId;
        this.documentVersionId = documentVersionId;
        this.versionNo = versionNo;
        this.indexName = indexName;
        this.aliasName = aliasName;
    }

    public UUID getId() { return id; }
    public UUID getKnowledgeBaseId() { return knowledgeBaseId; }
    public UUID getDocumentVersionId() { return documentVersionId; }
    public int getVersionNo() { return versionNo; }
    public String getIndexName() { return indexName; }
    public String getAliasName() { return aliasName; }
    public IndexReleaseStatus getStatus() { return status; }
    public Instant getPublishedAt() { return publishedAt; }

    public void publish() {
        this.status = IndexReleaseStatus.PUBLISHED;
        this.publishedAt = Instant.now();
    }

    public void rollback() { this.status = IndexReleaseStatus.ROLLED_BACK; }
    public void offline() { this.status = IndexReleaseStatus.OFFLINE; }
}
```

```java
package io.veridex.indexing.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface IndexReleaseRepository extends CrudRepository<IndexRelease, UUID> {

    long countByKnowledgeBaseId(UUID knowledgeBaseId);

    Optional<IndexRelease> findByDocumentVersionId(UUID documentVersionId);

    List<IndexRelease> findByKnowledgeBaseIdAndStatusOrderByVersionNoDesc(
            UUID knowledgeBaseId, IndexReleaseStatus status);

    @Query("select r from IndexRelease r where r.knowledgeBaseId = :kb and r.status = 'PUBLISHED' and r.id <> :exclude order by r.publishedAt desc")
    List<IndexRelease> findOtherPublished(@Param("kb") UUID kb, @Param("exclude") UUID exclude);
}
```

```java
package io.veridex.indexing.application;

import io.veridex.indexing.domain.IndexRelease;
import io.veridex.indexing.domain.IndexReleaseRepository;
import io.veridex.indexing.domain.IndexReleaseStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class IndexReleaseService {

    private final IndexReleaseRepository releases;
    private final SearchIndexGateway gateway;
    private final int dimensions;

    public IndexReleaseService(IndexReleaseRepository releases, SearchIndexGateway gateway,
                               io.veridex.shared.infrastructure.config.OpenSearchProperties properties) {
        this.releases = releases;
        this.gateway = gateway;
        this.dimensions = properties.dimensions();
    }

    public IndexRelease createDraft(UUID knowledgeBaseId, UUID documentVersionId, String aliasName) {
        long next = releases.countByKnowledgeBaseId(knowledgeBaseId) + 1;
        String indexName = "veridex-" + next;
        IndexRelease release = new IndexRelease(knowledgeBaseId, documentVersionId, (int) next,
                indexName, aliasName);
        return releases.save(release);
    }

    public IndexRelease publish(UUID releaseId) {
        IndexRelease release = releases.findById(releaseId).orElseThrow();
        gateway.createIndex(release.getIndexName(), dimensions);
        gateway.aliasTo(release.getAliasName(), release.getIndexName());
        release.publish();
        return releases.save(release);
    }

    public IndexRelease rollback(UUID releaseId) {
        IndexRelease release = releases.findById(releaseId).orElseThrow();
        if (release.getStatus() != IndexReleaseStatus.PUBLISHED) {
            throw new IllegalStateException("only PUBLISHED release can be rolled back");
        }
        Optional<IndexRelease> previous = releases.findOtherPublished(release.getKnowledgeBaseId(), release.getId())
                .stream().findFirst();
        if (previous.isPresent()) {
            gateway.aliasTo(release.getAliasName(), previous.get().getIndexName());
        } else {
            gateway.removeAlias(release.getAliasName());
        }
        release.rollback();
        return releases.save(release);
    }

    public IndexRelease offline(UUID releaseId) {
        IndexRelease release = releases.findById(releaseId).orElseThrow();
        if (release.getStatus() != IndexReleaseStatus.PUBLISHED) {
            throw new IllegalStateException("only PUBLISHED release can be taken offline");
        }
        gateway.removeAlias(release.getAliasName());
        release.offline();
        return releases.save(release);
    }

    public void delete(UUID releaseId) {
        IndexRelease release = releases.findById(releaseId).orElseThrow();
        gateway.deleteIndex(release.getIndexName());
        releases.delete(release);
    }
}
```

- [ ] **Step 6: 写失败的 worker 端到端集成测试**

```java
package io.veridex.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.knowledge.application.DocumentService;
import io.veridex.knowledge.application.KnowledgeBaseService;
import io.veridex.knowledge.application.ObjectStorage;
import io.veridex.knowledge.domain.DocumentVersionStatus;
import io.veridex.support.PostgresContainerConfiguration;
import io.veridex.support.MinioContainerConfiguration;
import io.veridex.support.RabbitContainerConfiguration;
import io.veridex.support.OpenSearchContainerConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(classes = io.veridex.VeridexApplication.class)
@Import({PostgresContainerConfiguration.class, MinioContainerConfiguration.class,
        RabbitContainerConfiguration.class, OpenSearchContainerConfiguration.class})
class IngestionWorkerIntegrationTest {

    @Autowired DocumentService documents;
    @Autowired KnowledgeBaseService knowledgeBases;
    @Autowired ObjectStorage storage;
    @Autowired RabbitTemplate rabbit;

    @Test
    void uploadedDocumentIsProcessedToReadyAndIndexed() throws Exception {
        UUID actor = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var kb = knowledgeBases.createKnowledgeBase(actor, "集成测试库", null);
        var version = documents.upload(actor, kb.getId(), "guide.md", "text/markdown", 11L, "a".repeat(64));
        storage.put(version.getObjectKey(),
                new java.io.ByteArrayInputStream("# 指南\n\n正文内容".getBytes()),
                "text/markdown", 11L);

        String body = "{\"documentVersionId\":\"" + version.getId() + "\",\"knowledgeBaseId\":\""
                + kb.getId() + "\",\"objectKey\":\"" + version.getObjectKey()
                + "\",\"filename\":\"guide.md\",\"contentType\":\"text/markdown\"}";
        rabbit.convertAndSend("veridex.ingestion", "document.ingest", body.getBytes());

        awaitStatus(version.getId(), DocumentVersionStatus.READY);
    }

    private void awaitStatus(UUID versionId, DocumentVersionStatus expected) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            var version = documents.findVersion(versionId);
            if (version.getStatus() == expected) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("document version did not reach " + expected);
    }
}
```

- [ ] **Step 7: 运行并确认失败**

Run: `./mvnw -pl backend test -Dtest=IngestionWorkerIntegrationTest`
Expected: FAIL（编译失败：`DocumentIngestionWorker` 不存在）。

- [ ] **Step 8: 实现 Worker 与 ChunkIndexer**

```java
package io.veridex.indexing.application;

import io.veridex.indexing.api.ChunkRecord;
import java.util.List;
import java.util.UUID;

public interface ChunkIndexer {
    void index(UUID knowledgeBaseId, UUID documentVersionId, List<ChunkRecord> chunks,
               String indexName, UUID releaseId);
}
```

```java
package io.veridex.indexing.infrastructure;

import io.veridex.indexing.api.ChunkRecord;
import io.veridex.indexing.application.ChunkIndexer;
import io.veridex.indexing.application.SearchIndexGateway;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ReleaseChunkIndexer implements ChunkIndexer {

    private final SearchIndexGateway gateway;

    public ReleaseChunkIndexer(SearchIndexGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void index(UUID knowledgeBaseId, UUID documentVersionId, List<ChunkRecord> chunks,
                      String indexName, UUID releaseId) {
        gateway.indexChunks(indexName, knowledgeBaseId, documentVersionId, releaseId, chunks);
    }
}
```

```java
package io.veridex.ingestion.application;

import tools.jackson.databind.json.JsonMapper;
import io.veridex.audit.application.AuditRecorder;
import io.veridex.indexing.api.ChunkRecord;
import io.veridex.indexing.application.ChunkIndexer;
import io.veridex.indexing.application.IndexReleaseService;
import io.veridex.indexing.domain.IndexRelease;
import io.veridex.knowledge.application.DocumentService;
import io.veridex.knowledge.application.ObjectStorage;
import io.veridex.knowledge.domain.DocumentVersion;
import io.veridex.knowledge.domain.DocumentVersionStatus;
import io.veridex.ingestion.domain.Chunk;
import io.veridex.ingestion.domain.ParsedDocument;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import com.rabbitmq.client.Channel;

@Component
public class DocumentIngestionWorker {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionWorker.class);

    private final DocumentService documents;
    private final ObjectStorage storage;
    private final DocumentParser parser;
    private final StructureChunker chunker;
    private final IndexReleaseService releaseService;
    private final ChunkIndexer chunkIndexer;
    private final AuditRecorder audit;
    private final JsonMapper objectMapper;

    public DocumentIngestionWorker(DocumentService documents, ObjectStorage storage,
                                   DocumentParser parser, StructureChunker chunker,
                                   IndexReleaseService releaseService, ChunkIndexer chunkIndexer,
                                   AuditRecorder audit, JsonMapper objectMapper) {
        this.documents = documents;
        this.storage = storage;
        this.parser = parser;
        this.chunker = chunker;
        this.releaseService = releaseService;
        this.chunkIndexer = chunkIndexer;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = io.veridex.shared.infrastructure.messaging.RabbitTopology.INGESTION_QUEUE)
    public void onIngest(byte[] payload, Channel channel,
                         @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            Map<String, Object> message = objectMapper.readValue(payload, Map.class);
            UUID versionId = UUID.fromString(String.valueOf(message.get("documentVersionId")));
            UUID kbId = UUID.fromString(String.valueOf(message.get("knowledgeBaseId")));
            String objectKey = String.valueOf(message.get("objectKey"));
            String filename = String.valueOf(message.get("filename"));
            String contentType = String.valueOf(message.get("contentType"));

            DocumentVersion version = documents.findVersion(versionId);
            if (version.getStatus() == DocumentVersionStatus.READY) {
                // 幂等：已处理完成，直接确认
                channel.basicAck(deliveryTag, false);
                return;
            }
            if (version.getStatus() != DocumentVersionStatus.UPLOADED) {
                throw new IllegalStateException("unexpected status " + version.getStatus());
            }
            documents.markProcessing(versionId);

            ParsedDocument parsed;
            try (var in = storage.get(objectKey)) {
                parsed = parser.parse(in, filename, contentType);
            }
            List<Chunk> chunks = chunker.chunk(parsed);
            List<ChunkRecord> records = chunks.stream()
                    .map(c -> new ChunkRecord(c.index(), c.text(), c.title(), c.structurePath()))
                    .toList();

            storage.put(objectKey + ".parsed.json",
                    new java.io.ByteArrayInputStream(objectMapper.writeValueAsBytes(parsed.text())),
                    "application/json", objectMapper.writeValueAsBytes(parsed.text()).length);
            storage.put(objectKey + ".chunks.json",
                    new java.io.ByteArrayInputStream(objectMapper.writeValueAsBytes(records)),
                    "application/json", objectMapper.writeValueAsBytes(records).length);
            documents.setParsedObjectKey(versionId, objectKey + ".parsed.json");

            IndexRelease release = releaseService.createDraft(kbId, versionId,
                    releaseAlias(kbId));
            chunkIndexer.index(kbId, versionId, records, release.getIndexName(), release.getId());
            releaseService.publish(release.getId());

            documents.markReady(versionId, chunks.size());
            audit.record(null, "ingestion.completed", "document_version", versionId, null,
                    Map.of("chunkCount", chunks.size(), "knowledgeBaseId", kbId.toString()));

            channel.basicAck(deliveryTag, false);
            log.info("ingestion completed for version {}", versionId);
        } catch (Exception e) {
            log.error("ingestion failed for message", e);
            try {
                channel.basicReject(deliveryTag, false);
            } catch (Exception reject) {
                log.error("failed to reject message", reject);
            }
        }
    }

    private static String releaseAlias(UUID kbId) {
        return "veridex-" + kbId + "-active";
    }
}
```

> 说明：`releaseAlias` 用 `kbId` 保证全局唯一（`veridex-{kbId}-active`），避免 slug 碰撞。`markProcessing` 需校验状态转换（UPLOADED→PROCESSING 才允许，否则抛 IllegalStateException 或幂等返回）；`markReady/markFailed` 只在 PROCESSING/READY 之间安全转换。worker 的失败会 `basicReject(requeue=false)` → 进 DLQ，满足「重试/死信」要求；重启续传由 RabbitMQ 未 ack 消息重投 + chunk `_id` 幂等保证。

- [ ] **Step 9: 运行全部相关测试**

Run:
```bash
./mvnw -pl backend test -Dtest=IndexReleaseServiceTest,IngestionWorkerIntegrationTest,ArchitectureTest,KnowledgeApiIntegrationTest,OutboxPublishingIntegrationTest
./mvnw -pl backend test
```
Expected: 全部通过。`ArchitectureTest` 验证新的 `ingestion → indexing`、`knowledge → audit` 依赖规则被强制执行。

- [ ] **Step 10: 提交**

```bash
git add backend/src/main/resources/db/migration/V4__index_release.sql backend/src/main/java/io/veridex/ingestion/package-info.java backend/src/main/java/io/veridex/knowledge/package-info.java backend/src/main/java/io/veridex/indexing backend/src/main/java/io/veridex/ingestion/application/DocumentIngestionWorker.java backend/src/test/java/io/veridex/indexing backend/src/test/java/io/veridex/ingestion/IngestionWorkerIntegrationTest.java
git commit -m "feat: process documents end-to-end with index release workflow"
```

---

### Task 10: 预览 API 与前端知识管理

**Files:**
- Create: `backend/src/main/java/io/veridex/knowledge/api/PreviewController.java`
- Create: `web/src/features/auth/authApi.ts`
- Create: `web/src/features/auth/LoginPage.tsx`
- Create: `web/src/features/knowledge/knowledgeApi.ts`
- Create: `web/src/features/knowledge/KnowledgePage.tsx`
- Create: `web/src/features/knowledge/components/UploadForm.tsx`
- Create: `web/src/features/knowledge/components/VersionList.tsx`
- Create: `web/src/features/knowledge/KnowledgePage.test.tsx`
- Modify: `web/src/app/routes.tsx`
- Modify: `web/src/app/App.tsx`

**Interfaces:**
- Consumes: `ObjectStorage`、`DocumentService`、`IndexReleaseService`（读 release 状态）。
- Produces:
  - `GET /api/documents/{documentId}/versions/{versionId}/parsed` → 200 纯文本（读 `parsed_object_key` 对象）
  - `GET /api/documents/{documentId}/versions/{versionId}/chunks` → 200 `List<ChunkRecord>`（读 `{objectKey}.chunks.json`）
  - `POST /api/knowledge-bases/{kbId}/releases/{releaseId}/publish|rollback|offline|delete` → 204（indexing 侧，新增到 `IndexReleaseController`）
  - 前端 `/knowledge` 页：知识库列表 + 新建；选中知识库 → 上传表单 + 文档列表 + 版本列表（状态徽章、parsed/chunk 预览、发布/回滚/离线按钮）
  - `/login` 页：用户名/密码表单（`authApi.login` 用 `fetch` + `credentials: 'include'`）
  - `App`：未登录（`/api/auth/me` 401）跳 `/login`；已登录恢复原路由

- [ ] **Step 1: 后端预览 API**

```java
package io.veridex.knowledge.api;

import io.veridex.knowledge.application.DocumentService;
import io.veridex.knowledge.application.ObjectStorage;
import io.veridex.knowledge.domain.DocumentVersion;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents/{documentId}/versions/{versionId}")
public class PreviewController {

    private final DocumentService documents;
    private final ObjectStorage storage;

    public PreviewController(DocumentService documents, ObjectStorage storage) {
        this.documents = documents;
        this.storage = storage;
    }

    @GetMapping(value = "/parsed", produces = "text/plain;charset=UTF-8")
    public String parsed(@PathVariable UUID documentId, @PathVariable UUID versionId) throws Exception {
        DocumentVersion version = documents.findVersion(versionId);
        try (var in = storage.get(version.getParsedObjectKey())) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @GetMapping("/chunks")
    public List<?> chunks(@PathVariable UUID documentId, @PathVariable UUID versionId) {
        DocumentVersion version = documents.findVersion(versionId);
        return documents.readChunksPreview(versionId);
    }
}
```

（`DocumentService.readChunksPreview(versionId)` 从 `{objectKey}.chunks.json` 读取并反序列化为 `List<Map<String,Object>>`；`getParsedObjectKey()` 为 null 时返回 404。）

- [ ] **Step 2: 前端 API 封装**

`web/src/features/knowledge/knowledgeApi.ts`：

```ts
export type KnowledgeBase = { id: string; name: string; description: string | null; slug: string }
export type DocumentSummary = { id: string; filename: string; contentType: string; sizeBytes: number }
export type DocumentVersion = {
  id: string; versionNo: number; status: string; chunkCount: number
  errorMessage: string | null; processedAt: string | null; releaseId: string | null
}
export type ChunkPreview = { index: number; text: string; title: string; structurePath: string }

const json = (r: Response) => {
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.status === 204 ? null : r.json()
}

export const knowledgeApi = {
  list: (): Promise<KnowledgeBase[]> => fetch('/api/knowledge-bases', { credentials: 'include' }).then(json),
  create: (name: string, description?: string): Promise<KnowledgeBase> =>
    fetch('/api/knowledge-bases', {
      method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, description }),
    }).then(json),
  documents: (kbId: string): Promise<DocumentSummary[]> =>
    fetch(`/api/knowledge-bases/${kbId}/documents`, { credentials: 'include' }).then(json),
  versions: (documentId: string): Promise<DocumentVersion[]> =>
    fetch(`/api/documents/${documentId}/versions`, { credentials: 'include' }).then(json),
  upload: (kbId: string, file: File): Promise<DocumentVersion> => {
    const form = new FormData()
    form.append('file', file)
    return fetch(`/api/knowledge-bases/${kbId}/documents`, {
      method: 'POST', credentials: 'include', body: form,
    }).then(json)
  },
  parsed: (documentId: string, versionId: string): Promise<string> =>
    fetch(`/api/documents/${documentId}/versions/${versionId}/parsed`, { credentials: 'include' }).then((r) => r.text()),
  chunks: (documentId: string, versionId: string): Promise<ChunkPreview[]> =>
    fetch(`/api/documents/${documentId}/versions/${versionId}/chunks`, { credentials: 'include' }).then(json),
  release: (kbId: string, releaseId: string, action: 'publish' | 'rollback' | 'offline' | 'delete'): Promise<null> =>
    fetch(`/api/knowledge-bases/${kbId}/releases/${releaseId}/${action}`, { method: 'POST', credentials: 'include' }).then(json),
}
```

`web/src/features/auth/authApi.ts`：

```ts
export type CurrentUser = { id: string; username: string; displayName: string; role: string }

export const authApi = {
  login: (username: string, password: string): Promise<CurrentUser> =>
    fetch(`/api/auth/login?username=${encodeURIComponent(username)}&password=${encodeURIComponent(password)}`, {
      method: 'POST', credentials: 'include',
    }).then((r) => { if (!r.ok) throw new Error('登录失败'); return r.json() }),
  me: (): Promise<CurrentUser> =>
    fetch('/api/auth/me', { credentials: 'include' }).then((r) => { if (!r.ok) throw new Error('未登录'); return r.json() }),
  logout: (): Promise<void> =>
    fetch('/api/auth/logout', { method: 'POST', credentials: 'include' }).then(() => undefined),
}
```

> 注意：登录接口目前是表单登录（返回 200 空体），`authApi.login` 期望 JSON。后端需让 `login` 成功处理器返回当前用户 JSON（改 `SecurityConfig` 的 `successHandler`：写 `{"id":...,"username":...}`）；计划以返回 JSON 为准。

- [ ] **Step 3: 登录页与 App 守卫**

`web/src/features/auth/LoginPage.tsx`：受控表单（username/password），提交调 `authApi.login`，成功后 `window.location.href = '/knowledge'`；错误展示提示。

`web/src/app/App.tsx`：挂载时 `authApi.me()` 判定登录态，未登录渲染 `<Navigate to="/login" />`；已登录正常渲染工作区。

- [ ] **Step 4: 知识管理页**

`web/src/features/knowledge/KnowledgePage.tsx`：左侧知识库列表 + 新建按钮；右侧选中库：`UploadForm`（`<input type="file" accept=".pdf,.docx,.txt,.md">` → `knowledgeApi.upload`）+ `文档列表`（点击展开版本列表 `VersionList`：状态徽章、`parsed` 预览 `<pre>`、`chunks` 表格、发布/回滚/离线按钮调 `knowledgeApi.release`）。

- [ ] **Step 5: 前端测试（KnowledgePage）**

`web/src/features/knowledge/KnowledgePage.test.tsx`：

```tsx
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { KnowledgePage } from './KnowledgePage'

const fetchMock = vi.fn()
vi.stubGlobal('fetch', fetchMock)

describe('KnowledgePage', () => {
  it('renders knowledge bases and create flow', async () => {
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify([{ id: 'kb-1', name: '人事制度', description: null, slug: 'hr' }]), { status: 200 }))
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify([{ id: 'kb-1', name: '人事制度', description: null, slug: 'hr' }]), { status: 200 }))

    render(<KnowledgePage />)
    await waitFor(() => expect(screen.getByText('人事制度')).toBeInTheDocument())
    expect(fetchMock).toHaveBeenCalledWith('/api/knowledge-bases', expect.anything())
  })
})
```

- [ ] **Step 6: 更新路由**

`web/src/app/routes.tsx` 的 `/knowledge` 从 `<h1>知识管理</h1>` 改为 `<KnowledgePage />`（import 并渲染）；新增 `/login` 路由（LoginPage）。

- [ ] **Step 7: 运行前后端测试**

Run:
```bash
./mvnw -pl backend test -Dtest=PreviewApiSmokeTest  # 或并入 KnowledgeApiIntegrationTest
cd web && npm test && npm run build
```
Expected: 后端测试通过、Vitest 通过、`tsc -b && vite build` 成功。

- [ ] **Step 8: 提交**

```bash
git add backend/src/main/java/io/veridex/knowledge/api/PreviewController.java backend/src/main/java/io/veridex/indexing/api/IndexReleaseController.java web/src/features web/src/app/routes.tsx web/src/app/App.tsx
git commit -m "feat: add preview APIs and knowledge management console page"
```

---

### Task 11: 出口门禁（Exit Gate 场景测试）

**Files:**
- Create: `backend/src/test/java/io/veridex/ingestion/IngestionExitGateTest.java`
- Modify: `scripts/verify.sh`（无改动；确认它已覆盖全部——如需并行前端/后端可保持不变）
- Test: 全部后端 + 前端 + verify.sh

**Interfaces:**
- Consumes: 全部 Phase 2 组件。
- Produces: 四个退出门禁场景的端到端测试（全容器：Postgres + MinIO + RabbitMQ + OpenSearch）：

- [ ] **Step 1: 写四个退出场景测试**

```java
package io.veridex.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.indexing.application.SearchIndexGateway;
import io.veridex.knowledge.application.DocumentService;
import io.veridex.knowledge.application.KnowledgeBaseService;
import io.veridex.knowledge.application.ObjectStorage;
import io.veridex.knowledge.domain.DocumentVersion;
import io.veridex.knowledge.domain.DocumentVersionStatus;
import io.veridex.support.PostgresContainerConfiguration;
import io.veridex.support.MinioContainerConfiguration;
import io.veridex.support.RabbitContainerConfiguration;
import io.veridex.support.OpenSearchContainerConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(classes = io.veridex.VeridexApplication.class)
@Import({PostgresContainerConfiguration.class, MinioContainerConfiguration.class,
        RabbitContainerConfiguration.class, OpenSearchContainerConfiguration.class})
class IngestionExitGateTest {

    @Autowired DocumentService documents;
    @Autowired KnowledgeBaseService knowledgeBases;
    @Autowired ObjectStorage storage;
    @Autowired RabbitTemplate rabbit;
    @Autowired SearchIndexGateway gateway;

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private record Uploaded(UUID kbId, DocumentVersion version) {
    }

    @Test
    void duplicateDeliveryDoesNotDuplicateChunks() throws Exception {
        Uploaded u = uploadAndPut("dup.md", "# 重复\n\n内容");
        String body = messageBody(u);

        rabbit.convertAndSend("veridex.ingestion", "document.ingest", body.getBytes(StandardCharsets.UTF_8));
        rabbit.convertAndSend("veridex.ingestion", "document.ingest", body.getBytes(StandardCharsets.UTF_8));

        awaitReady(u.version().getId());
        // worker 幂等：第二次投递不会重复建 release/索引，chunk 数不变
        assertThat(documents.findVersion(u.version().getId()).getChunkCount()).isEqualTo(1);
    }

    @Test
    void failedNewVersionLeavesOldReleaseQueryable() throws Exception {
        Uploaded v1 = uploadAndPut("a.md", "# 第一版\n\n稳定内容");
        publishMessage(v1);
        awaitReady(v1.version().getId());

        // v2 处理失败：损坏的二进制内容
        Uploaded v2 = uploadVersion("b.md");
        storage.put(v2.version().getObjectKey(),
                new java.io.ByteArrayInputStream(new byte[]{(byte) 0xff, 0x00, 0x01, 0x02}),
                "text/markdown", 4L);
        publishMessage(v2);
        awaitStatus(v2.version().getId(), DocumentVersionStatus.FAILED);

        // 旧 alias 仍指向 v1 的 index，可检索
        String alias = "veridex-" + v1.kbId() + "-active";
        assertThat(gateway.findChunksByDocumentVersion(alias, v1.version().getId())).isNotEmpty();
    }

    @Test
    void offlineDocumentIsExcludedFromSearch() throws Exception {
        Uploaded u = uploadAndPut("off.md", "# 离线\n\n敏感内容");
        publishMessage(u);
        awaitReady(u.version().getId());

        String alias = "veridex-" + u.kbId() + "-active";
        assertThat(gateway.findChunksByDocumentVersion(alias, u.version().getId())).isNotEmpty();

        documents.offline(u.version().getId()); // 内部经 IndexReleaseService.offline 移除 alias

        assertThat(gateway.findChunksByDocumentVersion(alias, u.version().getId())).isEmpty();
    }

    private Uploaded uploadAndPut(String filename, String content) throws Exception {
        Uploaded u = uploadVersion(filename);
        storage.put(u.version().getObjectKey(),
                new java.io.ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                "text/markdown", content.getBytes(StandardCharsets.UTF_8).length);
        return u;
    }

    private Uploaded uploadVersion(String filename) {
        UUID kb = knowledgeBases.createKnowledgeBase(ACTOR, "门禁-" + UUID.randomUUID(), null).getId();
        DocumentVersion version = documents.upload(ACTOR, kb, filename, "text/markdown",
                filename.length(), "a".repeat(64));
        return new Uploaded(kb, version);
    }

    private String messageBody(Uploaded u) {
        return "{\"documentVersionId\":\"" + u.version().getId() + "\",\"knowledgeBaseId\":\"" + u.kbId()
                + "\",\"objectKey\":\"" + u.version().getObjectKey()
                + "\",\"filename\":\"x.md\",\"contentType\":\"text/markdown\"}";
    }

    private void publishMessage(Uploaded u) {
        rabbit.convertAndSend("veridex.ingestion", "document.ingest",
                messageBody(u).getBytes(StandardCharsets.UTF_8));
    }

    private void awaitReady(UUID versionId) throws InterruptedException {
        awaitStatus(versionId, DocumentVersionStatus.READY);
    }

    private void awaitStatus(UUID versionId, DocumentVersionStatus expected) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            if (documents.findVersion(versionId).getStatus() == expected) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("version " + versionId + " not " + expected);
    }
}
```

> 说明：`documents.offline(versionId)` 需在 `DocumentService` 补充（找到该版本对应的 `IndexRelease` 并调 `IndexReleaseService.offline`，同时将版本状态置 `OFFLINE`）。损坏二进制使 Tika 解析抛异常 → worker 捕获 → `markFailed` + `basicReject` 进 DLQ，验证「失败的新版本不影响旧 release」。

- [ ] **Step 2: 运行全部测试 + 一键门禁**

Run:
```bash
./mvnw -pl backend test
./scripts/verify.sh
```
Expected: 全部通过；`verify.sh` 输出 `==> verify.sh: all checks passed`。

- [ ] **Step 3: 补充 `DocumentService.offline`/`markFailed` 状态转换（如未在前置任务实现）**

确保状态机只允许合法转换（UPLOADED→PROCESSING→READY|FAILED；READY↔OFFLINE 经 release 工作流），并补状态转换单元测试。

- [ ] **Step 4: 更新路线图勾选与提交**

更新 `docs/superpowers/plans/2026-08-09-enterprise-rag-delivery-roadmap.md` 中 Phase 2 的退出门禁描述为已满足（可选：在 Phase 2 标题旁标注 `✅ 2026-08-11`）。

```bash
git add backend/src/test/java/io/veridex/ingestion/IngestionExitGateTest.java backend/src/main/java/io/veridex/knowledge/application/DocumentService.java docs/superpowers/plans/2026-08-09-enterprise-rag-delivery-roadmap.md
git commit -m "test: verify phase 2 exit gate scenarios"
```

- [ ] **Step 5: 最终清洁树验证**

Run:
```bash
./scripts/verify.sh
git status --short
```
Expected: 验证通过；工作树除 `.codetui/`（已忽略）外干净。**注意：** 根目录误生成的 `package-lock.json` 应在此前删除（见会话历史）或确认忽略。

---

## Phase 2 完成标准（对应路线图 Exit Gate）

- [ ] 重复消息投递不产生重复 chunk（Task 9 幂等 `_id` + Task 11 场景 1）
- [ ] worker 重启后续传（未 ack 消息重投 + 幂等写入；Task 9 手动 ack + Task 11）
- [ ] 失败的新版本不影响旧 release 查询（Task 11 场景 2）
- [ ] 离线文档立即从检索中排除（Task 11 场景 3）
- [ ] 管理员可建知识库、上传、观察处理、预览 parsed/chunk、发布/回滚/离线/删除（Task 6/9/10）
- [ ] `./mvnw clean verify` 与 `./scripts/verify.sh` 全绿，`ArchitectureTest` 验证新的模块依赖规则

## 阶段收尾：Phase 3 规划提示

Phase 2 完成后，按路线图「Planning Rule for Later Phases」，基于**当时实际存在的文件与依赖版本**撰写 Phase 3（授权 RAG 查询垂直切片）计划；不要针对假设的 API 规划。Phase 3 将复用 `IndexRelease`/alias 语义做 `retrieval` 模块的 ACL 过滤检索与 citation 校验。
