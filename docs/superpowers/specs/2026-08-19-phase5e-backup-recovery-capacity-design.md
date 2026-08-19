# Phase 5-e 备份恢复与容量验证 - 设计文档

> 日期：2026-08-19
> 状态：已确认
> 定位：Phase 5 收官阶段；完成后企业试点准备（5-a~5-e）全部就绪

## 1. 背景

Phase 5-a~5-d 与 Ollama Chat 前置阶段已完成：API 治理、可观测性、安全加固、容器与 K8s/Helm 部署、真实流式问答全部就绪。路线图对 Phase 5 的剩余要求（`docs/superpowers/plans/2026-08-09-enterprise-rag-delivery-roadmap.md` §Phase 5）：

- PostgreSQL, S3, and OpenSearch backup/recovery；
- online load, bulk ingestion, evaluation isolation, and 1–5 million chunk capacity report；
- 退出门禁：actual recovery drill succeeds; production-like load and failure-recovery reports are approved。

现状缺口：

- postgres/minio/opensearch 仅有匿名 Docker 卷，无任何备份/恢复脚本与演练；
- backend 为内存 Session（helm 默认 2 副本，滚动升级/故障切换丢登录态）；限流计数器为进程内 `ConcurrentHashMap`（多副本各算各的）；compose 中的 redis 为闲置服务（代码零引用）；
- 无负载测试、无合成数据生成器、无容量报告；OpenSearch 索引 shards=1/replicas=0；
- 评测运行与在线问答共用同一栈，干扰未量化。

## 2. 目标与非目标

### 2.1 目标

- 提供 PG + MinIO 一致性备份脚本与可验收的恢复演练（OpenSearch 源重建）；
- backend Session 外部化到 PostgreSQL（Spring Session JDBC），多副本滚动/故障不丢登录态；
- 移除 compose 中闲置的 redis 服务；
- 1M chunk 本机实测（生成、重建 RTO、检索延迟、在线负载、评测干扰），deterministic 与 Ollama 双曲线；
- 5M 外推报告与生产部署建议；
- 真实恢复演练记录 + 容量报告构成 Phase 5 退出门禁证据。

### 2.2 非目标

- 不做 OpenSearch snapshot repository（快照备份）；
- 不做备份调度的 Helm CronJob（k8s 备份命令作为安装文档说明）；
- 不做独立评测实例（评测隔离仅量化干扰）；
- 不做 5M chunk 全量实测；
- 不做全局限流（限流保持每副本语义，文档明确）；
- 不给 chart/应用新增常驻 reindex 管理 API。

## 3. 已确认决策

| 编号 | 决策 | 说明 |
|---|---|---|
| D1 | OpenSearch 源重建 | 只备份 PG + MinIO；恢复时从源数据全量重建索引。无快照仓库依赖、存储省、与现有 alias 发布机制一致。代价：恢复时间含重建时间。 |
| D2 | Spring Session JDBC | 复用现有 PostgreSQL，不加新组件；备份随 PG 走。同时移除闲置 redis。 |
| D3 | 1M 实测 + 5M 外推 | 本机 Docker 单节点环境约束；5M 线性外推并标注非实测，建议生产前目标硬件校准。 |
| D4 | k6 + Java 生成器 | k6 做在线负载（内建 SSE、Prometheus 输出）；数据生成器用 Java（与集成测试同栈直连基础设施）。 |
| D5 | 评测隔离 = 量化干扰 | 报告给出「评测并发对在线 P99 漂移」数据与部署建议，不改架构。 |
| D6 | 备份交付 = 脚本 + compose 验证 | backup.sh/restore.sh + 一键演练 verify-recovery.sh；k8s 侧为文档说明。 |
| D7 | 恢复演练是显式门禁步骤 | 不进 verify.sh 自动门禁（太重），作为 5-e 退出的手工步骤记录结果。 |

## 4. 组件设计

### 4.1 备份（`deploy/backup/backup.sh`）

```bash
./deploy/backup/backup.sh [--stack compose栈目录] [--out 输出目录]
```

- PostgreSQL：`pg_dump -Fc`（自定义格式，支持并行恢复）；
- MinIO：按 bucket 枚举对象流式导出到 `objects/`（保留对象键结构）；
- 产物结构：`backups/<timestamp>/{manifest.json, postgres.dump, objects/...}`；
- `manifest.json`：时间戳、应用/镜像版本、Flyway `schema_version`、活跃发布状态（KB→索引→alias）、对象数、每个文件的 SHA-256 校验和；
- 脚本 `set -euo pipefail`，任一步失败非零退出，不留半成品目录（失败清理）。

**一致性语义**：不追求 PG 与 MinIO 的冻结一致（无分布式事务），保证**可恢复自洽**——恢复后重建流程对「PG 有记录但 MinIO 缺对象」的文档走现有 ingestion 失败路径并显式标记，不静默丢数据。恢复前校验迁移版本兼容（目标 `schema_version` ≤ 备份版本才可恢复，否则拒绝并提示先升级应用）。

