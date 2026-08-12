# 索引发布状态简化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 移除索引发布的 ROLLED_BACK 状态与「回滚」操作，改为「设为当前」，让 `isActive` 唯一决定当前检索版本，并增加无当前版本的空态保护提示。

**Architecture:** 后端删除 `IndexReleaseStatus.ROLLED_BACK`，把 `IndexReleaseService.rollback()` 改为 `makeCurrent()`（对任意非当前快照切 alias + 维护 active 不变量，OFFLINE 快照可恢复）；V6 迁移把存量 ROLLED_BACK 归为 PUBLISHED；前端把「回滚」按钮改为「设为当前」，列表按 isActive+status 显示三种状态并增加空态提示。

**Tech Stack:** Spring Boot 4（Spring Modulith）、JPA、Flyway、OpenSearch Java Client、React 19 + TypeScript、Vitest + Testing Library。

## Global Constraints

- 迁移 SQL 必须幂等、可重复执行；迁移失败回滚代码 + SQL，旧逻辑照常。
- 同一知识库至多一个 `isActive=true` 快照（发布/设为当前/下架时维护此不变量）。
- 当前检索快照不可删除，须先下架。
- 「设为当前」对 PUBLISHED 与 OFFLINE 快照均可用；OFFLINE 设为当前时恢复为 PUBLISHED。
- 列表非空但无 active 快照时，顶部显示「当前无检索版本，点击发布恢复」。
- 前端文案禁止 em dash / en dash 字符。
- 可见文案均为中文（沿用现有 UI 语言）。

---

### Task 1: V6 迁移 SQL 与迁移测试

**Files:**
- Create: `backend/src/main/resources/db/migration/V6__normalize_rolled_back_releases.sql`
- Modify: `backend/src/test/java/io/veridex/support/DatabaseMigrationTest.java`

**Interfaces:**
- Produces: V6 迁移把存量 `ROLLED_BACK` 记录归为 `PUBLISHED`（幂等）。

- [ ] **Step 1: 写迁移 SQL**

```sql
-- 全量快照语义下不再使用 ROLLED_BACK：存量回滚记录归为历史版本（PUBLISHED，非当前）
UPDATE index_release SET status = 'PUBLISHED' WHERE status = 'ROLLED_BACK';
```

- [ ] **Step 2: 更新迁移测试**

`DatabaseMigrationTest` 中：
- `flywayAppliesPlatformBaselineMigration` 的版本断言 `contains("1","2","3","4","5")` 改为 `contains("1","2","3","4","5","6")`；
- 迁移行为（ROLLED_BACK → PUBLISHED）由集成测试覆盖（见 Task 3 门禁），迁移测试仅断言 V6 成功应用，不造迁移后数据（V6 只处理存量，测试插入发生在迁移后无法验证迁移本身）。

```java
// flywayAppliesPlatformBaselineMigration 内版本断言：
assertThat(versions).contains("1", "2", "3", "4", "5", "6");
```

- [ ] **Step 3: 运行迁移测试确认通过**

