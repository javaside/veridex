# Veridex 容量与恢复报告（2026-08）

> 环境：macOS Docker Desktop 单节点（记录 CPU/内存/磁盘）、compose 栈、镜像版本 `<commit>`。
> 规模：1M chunk 实测（20 KB × 50 doc/KB × 1000 chunk/doc），5M 为外推（§4 标注非实测）。

## 1. 数据生成（1M）

<!-- 由 1M 实测填充：SeedGenerator 输出：总耗时、PG/MinIO/OpenSearch 各阶段耗时、磁盘占用 -->

- **状态**：占位 —— 由 1M 实测填充（`tools/capacity` SeedGenerator，`--kbs 20 --docs-per-kb 50 --chunks-per-doc 1000`）。
- 待填：总耗时；PG / MinIO / OpenSearch 各阶段耗时；磁盘占用。

## 2. 全量重建 RTO（源重建路径）

<!-- ReindexBenchmark 与恢复演练 reindex 计时，两处互证 -->

- **状态**：占位 —— 由 1M 实测填充（`tools/capacity` ReindexBenchmark 重跑计时 + 恢复演练 reindex 计时，两处互证）。
- 待填：1M 规模下删除索引 → 重建 → 重灌全量 → 计时；与 §5 演练 reindex 耗时互为印证。

## 3. 在线负载（三档 × 双模型）

<!-- deterministic 与 ollama(qwen3.5:9b-mlx) 的 5/20/50 VU:
     P50/P95/P99 全链路、吞吐、错误率、first_token P99 -->

- **状态**：占位 —— 由 1M 实测填充（`tools/capacity/k6/run-matrix.sh`）。
- 待填：deterministic 与 ollama（qwen3.5:9b-mlx）两曲线，5 / 20 / 50 VU 三档；P50/P95/P99 全链路、吞吐、错误率、first_token P99。

## 4. 5M 外推（非实测）

<!-- 磁盘线性、HNSW 检索曲线引用+实测锚点、bulk 吞吐线性；
     结论 + 「生产前建议目标硬件 5M 校准」 -->

- **状态**：占位 —— 由 1M 实测填充（本节为非实测外推，须显式标注）。
- 待填：磁盘线性、HNSW 检索曲线引用 + 1M 实测锚点、bulk 吞吐线性；结论 + 「生产前建议目标硬件 5M 校准」。

## 5. 恢复演练记录

<!-- verify-recovery.sh 实跑日期、RTO、抽查结果 -->

**演练已于 Task 5 实跑通过（2026-08-19），环境未变，此处复用该次记录；1M 实测阶段若重跑演练，以最新一次为准并更新本节。**

| 项目 | 记录 |
|---|---|
| 演练脚本 | `deploy/backup/verify-recovery.sh`（一键：起栈 → 种子 → 备份 → `--fresh` 销毁 → 恢复 → smoke + QA 抽查 → RTO） |
| 实跑日期 | 2026-08-19 |
| 备份目录 | `backups/20260819T224613`（schema_version=15，MinIO 对象 57 个，含 SHA256SUMS 校验） |
| RTO | **21 秒** |
| RTO 口径 | 销毁（`restore.sh --fresh` 内部 `down -v`）→ 恢复后 smoke 通过 的总时长；**QA 抽查在窗口外不计入**（Task 5 审查 Minor ③ 口径提示） |
| 抽查结果 | 恢复后 4 个 ACTIVE 知识库全部在；QA SSE 全链路自证通过：`run.started → retrieval.completed(hitCount=1) → answer.delta×13 → citation.available(validationStatus=VALID) → answer.completed`，引用指向恢复后的版本对象（postgres → MinIO → OpenSearch 源重建 → 检索 → 生成 → 引用终检） |
| reindex 耗时 | 8–10s（4 KB、57 对象小数据集；RTO 主体）。1M 规模上界见 §2 |

## 6. 评测干扰量化

<!-- 50 VU ± 评测运行 的 P99 漂移；部署建议（评测并发上限） -->

- **状态**：占位 —— 由 1M 实测填充（`WITH_EVAL=1` 场景）。
- 待填：50 VU 在线负载叠加评测运行的 P99 漂移；部署建议（评测并发上限）。

## 7. 结论与生产部署建议

<!-- 资源配额、副本数、瓶颈项、Session/限流多副本语义 -->

- **状态**：占位 —— 由 1M 实测填充。
- 已确认事实（Phase 5-e 代码态，非容量实测）：
  - Session 已外部化到 PostgreSQL（Spring Session JDBC，迁移 V15）：多副本共享会话，无需粘性路由。
  - API 限流为**每副本语义**（`RateLimitFilter` 进程内固定窗口，默认 600 请求/分/用户/副本）：N 副本总限额 ≈ 限额 × N。
- 待填：资源配额、副本数、瓶颈项。