### 4.2 恢复（`deploy/backup/restore.sh`）

```bash
./deploy/backup/restore.sh --backup backups/<timestamp> [--fresh]
```

- `--fresh`：删除并重建 postgres/opensearch 数据卷，需二次确认（输入 `YES`）；
- 无 `--fresh`：拒绝在非空数据卷上恢复（防误覆盖）。

恢复序列：

1. 校验 manifest（校验和、迁移版本兼容）；
2. `--fresh` 重建卷 → 起基础设施 → `pg_restore` → Flyway `validate`（不 migrate，备份已含 schema）；
3. MinIO 对象回灌（按 manifest 对象清单）；
4. OpenSearch 全量重建：对每个 KB 触发一次全量发布（复用 `KnowledgeBasePublishService` 语义：幂等、alias 切换、状态机）；
5. 起 backend/web → smoke 验收 + 抽查（文档预览、问答含引用、权限拒答）。

**重建触发方式**：不起常驻管理 API。恢复脚本起一个**一次性 backend 容器**（挂恢复后的栈），以内部入口直调各 KB 全量发布，跑完即退。复用全部现有发布语义，不扩大攻击面。

### 4.3 恢复演练（`deploy/backup/verify-recovery.sh`）

一键演练（5-e 退出门禁的显式步骤，不进 verify.sh）：

起栈 → 灌种子数据（建库/传文档/发布/问答）→ backup.sh → `--fresh` 销毁 → restore.sh → smoke + 抽查 → 输出演练记录（含 RTO 实测）。演练结果人工记录进容量报告 §5。

### 4.4 Spring Session JDBC

- `backend/pom.xml` 加 `spring-session-jdbc`（版本随 Boot BOM）；
- 配置：`spring.session.store-type=jdbc`、`initialize-schema=never`（schema 由 Flyway 管）、`table-name` 用默认、`timeout` 由 `VERIDEX_SESSION_TIMEOUT`（默认 8h）控制；
- Flyway **V15**：Spring Session JDBC 官方 PostgreSQL schema（`SPRING_SESSION` / `SPRING_SESSION_ATTRIBUTES` + 索引）；
- Session cookie 属性、CSRF 机制、安全过滤器链不动——Spring Session 只替换存储层；CSRF token 存 Session，改 JDBC 后天然多副本共享，现有 CSRF 测试回归覆盖；
- 过期清理用 Spring Session JDBC 内建定时任务：默认每分钟一次（`spring.session.jdbc.cleanup-cron` 默认 `0 * * * * *`），按 `EXPIRY_TIME` 索引删除主表、属性表经外键 `ON DELETE CASCADE` 级联删除；不自造清理器，也不改默认 cron；
- 序列化用 Spring Session 默认（属性均为简单类型：用户 ID/角色）。

**移除闲置 redis**：compose 删 redis 服务与健康检查依赖；`InfrastructureSmokeTest` 的 redis 探活断言同步删除；helm chart 从未引用 redis，不动。

**验证**：
- 集成测试：登录后 Session 从 PG 恢复（重建应用上下文后原 Cookie 仍有效）；
- kind 滚动升级验收加一步：滚动期间持 Cookie 请求不丢登录态（现有 rolling upgrade 步骤插入带 Cookie 的轮询断言）；
- 性能：容量负载中登录态请求 P99 与内存版对比（预期 Session 读多一次 PG 查询，<5ms 量级，报告记录实测）。

### 4.5 容量验证（`tools/capacity/`）

独立 maven 模块（不进 backend 构建链，verify.sh 不触发）+ k6 脚本目录：

**数据生成器（Java，直连基础设施）**：
- 合成语料：中文制度文档模板（请假/报销/差旅/保密等 8 类 × 参数化展开），每 chunk 200-500 字；
- 规模：1M chunk，分布为多 KB（如 20 KB × 50 文档/KB 量级，覆盖 KB 数对混合检索的影响），具体按磁盘实测微调；
- 写入与生产一致：PG 元数据 + MinIO 对象（原文/parsed/chunks.json）+ OpenSearch bulk；
- embedding 用 deterministic 128 维（可复现、不依赖 Ollama），批量并行计算；
- 目标吞吐：1M chunk 生成 < 1 小时；
- 生成后跑一次全量发布并计时——该计时即「源重建恢复路径」的 RTO 主体（与恢复演练互为印证）。

**k6 在线负载（`qa-load.js`）**：
- setup：admin 登录拿 Session + CSRF；每个 VU 独立 employee 账号；
- 场景：SSE 流式问答（读完整流为一次迭代）；
- 梯度：5 / 20 / 50 VU × 每档 5 分钟；
- 采集：P50/P95/P99 全链路时延、首 delta 时延（SSE 首事件）、吞吐、错误率；
- 输出 `--out prometheus-remote-write` 进现有 Grafana。

