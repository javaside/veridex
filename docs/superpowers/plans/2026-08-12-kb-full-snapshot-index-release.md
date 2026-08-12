# 知识库全量索引发布 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把索引发布从「每文档版本一个 release」改为「知识库全量快照」，增加显式「当前检索」active 标记，并迁移现有数据（不重建索引、不断服务）。

**Architecture:** 发布触发点从 ingestion worker 移到显式的手动发布 API（知识管理员点击「发布」）；`IndexRelease` 不再绑定单个 `documentVersionId`，新增 `index_release_document` 快照清单表记录快照包含的文档版本；`is_active` 列标记当前生效快照。worker 处理完成只写解析产物与 chunk 清单到 MinIO 并 `markReady`，不再自动建索引/切 alias。

**Tech Stack:** Spring Boot 4（Spring Modulith）、JPA/Hibernate、Flyway、OpenSearch Java Client、React 19 + TypeScript、Vitest + Testing Library。

## Global Constraints

- 迁移 SQL 必须幂等、可重复执行；迁移失败回滚代码 + SQL，旧逻辑照常。
- 发布快照 = 当前全部 READY 文档版本（每文档取最新 READY 版本）；PROCESSING/FAILED 不进入快照，响应带 `excludedCount` 提示。
- 当前 active 快照不可直接删除，须先离线/回滚。
- `publish / rollback / offline` 必须同步更新 `is_active`（DB 事实源，不依赖运行时查 OpenSearch）。
- 本计划只改索引发布语义层，不涉及 Phase 3 检索查询代码。
- 前端文案禁止 em dash / en dash 字符。
- 可见文案均为中文（沿用现有 UI 语言）。

---

### Task 1: V5 迁移 SQL 与迁移测试

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__kb_full_snapshot_index_release.sql`
- Modify: `backend/src/test/java/io/veridex/support/DatabaseMigrationTest.java`

**Interfaces:**
- Produces: 迁移 V5 建 `index_release_document` 表；`index_release` 加 `document_count`、`chunk_count`、`is_active` 列并回填；删除 `document_version_id` 列；按知识库把 version_no 最大的 PUBLISHED release 置 `is_active=true`。

- [ ] **Step 1: 写迁移 SQL**

```sql
-- 知识库全量快照发布：快照清单表 + index_release 语义列
CREATE TABLE index_release_document (
    release_id UUID NOT NULL REFERENCES index_release(id) ON DELETE CASCADE,
    document_version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
    PRIMARY KEY (release_id, document_version_id)
);

ALTER TABLE index_release
    ADD COLUMN document_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN chunk_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT false;

-- 回填：既有 release 各绑定单个文档版本（如实记录，不虚构）
INSERT INTO index_release_document (release_id, document_version_id)
    SELECT id, document_version_id FROM index_release WHERE document_version_id IS NOT NULL;

UPDATE index_release r
    SET document_count = 1,
        chunk_count = COALESCE((SELECT v.chunk_count FROM document_version v WHERE v.id = r.document_version_id), 0);

-- 每个知识库 version_no 最大的 PUBLISHED release 即当前 alias 指向（旧实现 publish 总是切到最新）
UPDATE index_release r
    SET is_active = true
    WHERE r.status = 'PUBLISHED'
      AND r.version_no = (SELECT MAX(r2.version_no) FROM index_release r2
                          WHERE r2.knowledge_base_id = r.knowledge_base_id
                            AND r2.status = 'PUBLISHED');