```bash
./mvnw -q test -Dtest=DatabaseMigrationTest
```
Expected: PASS（V1..V6 全部成功）。

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/resources/db/migration/V6__normalize_rolled_back_releases.sql backend/src/test/java/io/veridex/support/DatabaseMigrationTest.java
git commit -m "feat: normalize rolled back releases to published in migration"
```

---

### Task 2: 后端状态机与「设为当前」操作

**Files:**
- Modify: `backend/src/main/java/io/veridex/indexing/domain/IndexReleaseStatus.java`
- Modify: `backend/src/main/java/io/veridex/indexing/domain/IndexRelease.java`
- Modify: `backend/src/main/java/io/veridex/indexing/api/IndexReleaseManager.java`
- Modify: `backend/src/main/java/io/veridex/indexing/infrastructure/IndexReleaseService.java`
- Modify: `backend/src/main/java/io/veridex/indexing/api/IndexReleaseController.java`
- Test: `backend/src/test/java/io/veridex/indexing/IndexReleaseServiceTest.java`

**Interfaces:**
- Consumes: `SearchIndexGateway`（`aliasTo/removeAlias`）、`IndexReleaseRepository`
- Produces:
  - `IndexReleaseStatus`：删除 `ROLLED_BACK`
  - `IndexRelease`：删除 `rollback()`，新增 `reactivate()`（`status = PUBLISHED`）
  - `IndexReleaseManager.makeCurrent(UUID kbId, UUID releaseId)`：替代 `rollback`
  - 删除 `IndexReleaseManager.rollback`
  - `POST /api/knowledge-bases/{kbId}/releases/{releaseId}/make-current`：替代 `/rollback`

- [ ] **Step 1: 写失败测试（makeCurrent 语义）**

`IndexReleaseServiceTest` 改写 `rollbackSwitchesActiveFlagToPreviousPublished` 为：

```java
@Test
void makeCurrentSwitchesActiveFlagAndAliasToTarget() {
    IndexRelease current = new IndexRelease(KB, 3, "veridex-3", "prod-active");
    current.publish(); current.markActive();
    IndexRelease target = new IndexRelease(KB, 1, "veridex-1", "prod-active");
    target.publish();
    when(releases.findById(target.getId())).thenReturn(Optional.of(target));
    when(releases.findByKnowledgeBaseIdAndIsActiveTrue(KB)).thenReturn(List.of(current));
    when(releases.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.makeCurrent(KB, target.getId());

    verify(gateway).aliasTo("prod-active", "veridex-1");
    assertThat(current.isActive()).isFalse();
    assertThat(target.isActive()).isTrue();
    assertThat(target.getStatus()).isEqualTo(IndexReleaseStatus.PUBLISHED);
}

@Test
void makeCurrentReactivatesOfflineRelease() {
    IndexRelease target = new IndexRelease(KB, 2, "veridex-2", "prod-active");
    target.publish(); target.offline();
    when(releases.findById(target.getId())).thenReturn(Optional.of(target));
    when(releases.findByKnowledgeBaseIdAndIsActiveTrue(KB)).thenReturn(List.of());
    when(releases.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.makeCurrent(KB, target.getId());

    assertThat(target.getStatus()).isEqualTo(IndexReleaseStatus.PUBLISHED);
    assertThat(target.isActive()).isTrue();
    verify(gateway).aliasTo("prod-active", "veridex-2");
}
```

删除/改写原 `rollbackSwitchesActiveFlagToPreviousPublished`（不再有 rollback）。

- [ ] **Step 2: 运行测试确认失败**

```bash
./mvnw -q test -Dtest=IndexReleaseServiceTest
```
Expected: FAIL（`makeCurrent`/`reactivate` 不存在）。

- [ ] **Step 3: 实现**

`IndexReleaseStatus.java`：

```java
public enum IndexReleaseStatus { DRAFT, PUBLISHED, OFFLINE }
```

`IndexRelease.java`：删 `rollback()`，加：

```java
public void reactivate() { this.status = IndexReleaseStatus.PUBLISHED; }
```

`IndexReleaseManager.java`：删 `void rollback(UUID, UUID)`，加 `void makeCurrent(UUID knowledgeBaseId, UUID releaseId);`

`IndexReleaseService.java`：删 `rollback` 实现，加：

```java
@Override
public void makeCurrent(UUID knowledgeBaseId, UUID releaseId) {
    IndexRelease release = requireOwned(knowledgeBaseId, releaseId);
    if (release.isActive()) {
        throw new IllegalStateException("release is already the current one");
    }
    gateway.aliasTo(release.getAliasName(), release.getIndexName());
    releases.findByKnowledgeBaseIdAndIsActiveTrue(knowledgeBaseId)
            .forEach(previous -> { previous.markInactive(); releases.save(previous); });
    if (release.getStatus() == IndexReleaseStatus.OFFLINE) {
        release.reactivate();
    }
    release.markActive();
    releases.save(release);
}
```

`IndexReleaseController.java`：把

```java
@PostMapping("/{releaseId}/rollback")
public ResponseEntity<Void> rollback(...) { requireManage(kbId); releases.rollback(kbId, releaseId); ... }
```

改为

```java
@PostMapping("/{releaseId}/make-current")
public ResponseEntity<Void> makeCurrent(@PathVariable UUID kbId, @PathVariable UUID releaseId) {
    requireManage(kbId);
    releases.makeCurrent(kbId, releaseId);
    return ResponseEntity.noContent().build();
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
./mvnw -q test -Dtest=IndexReleaseServiceTest
```
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/veridex/indexing backend/src/test/java/io/veridex/indexing
git commit -m "feat: replace release rollback with make-current"
```

---

### Task 3: 出口门禁与全量后端验证

**Files:**
- Modify: `backend/src/test/java/io/veridex/ingestion/IngestionExitGateTest.java`

**Interfaces:**
- Consumes: `releaseManager.makeCurrent(kbId, releaseId)`

- [ ] **Step 1: 更新门禁测试**

`IngestionExitGateTest`：把原使用 `releaseManager.offline(...)` 之后的恢复场景补一个 `makeCurrent` 验证，并确认无 `rollback` 引用：

- `offlineReleaseIsExcludedFromSearch` 之后追加：

```java
@Test
void makeCurrentRestoresOfflineReleaseToSearch() throws Exception {
    Uploaded u = uploadAndPut("off.md", "# 离线\n\n敏感内容");
    publishMessage(u);
    awaitReady(u.version().getId());
    var published = publishService.publish(u.kbId());

    String alias = "veridex-" + u.kbId() + "-active";
    releaseManager.offline(u.kbId(), published.release().releaseId());
    assertThat(gateway.findChunksByDocumentVersion(alias, u.version().getId())).isEmpty();

    releaseManager.makeCurrent(u.kbId(), published.release().releaseId());

    assertThat(gateway.findChunksByDocumentVersion(alias, u.version().getId())).isNotEmpty();
}
```

- [ ] **Step 2: 运行门禁测试确认通过**

```bash
./mvnw -q test -Dtest=IngestionExitGateTest
```
Expected: PASS。

- [ ] **Step 3: 后端全量验证**

```bash
./mvnw clean verify
```
Expected: BUILD SUCCESS（全部测试通过，含架构测试）。

- [ ] **Step 4: 提交**

```bash
git add backend/src/test/java/io/veridex/ingestion/IngestionExitGateTest.java
git commit -m "test: cover make-current restore in exit gate"
```

---

### Task 4: 前端「设为当前」与空态保护

**Files:**
- Modify: `web/src/features/knowledge/knowledgeApi.ts`
- Modify: `web/src/features/knowledge/components/ReleaseList.tsx`
- Test: `web/src/features/knowledge/KnowledgePage.test.tsx`

**Interfaces:**
- Consumes: `knowledgeApi.releaseAction(kbId, releaseId, action)`（`action` 类型改为 `'make-current' | 'offline' | 'delete'`）
- Produces:
  - `ReleaseList`：按钮映射改为——历史版本/已下架行显示「设为当前」+「删除」；当前检索行显示「下架」；删除按钮当前行禁用
  - 空态保护：`releases.length > 0 && !releases.some(r => r.isActive)` 时，列表顶部显示「当前无检索版本，点击发布恢复」

- [ ] **Step 1: 更新 `knowledgeApi.ts`**

```ts
releaseAction: (kbId: string, releaseId: string, action: 'make-current' | 'offline' | 'delete'): Promise<null> =>
```

- [ ] **Step 2: 重写 `ReleaseList.tsx`**

```tsx
const statusLabel = (release: Release) => {
  if (release.isActive) return '当前检索'
  if (release.status === 'PUBLISHED') return '历史版本'
  return '已下架'
}
// 列表渲染（关键差异）：
//  - 空态保护：在 <div className="release-list"> 前，当 releases.length>0 && 无 active 时渲染：
//      <div className="state-block compact"><h4>当前无检索版本</h4><p>点击「发布」将当前知识库重新上线为检索快照。</p></div>
//  - 每行按钮：
//      {release.isActive
//        ? <button className="text-button" disabled={pending} onClick={() => void act(release, 'offline')}><CloudSlash size={16} />下架</button>
//        : <button className="text-button" disabled={pending} onClick={() => void act(release, 'make-current')}><ArrowsClockwise size={16} />设为当前</button>}
//      <button className="text-button danger" aria-label={`删除发布 v${release.versionNo}`} disabled={pending || release.isActive} title={release.isActive ? '当前检索版本，需先下架' : undefined} onClick={() => void act(release, 'delete')}><Trash size={16} />删除</button>
```

- [ ] **Step 3: 更新前端测试**

`KnowledgePage.test.tsx`：
- `does not send a release deletion request when confirmation is cancelled` 的 release stub 保持 `isActive: false`；
- 新增「设为当前」测试：点历史版本行的「设为当前」→ 断言 `POST .../make-current` 被调用、`onChanged` 触发刷新；
- 新增空态保护测试：`releases` 返回 `[{ ... isActive: false, status: 'PUBLISHED' }]` → 断言显示「当前无检索版本」。

- [ ] **Step 4: 运行前端测试/lint/build**

```bash
cd web && npm test && npm run lint && npm run build && git diff --check
```
Expected: 全部通过。

- [ ] **Step 5: 提交**

```bash
git add web
git commit -m "feat: switch release actions to make-current with empty-state guard"
```

---

### Task 5: 文档同步与收尾验证

**Files:**
- Modify: `docs/knowledge-ingestion-pipeline.md`

- [ ] **Step 1: 更新管道文档**

第 4 节生命周期表：删「回滚 rollback」行，加「设为当前 makeCurrent」（对历史/已下架快照切 alias + 置当前，OFFLINE 自动恢复 PUBLISHED）；状态枚举从 `DRAFT/PUBLISHED/ROLLED_BACK/OFFLINE` 改为 `DRAFT/PUBLISHED/OFFLINE`；补充空态提示说明。

- [ ] **Step 2: 提交**

```bash
git add docs/knowledge-ingestion-pipeline.md
git commit -m "docs: align pipeline doc with simplified release states"
```