**评测干扰量化**：基线（50 VU 在线 P99）vs 干扰组（50 VU + 同时跑 200 case 评测运行），对比 P99 漂移，结论为部署建议（如「评测并发 ≤ X 时在线 P99 漂移 < Y%」）。

**双模型曲线**：deterministic 基线与 Ollama（本机 `qwen3.5:9b-mlx`，`VERIDEX_OLLAMA_CHAT_MODEL` 覆盖）分别跑在线负载，结果不混同一结论。

### 4.6 容量报告（`docs/capacity-report-2026-08.md`）

1. 环境说明（本机 Docker 单节点、资源限制、镜像版本）；
2. 1M 实测：生成、重建 RTO、检索延迟分布、在线负载三档（deterministic + Ollama 双曲线）；
3. 评测干扰量化；
4. 5M 外推与依据：磁盘按实测线性外推（shards=1/replicas=0，分片内数据线性）；检索引用 OpenSearch HNSW 官方曲线 + 实测锚点；重建按 bulk 吞吐实测线性外推；外推结论明确标注「非实测」，建议生产前目标硬件 5M 校准；
5. 恢复演练记录（RTO 实测）；
6. 结论与生产部署建议（资源配额、副本数、瓶颈项、Session/限流多副本语义）。

## 5. 失败与边界

- 备份失败：非零退出、清理半成品、manifest 不落盘（无 manifest = 无有效备份）；
- 恢复校验失败（校验和不符/版本不兼容）：拒绝恢复，明确报错，不动目标卷；
- 重建中文档缺失（PG 有记录、MinIO 无对象）：走 ingestion 失败路径显式标记，不静默跳过；
- trace body 恢复：加密对象随 MinIO 备份恢复，但**解密 key ring 不在备份产物中**——文档明确密钥需另行保管，恢复后无原 key 则 trace body 不可读（其余功能不受影响）；
- 一次性重建容器失败：重跑幂等（发布流程幂等），中断不产生半切换 alias。

## 6. 部署与配置契约

- compose：移除 redis；backup/restore/verify-recovery 脚本面向 compose 栈；
- helm/values 不新增配置（Session 外部化对 chart 透明；备份命令作为 INSTALL.txt 附录说明，安装者用 CronJob/k8s 工具自行调度）；
- 新环境变量：`VERIDEX_SESSION_TIMEOUT`（默认 8h）；
- `verify.sh` 追加：backup/restore 脚本静态契约测试（存在、`set -euo pipefail`、manifest 字段、`--fresh` 保护逻辑），不自动跑真实演练。

## 7. 测试策略

- 脚本静态契约：`backend/src/test/java/io/veridex/deployment/BackupScriptStaticContractTest.java`（DeploymentExitGateTest 风格）；
- Session：集成测试（跨上下文 Cookie 有效）+ 现有登录/CSRF/ACL 回归 + kind 滚动升级登录态断言；
- 容量工具：生成器对小子集（1K chunk）自校验（计数、抽查检索命中）；k6 脚本 smoke（1 VU × 30s）进 tools/capacity/README 手册而非 CI；
- 恢复演练：verify-recovery.sh 全流程实跑（显式门禁步骤）。

## 8. 验收标准

1. backup.sh 产出含校验和 manifest 的完整备份；失败不留半成品；
2. restore.sh `--fresh` 保护生效；非空卷拒绝恢复；版本不兼容拒绝恢复；
3. verify-recovery.sh 全流程演练成功：备份→销毁→恢复→smoke+抽查全绿，RTO 记录入报告；
4. 重建后「PG 有记录但对象缺失」的文档被显式标记为失败，不静默丢失；
5. 登录态在应用重启/滚动升级后保持（集成测试 + kind 验收双证）；
6. redis 从 compose 移除后全量门禁仍绿；
7. 1M chunk 实测完成：生成、重建 RTO、检索 P50/P99、三档在线负载、评测干扰量化，deterministic 与 Ollama 双曲线；
8. 5M 外推写入报告且标注非实测；
9. 容量报告含生产部署建议与 Session/限流多副本语义说明；
10. verify.sh 全量门禁（含新静态契约）通过；
11. README/architecture 更新备份恢复入口、Session 语义、容量报告索引。

## 9. 实施顺序

1. Spring Session JDBC + 移除 redis（多副本就绪，后续容量与验收都依赖它）；
2. backup.sh + restore.sh + 静态契约测试；
3. verify-recovery.sh 演练打通（真实验收门禁证据）；
4. 容量工具：数据生成器 + 重建计时；
5. k6 负载脚本 + 评测干扰量化（双模型曲线）；
6. 容量报告撰写 + 恢复演练记录归档；
7. 文档与 verify.sh 门禁收口。

完成后 Phase 5（5-a~5-e）全部收官，进入企业试点（20–50 人）。