ALTER TABLE index_release DROP COLUMN document_version_id;
```

- [ ] **Step 2: 写迁移断言测试**

在 `DatabaseMigrationTest` 中新增测试（并更新既有版本断言）：

```java
@Test
void fullSnapshotReleaseMigrationAddsColumnsAndBackfillsActiveFlag() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
        var versions = new java.util.ArrayList<String>();
        try (var statement = connection.prepareStatement("""
                SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank
                """); var rows = statement.executeQuery()) {
            while (rows.next()) versions.add(rows.getString("version"));
        }
        assertThat(versions).contains("1", "2", "3", "4", "5");

        var releaseColumns = columns(connection);
        assertColumn(releaseColumns, "index_release.document_count", "integer", null, false, "0");
        assertColumn(releaseColumns, "index_release.chunk_count", "integer", null, false, "0");
        assertColumn(releaseColumns, "index_release.is_active", "boolean", null, false, "false");
        assertThat(releaseColumns).as("dropped document_version_id").doesNotContainKey("index_release.document_version_id");

        try (var statement = connection.prepareStatement("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'index_release_document'
                """); var rows = statement.executeQuery()) {
            assertThat(rows.next()).as("index_release_document table").isTrue();
        }
    }
}
```

注意 `columns(connection)` 目前只查 `installation/outbox_event/audit_event` 三表，需把 `index_release` 加入其 `table_name IN (...)` 列表；`assertColumn` 中 boolean 默认值断言传 `"false"`。

- [ ] **Step 3: 运行迁移测试确认通过**

```bash
cd backend && ./mvnw -q test -Dtest=DatabaseMigrationTest
```
Expected: PASS（V1..V5 全部成功，断言通过）。

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/resources/db/migration/V5__kb_full_snapshot_index_release.sql backend/src/test/java/io/veridex/support/DatabaseMigrationTest.java
git commit -m "feat: migrate index release to knowledge base snapshots"
```

---

### Task 2: 发布领域模型与服务生命周期

**Files:**
- Modify: `backend/src/main/java/io/veridex/indexing/domain/IndexRelease.java`
- Create: `backend/src/main/java/io/veridex/indexing/domain/IndexReleaseDocument.java`
- Create: `backend/src/main/java/io/veridex/indexing/domain/IndexReleaseDocumentId.java`
- Create: `backend/src/main/java/io/veridex/indexing/domain/IndexReleaseDocumentRepository.java`
- Modify: `backend/src/main/java/io/veridex/indexing/domain/IndexReleaseRepository.java`
- Modify: `backend/src/main/java/io/veridex/indexing/api/ReleaseView.java`
- Modify: `backend/src/main/java/io/veridex/indexing/api/IndexReleaseManager.java`
- Modify: `backend/src/main/java/io/veridex/indexing/api/DraftRelease.java`
- Modify: `backend/src/main/java/io/veridex/indexing/infrastructure/IndexReleaseService.java`
- Test: `backend/src/test/java/io/veridex/indexing/IndexReleaseServiceTest.java`

**Interfaces:**
- Consumes: `SearchIndexGateway`（`aliasTo/removeAlias/deleteIndex`）、`IndexReleaseRepository`、`OpenSearchProperties`
- Produces:
  - `IndexRelease`：删 `documentVersionId` 字段；加 `documentCount`、`chunkCount`、`isActive`；方法 `publish()/rollback()/offline()/markActive()/markInactive()/setStats(int,int)`；构造器 `IndexRelease(UUID kbId, int versionNo, String indexName, String aliasName)`
  - `IndexReleaseManager.createDraft(UUID kbId, String aliasName)`（去掉 documentVersionId 参数）
  - `IndexReleaseManager.publishKnowledgeBase(UUID kbId)`：新方法，返回 `ReleaseView`，供 Task 3 发布服务与 controller 使用
  - `IndexReleaseManager.setStats(UUID releaseId, int documentCount, int chunkCount)`：新方法，发布完成后写入统计
  - `IndexReleaseManager.markSnapshotDocuments(UUID releaseId, List<UUID> documentVersionIds)`：新方法，写 `index_release_document`
  - `ReleaseView(UUID releaseId, int versionNo, String status, String indexName, String aliasName, boolean isActive, int documentCount, int chunkCount)`
  - `IndexReleaseRepository.findByKnowledgeBaseIdAndIsActiveTrue(UUID kbId)`
  - 删除 `IndexReleaseRepository.findByDocumentVersionId`

- [ ] **Step 1: 写失败测试（生命周期与 active 语义）**

在 `IndexReleaseServiceTest` 中新增/改写（使用 mock 的 `IndexReleaseDocumentRepository` 与 `IndexReleaseRepository`）：

```java
@Test
void publishMarksOnlyNewestReleaseActive() {
    when(releases.save(any())).thenAnswer(inv -> inv.getArgument(0));
    IndexRelease older = new IndexRelease(KB, 1, "veridex-1", "prod-active");
    older.publish(); older.markActive();
    IndexRelease newer = new IndexRelease(KB, 2, "veridex-2", "prod-active");
    when(releases.findById(newer.getId())).thenReturn(Optional.of(newer));
    when(releases.findByKnowledgeBaseIdAndIsActiveTrue(KB)).thenReturn(List.of(older));

    service.publish(newer.getId());

    assertThat(older.isActive()).isFalse();
    assertThat(newer.isActive()).isTrue();
    assertThat(newer.getStatus()).isEqualTo(IndexReleaseStatus.PUBLISHED);
}

@Test
void rollbackSwitchesActiveFlagToPreviousPublished() {
    IndexRelease current = new IndexRelease(KB, 3, "veridex-3", "prod-active");
    current.publish(); current.markActive();
    IndexRelease previous = new IndexRelease(KB, 2, "veridex-2", "prod-active");
    previous.publish();
    when(releases.findById(current.getId())).thenReturn(Optional.of(current));
    when(releases.findOtherPublished(KB, current.getId())).thenReturn(List.of(previous));
    when(releases.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.rollback(KB, current.getId());

    assertThat(current.getStatus()).isEqualTo(IndexReleaseStatus.ROLLED_BACK);
    assertThat(current.isActive()).isFalse();
    assertThat(previous.isActive()).isTrue();
}

@Test
void deleteOfActiveReleaseIsRejected() {
    IndexRelease active = new IndexRelease(KB, 4, "veridex-4", "prod-active");
    active.publish(); active.markActive();
    when(releases.findById(active.getId())).thenReturn(Optional.of(active));

    assertThatThrownBy(() -> service.delete(KB, active.getId()))
            .isInstanceOf(IllegalStateException.class);
}

@Test
void deleteOfInactiveReleaseDeletesIndexAndRecord() {
    IndexRelease inactive = new IndexRelease(KB, 5, "veridex-5", "prod-active");
    inactive.publish();
    when(releases.findById(inactive.getId())).thenReturn(Optional.of(inactive));

    service.delete(KB, inactive.getId());

    verify(gateway).deleteIndex("veridex-5");
    verify(releases).delete(inactive);
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd backend && ./mvnw -q test -Dtest=IndexReleaseServiceTest
```
Expected: FAIL（编译失败：`isActive`/`markActive`/新签名不存在——先让测试因缺失 API 失败）。

- [ ] **Step 3: 实现领域模型**

`IndexRelease.java`：删除 `documentVersionId` 字段与构造器参数；新增：

```java
@Column(name = "document_count", nullable = false)
private int documentCount;

@Column(name = "chunk_count", nullable = false)
private int chunkCount;

@Column(name = "is_active", nullable = false)
private boolean isActive = false;

public IndexRelease(UUID knowledgeBaseId, int versionNo, String indexName, String aliasName) { ... }

public boolean isActive() { return isActive; }
public int getDocumentCount() { return documentCount; }
public int getChunkCount() { return chunkCount; }
public void markActive() { this.isActive = true; }
public void markInactive() { this.isActive = false; }
public void setStats(int documentCount, int chunkCount) { this.documentCount = documentCount; this.chunkCount = chunkCount; }
```

`IndexReleaseDocumentId.java`：

```java
package io.veridex.indexing.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class IndexReleaseDocumentId implements Serializable {
    private UUID releaseId;
    private UUID documentVersionId;
    // 无参构造器 + getters/setters（或 public 字段）+ equals/hashCode
}
```

`IndexReleaseDocument.java`：

```java
@Entity
@Table(name = "index_release_document")
@IdClass(IndexReleaseDocumentId.class)
public class IndexReleaseDocument {
    @Id @Column(name = "release_id", nullable = false) private UUID releaseId;
    @Id @Column(name = "document_version_id", nullable = false) private UUID documentVersionId;
    protected IndexReleaseDocument() {}
    public IndexReleaseDocument(UUID releaseId, UUID documentVersionId) { ... }
    public UUID getReleaseId() { return releaseId; }
    public UUID getDocumentVersionId() { return documentVersionId; }
}
```

`IndexReleaseDocumentRepository.java`：

```java
public interface IndexReleaseDocumentRepository extends CrudRepository<IndexReleaseDocument, IndexReleaseDocumentId> {
    List<IndexReleaseDocument> findByReleaseId(UUID releaseId);
}
```

- [ ] **Step 4: 实现服务与端口**

`IndexReleaseManager.java` 改为：

```java
DraftRelease createDraft(UUID knowledgeBaseId, String aliasName);
void prepare(UUID releaseId);
void publish(UUID releaseId);
void discardDraft(UUID releaseId);
void rollback(UUID knowledgeBaseId, UUID releaseId);
void offline(UUID knowledgeBaseId, UUID releaseId);
void delete(UUID knowledgeBaseId, UUID releaseId);
List<ReleaseView> listReleases(UUID knowledgeBaseId);
ReleaseView publishKnowledgeBase(UUID knowledgeBaseId);          // 新增：Task 3 实现发布编排后调用
void setStats(UUID releaseId, int documentCount, int chunkCount); // 新增
void markSnapshotDocuments(UUID releaseId, List<UUID> documentVersionIds); // 新增
```

`IndexReleaseService.java` 关键改动：

```java
@Override
public DraftRelease createDraft(UUID knowledgeBaseId, String aliasName) {
    int next = releases.findMaxVersionNo(knowledgeBaseId) + 1;
    String indexName = properties.indexPrefix() + "-" + knowledgeBaseId + "-" + next;
    IndexRelease release = new IndexRelease(knowledgeBaseId, next, indexName, aliasName);
    return new DraftRelease(releases.save(release).getId(), indexName, aliasName);
}

@Override
public void publish(UUID releaseId) {
    IndexRelease release = require(releaseId);
    gateway.aliasTo(release.getAliasName(), release.getIndexName());
    releases.findByKnowledgeBaseIdAndIsActiveTrue(release.getKnowledgeBaseId())
            .forEach(previous -> { previous.markInactive(); releases.save(previous); });
    release.markActive();
    release.publish();
    releases.save(release);
}

@Override
public void rollback(UUID knowledgeBaseId, UUID releaseId) {
    IndexRelease release = requireOwned(knowledgeBaseId, releaseId);
    if (release.getStatus() != IndexReleaseStatus.PUBLISHED) {
        throw new IllegalStateException("only PUBLISHED release can be rolled back");
    }
    Optional<IndexRelease> previous = releases.findOtherPublished(release.getKnowledgeBaseId(), release.getId())
            .stream().findFirst();
    release.markInactive();
    if (previous.isPresent()) {
        gateway.aliasTo(release.getAliasName(), previous.get().getIndexName());
        previous.get().markActive();
        releases.save(previous.get());
    } else {
        gateway.removeAlias(release.getAliasName());
    }
    release.rollback();
    releases.save(release);
}

@Override
public void offline(UUID knowledgeBaseId, UUID releaseId) {
    IndexRelease release = requireOwned(knowledgeBaseId, releaseId);
    if (release.getStatus() != IndexReleaseStatus.PUBLISHED) {
        throw new IllegalStateException("only PUBLISHED release can be taken offline");
    }
    gateway.removeAlias(release.getAliasName());
    release.markInactive();
    release.offline();
    releases.save(release);
}

@Override
public void delete(UUID knowledgeBaseId, UUID releaseId) {
    IndexRelease release = requireOwned(knowledgeBaseId, releaseId);
    if (release.isActive()) {
        throw new IllegalStateException("active release must be taken offline or rolled back before deletion");
    }
    gateway.deleteIndex(release.getIndexName());
    releases.delete(release);
}

@Override
public List<ReleaseView> listReleases(UUID knowledgeBaseId) {
    return releases.findByKnowledgeBaseIdOrderByVersionNoDesc(knowledgeBaseId).stream()
            .map(r -> new ReleaseView(r.getId(), r.getVersionNo(), r.getStatus().name(),
                    r.getIndexName(), r.getAliasName(), r.isActive(), r.getDocumentCount(), r.getChunkCount()))
            .toList();
}

@Override
public void setStats(UUID releaseId, int documentCount, int chunkCount) {
    IndexRelease release = require(releaseId);
    release.setStats(documentCount, chunkCount);
    releases.save(release);
}

@Override
public void markSnapshotDocuments(UUID releaseId, List<UUID> documentVersionIds) {
    snapshotDocuments.saveAll(documentVersionIds.stream()
            .map(v -> new IndexReleaseDocument(releaseId, v)).toList());
}
```

`IndexReleaseService` 构造器新增 `IndexReleaseDocumentRepository snapshotDocuments` 依赖；`publishKnowledgeBase` 在 `IndexReleaseService` 中实现为「无 READY 文档则抛错」，实际编排由 Task 3 的 `KnowledgeBasePublishService` 完成（`publishKnowledgeBase` 默认抛 `UnsupportedOperationException`，Task 3 覆盖）。

- [ ] **Step 5: 运行测试确认通过**

```bash
cd backend && ./mvnw -q test -Dtest=IndexReleaseServiceTest
```
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/io/veridex/indexing
git commit -m "feat: model index releases as knowledge base snapshots"
```

---

### Task 3: READY 版本枚举 + 手动发布编排

**Files:**
- Modify: `backend/src/main/java/io/veridex/knowledge/api/DocumentVersionProcessing.java`
- Modify: `backend/src/main/java/io/veridex/knowledge/application/DocumentService.java`
- Modify: `backend/src/main/java/io/veridex/knowledge/domain/DocumentVersionRepository.java`
- Create: `backend/src/main/java/io/veridex/indexing/application/KnowledgeBasePublishService.java`
- Modify: `backend/src/main/java/io/veridex/indexing/api/IndexReleaseManager.java`（若 `publishKnowledgeBase` 已加则跳过）
- Test: `backend/src/test/java/io/veridex/knowledge/DocumentServiceTest.java`（新建，若不存在）
- Test: `backend/src/test/java/io/veridex/indexing/KnowledgeBasePublishServiceTest.java`（新建）

**Interfaces:**
- Consumes: `DocumentVersionProcessing.listReadyVersions(UUID kbId)`、`ObjectStorage.get(objectKey + ".chunks.json")`、`ChunkIndexer.index(...)`、`IndexReleaseManager`
- Produces:
  - `DocumentVersionProcessing.listReadyVersions(UUID kbId)`：返回 `List<ReadyVersion>`，`ReadyVersion` 为 `knowledge.api` 包内 record `(UUID versionId, String objectKey, int chunkCount)`（每文档取最新 READY 版本）
  - `DocumentVersionProcessing.countNotReady(UUID kbId)`：`int`，最新版本非 READY 的文档数
  - `KnowledgeBasePublishService.publish(UUID kbId)`：返回 `PublishResult`（`indexing.api` record `(ReleaseView release, int excludedCount)`）

- [ ] **Step 1: 写失败测试（READY 枚举与发布编排）**

`DocumentServiceTest`：

```java
@Test
void listReadyVersionsReturnsNewestReadyVersionPerDocument() {
    // 造数据：kb 下文档 A 有两个版本(v1 READY, v2 READY)，文档 B 只有 v1 PROCESSING
    // 断言：listReadyVersions(kb) 只含 A.v2（最新 READY），不含 B
}
```

`KnowledgeBasePublishServiceTest`（mock）：

```java
@Test
void publishIndexesAllReadyVersionsAndMarksSnapshot() throws Exception {
    UUID kb = UUID.randomUUID(), releaseId = UUID.randomUUID();
    String indexName = "veridex-" + kb + "-1";
    String objectKey = kb + "/doc/v1/guide.md";
    when(documents.listReadyVersions(kb))
            .thenReturn(List.of(new ReadyVersion(vid, objectKey, 2)));
    when(documents.countNotReady(kb)).thenReturn(0);
    when(releases.createDraft(eq(kb), anyString()))
            .thenReturn(new DraftRelease(releaseId, indexName, "veridex-" + kb + "-active"));
    when(storage.get(objectKey + ".chunks.json"))
            .thenReturn(new ByteArrayInputStream(
                    "[{\"index\":0,\"text\":\"内容\",\"title\":\"guide.md\",\"structurePath\":\"1\"}]"
                            .getBytes(StandardCharsets.UTF_8)));
    when(releases.listReleases(kb)).thenReturn(List.of(new ReleaseView(
            releaseId, 1, "PUBLISHED", indexName, "veridex-" + kb + "-active", true, 1, 1)));

    PublishResult result = service.publish(kb);

    verify(releases).prepare(releaseId);
    verify(indexer).index(eq(kb), eq(vid), any(), eq(indexName), eq(releaseId));
    verify(releases).setStats(releaseId, 1, 1);
    verify(releases).markSnapshotDocuments(releaseId, List.of(vid));
    verify(releases).publish(releaseId);
    assertThat(result.excludedCount()).isZero();
    assertThat(result.release().isActive()).isTrue();
}

@Test
void publishFailsAndDiscardsDraftWhenChunkReadFails() throws Exception {
    // storage.get 抛异常 → verify(releases).discardDraft(releaseId)，且不 publish
}

@Test
void publishWithPendingDocumentsReportsExcludedCount() throws Exception {
    // countNotReady=2 → result.excludedCount()==2，但发布仍成功
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd backend && ./mvnw -q test -Dtest=DocumentServiceTest,KnowledgeBasePublishServiceTest
```
Expected: FAIL（API 不存在）。

- [ ] **Step 3: 实现 READY 版本枚举**

`DocumentVersionProcessing.java` 增加：

```java
record ReadyVersion(UUID versionId, String objectKey, int chunkCount) {}

List<ReadyVersion> listReadyVersions(UUID knowledgeBaseId);
int countNotReady(UUID knowledgeBaseId);
```

`DocumentVersionRepository` 增加：

```java
Optional<DocumentVersion> findFirstByDocumentIdAndStatusOrderByVersionNoDesc(
        UUID documentId, DocumentVersionStatus status);
```

`DocumentService` 实现：

```java
@Override
public List<ReadyVersion> listReadyVersions(UUID knowledgeBaseId) {
    return documents.findByKnowledgeBaseIdOrderByCreatedAtDesc(knowledgeBaseId).stream()
            .map(doc -> versions.findFirstByDocumentIdAndStatusOrderByVersionNoDesc(
                    doc.getId(), DocumentVersionStatus.READY))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .map(v -> new ReadyVersion(v.getId(), v.getObjectKey(), v.getChunkCount()))
            .toList();
}

@Override
public int countNotReady(UUID knowledgeBaseId) {
    long ready = listReadyVersions(knowledgeBaseId).size();
    return (int) (documents.findByKnowledgeBaseIdOrderByCreatedAtDesc(knowledgeBaseId).size() - ready);
}
```

- [ ] **Step 4: 实现发布编排**

`KnowledgeBasePublishService`（`indexing.application` 包，注入 `DocumentVersionProcessing`、`ObjectStorage`、`ChunkIndexer`、`IndexReleaseManager`、`JsonMapper`）：

```java
@Component
public class KnowledgeBasePublishService {

    private static final String ACTIVE_ALIAS_SUFFIX = "-active";

    private final DocumentVersionProcessing documents;
    private final ObjectStorage storage;
    private final ChunkIndexer indexer;
    private final IndexReleaseManager releases;
    private final JsonMapper jsonMapper;

    @Transactional
    public PublishResult publish(UUID knowledgeBaseId) {
        List<ReadyVersion> ready = documents.listReadyVersions(knowledgeBaseId);
        if (ready.isEmpty()) {
            throw new IllegalStateException("no READY document versions to publish");
        }
        DraftRelease draft = releases.createDraft(knowledgeBaseId, "veridex-" + knowledgeBaseId + ACTIVE_ALIAS_SUFFIX);
        try {
            releases.prepare(draft.releaseId());
            int totalChunks = 0;
            for (ReadyVersion version : ready) {
                List<ChunkRecord> chunks = readChunks(version.objectKey());
                indexer.index(knowledgeBaseId, version.versionId(), chunks, draft.indexName(), draft.releaseId());
                totalChunks += chunks.size();
            }
            releases.setStats(draft.releaseId(), ready.size(), totalChunks);
            releases.publish(draft.releaseId());
            releases.markSnapshotDocuments(draft.releaseId(),
                    ready.stream().map(ReadyVersion::versionId).toList());
        } catch (Exception e) {
            try {
                releases.discardDraft(draft.releaseId());
            } catch (Exception cleanup) {
                log.error("failed to discard draft {}", draft.releaseId(), cleanup);
            }
            throw e;
        }
        ReleaseView view = releases.listReleases(knowledgeBaseId).stream()
                .filter(r -> r.releaseId().equals(draft.releaseId())).findFirst().orElseThrow();
        return new PublishResult(view, documents.countNotReady(knowledgeBaseId));
    }

    private List<ChunkRecord> readChunks(String objectKey) throws IOException {
        try (InputStream in = storage.get(objectKey + ".chunks.json")) {
            return jsonMapper.readValue(in, new TypeReference<>() {});
        }
    }
}
```

`IndexReleaseManager` 增加 `PublishResult publishKnowledgeBase(UUID kbId)` 的默认实现委托给 `KnowledgeBasePublishService`（或由 service 直接注入并暴露 `publishKnowledgeBase`），`IndexReleaseService` 实现改为调用 `KnowledgeBasePublishService`；`IndexReleaseManager` 不再保留 `publishKnowledgeBase` 抛错占位。

- [ ] **Step 5: 运行测试确认通过**

```bash
cd backend && ./mvnw -q test -Dtest=DocumentServiceTest,KnowledgeBasePublishServiceTest
```
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/io/veridex/knowledge backend/src/main/java/io/veridex/indexing
git commit -m "feat: publish knowledge base snapshot from ready versions"
```

---

### Task 4: Worker 去自动发布 + 发布 API + 出口门禁更新

**Files:**
- Modify: `backend/src/main/java/io/veridex/ingestion/infrastructure/DocumentIngestionWorker.java`
- Modify: `backend/src/main/java/io/veridex/indexing/api/IndexReleaseController.java`
- Modify: `backend/src/test/java/io/veridex/ingestion/DocumentIngestionWorkerTest.java`
- Modify: `backend/src/test/java/io/veridex/ingestion/IngestionExitGateTest.java`

**Interfaces:**
- Consumes: `IndexReleaseManager`、`ChunkIndexer`（Worker 不再需要）、`KnowledgeBasePublishService`（controller 用）
- Produces:
  - `POST /api/knowledge-bases/{kbId}/releases/publish` → `PublishResult`（需 MANAGE 权限）
  - Worker：解析 → 分块 → 写 `parsed.json`/`chunks.json` → `markReady`，不再建索引/发布

- [ ] **Step 1: 写失败测试（worker 不再发布 + 发布 API）**

`DocumentIngestionWorkerTest`：改两个现有测试，断言 worker 完成后**不调用** `releases.prepare/publish`，只 `markReady`；并新增验证 chunks.json 写入：

```java
@Test
void workerWritesChunkManifestAndMarksReadyWithoutPublishing() throws Exception {
    // ... mock documents/storage/parser/chunker/audit
    // worker.onIngest(...)
    verify(storage).put(objectKey + ".chunks.json", any(), eq("application/json"), anyInt());
    verify(documents).markReady(versionId, 1);
    verify(releases, never()).publish(any());
    verify(releases, never()).prepare(any());
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd backend && ./mvnw -q test -Dtest=DocumentIngestionWorkerTest
```
Expected: FAIL（worker 仍自动发布）。

- [ ] **Step 3: 修改 Worker**

`DocumentIngestionWorker`：
- 移除 `IndexReleaseManager releases` 与 `ChunkIndexer indexer` 依赖与构造器参数；
- 移除 `createDraft/prepare/index/publish/discardDraft` 调用与 `published`/`draft` 变量；
- 保留：幂等检查（READY 直接 ack）、解析、分块、写 parsed/chunks.json、`markReady`、审计、失败 `markFailed` + reject。

- [ ] **Step 4: 实现发布 API**

`IndexReleaseController` 增加：

```java
@PostMapping("/publish")
public ResponseEntity<PublishResult> publish(@PathVariable UUID kbId) {
    requireManage(kbId);
    return ResponseEntity.ok(releases.publishKnowledgeBase(kbId));
}
```

`IndexReleaseManager` 最终形态：`PublishResult publishKnowledgeBase(UUID kbId)` 由 `IndexReleaseService` 委托 `KnowledgeBasePublishService` 实现。

- [ ] **Step 5: 更新出口门禁**

`IngestionExitGateTest`：
- `duplicateDeliveryDoesNotDuplicateChunks`：改为验证 worker 幂等（chunk 清单不重复 + 不重复建 release）——断言 `releaseRepository.findByKnowledgeBaseIdOrderByVersionNoDesc(kb)` 大小为 0（worker 不再建 release）；
- `failedNewVersionLeavesOldReleaseQueryable`：改为「手动发布 v1 → 上传 v2 失败 → 再手动发布 → alias 指向含 v1 的新快照，v2 不在其中」：

```java
@Test
void failedNewVersionIsExcludedFromNextManualPublish() throws Exception {
    Uploaded v1 = uploadAndPut("a.md", "# 第一版\n\n稳定内容");
    publishMessage(v1);
    awaitReady(v1.version().getId());
    releaseManager.publishKnowledgeBase(v1.kbId());

    Uploaded v2 = uploadVersion("b.md");           // MinIO 无对象 → 处理失败
    publishMessage(v2);
    awaitStatus(v2.version().getId(), DocumentVersionStatus.FAILED);

    var result = releaseManager.publishKnowledgeBase(v1.kbId());
    assertThat(result.excludedCount()).isEqualTo(1);

    String alias = "veridex-" + v1.kbId() + "-active";
    assertThat(gateway.findChunksByDocumentVersion(alias, v1.version().getId())).isNotEmpty();
    assertThat(gateway.findChunksByDocumentVersion(alias, v2.version().getId())).isEmpty();
}
```

- `offlineDocumentIsExcludedFromSearch`：改为「手动发布 → offline 该 release → alias 查不到」：

```java
@Test
void offlineReleaseIsExcludedFromSearch() throws Exception {
    Uploaded u = uploadAndPut("off.md", "# 离线\n\n敏感内容");
    publishMessage(u);
    awaitReady(u.version().getId());
    var published = releaseManager.publishKnowledgeBase(u.kbId());

    String alias = "veridex-" + u.kbId() + "-active";
    assertThat(gateway.findChunksByDocumentVersion(alias, u.version().getId())).isNotEmpty();

    releaseManager.offline(u.kbId(), published.release().releaseId());

    assertThat(gateway.findChunksByDocumentVersion(alias, u.version().getId())).isEmpty();
}
```

（旧 `findByDocumentVersionId` 已删，替换为上述语义。）

- [ ] **Step 6: 运行测试确认通过**

```bash
cd backend && ./mvnw -q test -Dtest=DocumentIngestionWorkerTest,IngestionExitGateTest
```
Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat: move release publishing out of ingestion worker"
```

---

### Task 5: 前端类型与 API

**Files:**
- Modify: `web/src/features/knowledge/knowledgeApi.ts`

**Interfaces:**
- Produces:
  - `Release` 类型加 `isActive: boolean`、`documentCount: number`、`chunkCount: number`
  - `PublishResult = { release: Release; excludedCount: number }`
  - `knowledgeApi.publish(kbId: string): Promise<PublishResult>`

- [ ] **Step 1: 写失败测试（无前端单测则跳过，靠 TS 类型检查）**

直接改类型（前端无对应单测文件时，用 `npm --prefix web run lint && npm --prefix web run build` 验证类型一致性）。

- [ ] **Step 2: 修改类型与 API**

```ts
export type Release = {
  releaseId: string
  versionNo: number
  status: string
  indexName: string
  aliasName: string
  isActive: boolean
  documentCount: number
  chunkCount: number
}
export type PublishResult = { release: Release; excludedCount: number }
```

在 `knowledgeApi` 增加：

```ts
publish: (kbId: string): Promise<PublishResult> =>
  fetch(`/api/knowledge-bases/${kbId}/releases/publish`, {
    method: 'POST',
    credentials: 'include',
  }).then((response) => json<PublishResult>(response)),
```

- [ ] **Step 3: 验证**

```bash
cd web && npm run lint && npm run build
```
Expected: PASS。

- [ ] **Step 4: 提交**

```bash
git add web/src/features/knowledge/knowledgeApi.ts
git commit -m "feat: add publish api and release active fields"
```

---

### Task 6: 前端发布按钮与列表呈现

**Files:**
- Modify: `web/src/features/knowledge/KnowledgePage.tsx`
- Modify: `web/src/features/knowledge/components/VersionList.tsx`
- Modify: `web/src/features/knowledge/components/ReleaseList.tsx`
- Modify: `web/src/styles.css`
- Test: `web/src/features/knowledge/KnowledgePage.test.tsx`
- Test: `web/src/features/knowledge/components/ReleaseList.test.tsx`（新建，若不存在）

**Interfaces:**
- Consumes: `knowledgeApi.publish(kbId)`、`Release`（含 `isActive` 等字段）
- Produces:
  - `KnowledgePage`：`publishNow()` 调用 `knowledgeApi.publish`，`publishing` 状态、`publishError`、`publishNotice`（`excludedCount > 0` 时显示「本次发布未包含 X 个文档」），成功后 `setRefreshKey(k => k + 1)` 刷新 VersionList
  - `VersionList`：`ReleaseList` 渲染按新字段显示「当前检索」角标与统计
  - `ReleaseList`：`isActive` 行显示「当前检索」角标与 `X 份文档 / Y chunks`；回滚/离线按钮仅 active 行；删除按钮 active 行禁用

- [ ] **Step 1: 写失败测试**

`KnowledgePage.test.tsx` 新增：

```ts
test('publishes the knowledge base and refreshes releases', async () => {
  // fetch stub: POST /api/knowledge-bases/kb-1/releases/publish → PublishResult{release:{...isActive:true}, excludedCount:0}
  render(<KnowledgePage />)
  fireEvent.click(await screen.findByRole('button', { name: '发布' }))
  expect(await screen.findByText('当前检索')).toBeInTheDocument()
  // 断言 publish 请求已发送
})

test('shows excluded document notice after publish', async () => {
  // excludedCount: 2 → findByRole('status') 含「本次发布未包含 2 个文档」
})
```

`ReleaseList.test.tsx`（新建）：

```ts
test('marks the active release and gates actions by active flag', () => {
  render(<ReleaseList ... releases={[
    { releaseId: 'r2', versionNo: 2, status: 'PUBLISHED', indexName: 'i2', aliasName: 'a', isActive: true, documentCount: 3, chunkCount: 40 },
    { releaseId: 'r1', versionNo: 1, status: 'PUBLISHED', indexName: 'i1', aliasName: 'a', isActive: false, documentCount: 1, chunkCount: 5 },
  ]} ... />)
  expect(screen.getByText('当前检索')).toBeInTheDocument()
  expect(screen.getAllByRole('button', { name: '回滚' })).toHaveLength(1)   // 仅 active 行
  expect(screen.getAllByRole('button', { name: '删除发布 v1' })).toHaveLength(1)
  expect(screen.getByRole('button', { name: '删除发布 v2' })).toBeDisabled()
  expect(screen.getByText('3 份文档 / 40 chunks')).toBeInTheDocument()
})
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd web && npx vitest run src/features/knowledge/KnowledgePage.test.tsx src/features/knowledge/components/ReleaseList.test.tsx
```
Expected: FAIL。

- [ ] **Step 3: 实现发布按钮与列表**

`KnowledgePage.tsx` 增加 `publishing/publishError/publishNotice` state 与 `publishNow`；在 `knowledge-workspace-header` 加「发布」按钮（`disabled={publishing || !selected}`，文案 `publishing ? '正在发布' : '发布'`），失败显示 `role="alert"`，`excludedCount>0` 显示 `role="status"` 提示。

`ReleaseList.tsx`：`isActive` 行加 `<span className="status-badge status-published">当前检索</span>`；行内显示 `<small>{documentCount} 份文档 / {chunkCount} chunks</small>`；`(release.status === 'PUBLISHED' && release.isActive)` 才渲染回滚/离线；删除按钮 `disabled={pending || release.isActive}`。

`styles.css`：新增 `.release-meta`（次要统计文本）样式，沿用现有 token（`--text-muted`、`--surface-accent` 等）。

- [ ] **Step 4: 运行测试确认通过**

```bash
cd web && npm test
```
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add web/src
git commit -m "feat: surface active index release and manual publish"
```

---

### Task 7: 全量验证与文档同步

**Files:**
- Modify: `docs/knowledge-ingestion-pipeline.md`

- [ ] **Step 1: 更新管道文档**

将第 4 节「页面上的索引发布是什么」与第 5 节时序改为知识库全量快照语义：worker 只处理到 READY，不再自动发布；「发布」由知识管理员手动触发，一次发布 = 该知识库全部 READY 文档版本的一个不可变快照；新增「当前检索」active 语义说明。

- [ ] **Step 2: 后端全量验证**

```bash
cd backend && ./mvnw clean verify
```
Expected: BUILD SUCCESS（全部单测 + 集成测试 + 架构测试通过）。

- [ ] **Step 3: 前端全量验证**

```bash
cd web && npm test && npm run lint && npm run build && git diff --check
```
Expected: 全部通过。

- [ ] **Step 4: 提交**

```bash
git add docs/knowledge-ingestion-pipeline.md
git commit -m "docs: align ingestion pipeline doc with snapshot releases"
```
