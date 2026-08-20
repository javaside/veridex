# Veridex 容量与恢复报告（2026-08）

> 环境：macOS Docker Desktop 单节点（Apple Silicon M4，14 核 / 内存 7.75GiB 上限）、compose 栈（postgres 17-alpine / opensearch 3.2.0 / minio / rabbitmq 4-management-alpine / prometheus 3.13.2）、应用镜像 `9b6e71f`（Phase 5-e 收口前最后后端改动）。
> 规模：**1M chunk 实测**（20 KB × 50 doc/KB × 1000 chunk/doc）；5M 为外推（§4，非实测）。
> 负载工具：k6 2.2.0（SSE 流式问答，普通 http.post 读完整流）；容量工具：`tools/capacity`（独立模块，不进 CI）。
> 测量日期：2026-08-20。

## 1. 数据生成（1M）

- 总耗时：**182.4s（约 3 分钟）**，目标 <1h 达成。
- 规模：1,000,000 chunks / 1,000 docs / 1,000 document_versions / 20 knowledge bases；OpenSearch `_count` 与 PG 对账一致（1M）。
- 单 KB（50K chunk）生成约 9s。
- 磁盘占用：OpenSearch 数据目录 **3.9GB**（shards=1/replicas=0，128 维 knn_vector + text）；PG 数据库 11MB（元数据为主）；MinIO 对象含原文/parsed/chunks.json（chunks.json 是主要体积）。
- 内存（压测期间峰值）：OpenSearch 2.76GiB / backend 0.52GiB / PG 37MiB。

## 2. 全量重建 RTO（源重建路径）

| 数据量 | 耗时 | 说明 |
|---|---|---|
| 单 KB 50K chunk（ReindexBenchmark） | 4.87s | 删索引→重建→读 MinIO chunks.json→bulk→挂 alias |
| 全量 1M（外推：20 × 50K） | ≈ 100s | 20 KB 线性叠加（实测单 KB 9.6s 生成 + 4.9s 重建） |
| 恢复演练（Task 5，种子 1 库 1 文档） | **RTO=21s** | 口径：销毁→smoke 通过，不含 QA 抽查 |

结论：1M 规模源重建恢复 RTO 主体约 **2 分钟量级**（重建）+ 启动/回灌时间；恢复性价优于快照+重建双轨（无快照仓库运维成本）。

## 3. 在线负载（三档 × 双模型）

**deterministic（默认占位模型）**——限流临时放宽至 100000/min（默认 600/min 会在 50VU 下触发 429，见 §7）：

| VU | P50 | P95 | P99 | max | 失败率 | 请求数/3min |
|---|---|---|---|---|---|---|
| 5 | 45ms | 51ms | 65ms | 85ms | 0 | ~1000 |
| 20 | 55ms | 72ms | 79ms | 89ms | 0 | ~4100 |
| 50 | 55ms | 82ms | 100ms | 144ms | 0 | ~10400 |

- 吞吐：50VU 下 ~43 req/s 全链路（含检索+生成+落库），P99 随并发从 65→100ms 温和爬升，无错误。
- 首 token（deterministic）：`veridex_generation_first_token_seconds` 全 0 量级（模板拼接瞬时完成），无信息量。
- 单请求检索采样（30 次，1M 数据）：P50=46ms / P99=138ms。

**ollama（真实模型 qwen3.5:9b-mlx，thinking 模型）**：

| VU | P50 | P95 | P99 | max | 失败率 | 迭代/5min |
|---|---|---|---|---|---|---|
| 5 | 60.1s | 112.8s | 120s | 120s | 5.9% | 23 |

- 首 token 平均约 **87s**（thinking 模型先思考后输出，首个可见 delta 要等思考结束）；P99 撞 k6 120s 上限，5.9% 超时。
- **结论：qwen3.5 系 thinking 模型不适合作为在线问答默认模型**——5VU 即饱和（吞吐 <5 req/min）；生产应选非 thinking 模型（如 `qwen3:8b` 标准版）或降级 deterministic 基线做容量底座。1M 检索部分两种模型共享（P50≈46ms 检索），差异全在生成段。

## 4. 5M 外推（非实测）

- **磁盘**：OpenSearch 3.9GB/1M → 5M ≈ **19.5GB**（shards=1/replicas=0，分片内数据线性；knn_vector 128 维线性）。PG ≈ 55MB。MinIO chunks.json 随 docs 线性。
- **检索**：HNSW kNN 查询为近似对数复杂度，但本测量在 1M 单分片下 P50≈46ms——5M 单分片下 HNSW 图更大，预计 P50 升至 60-80ms（引用 OpenSearch HNSW 曲线 + 本实测锚点）；**若要求 P99<100ms，建议 5M 时按 KB 拆多分片或增加分片数**。
- **重建**：bulk 吞吐实测 ~10K chunks/s 单线程 → 5M ≈ 500s（约 8 分钟），多线程可线性加速。
- **内存**：OpenSearch 堆按数据量建议 4-8GiB（5M）；backend 稳定 0.5GiB 与数据量无关。
- ⚠️ **全部外推为线性近似，非实测**；建议生产前在目标硬件跑 5M 校准（至少覆盖检索 P99 与重建 RTO）。

## 5. 恢复演练记录

- 演练日期：2026-08-19（Task 5，种子 1 库 1 文档规模）；**RTO=21s**（销毁→smoke 通过，不含 QA 抽查；备份 `backups/20260819T224613`）。
- 验证链路：备份（sha256 校验）→ `--fresh` 销毁 → pg_restore → MinIO 回灌 → reindex 容器重建 → smoke（登录/SPA/CSRF/actuator 404）→ 问答抽查（answer.delta 流 + citation VALID）。
- 恢复后 Session 表随 pg_dump 恢复，重启后原登录态仍有效（隐式验证）。
- 1M 规模 RTO 外推见 §2（≈2 分钟重建主体 + 回灌时间）。

## 6. 评测干扰量化

- 干扰组：50VU 在线负载 + 后台评测运行。实测 dataset 为 0 case 空数据集，评测秒级完成，**P99 无可见漂移**（100ms vs 无评测 100ms；P95 85ms vs 82ms，<4% 噪声级）。
- **局限（如实）**：干扰未用真实规模评测（200+ case）；空检索 dataset 的评测几乎不占资源。
- 部署建议：评测运行与在线负载共用栈时，评测并发 ≤ 2 且 case 数 ≤ 200 的 P99 漂移预计 <10%（外推自空数据集 + 资源余量——OpenSearch/backend 峰值内存远低于配额）；严格隔离需独立实例（spec 非目标）。

## 7. 结论与生产部署建议

1. **容量底座（deterministic）**：1M chunk / 50 并发全链路 P99=100ms、0 失败、43 req/s。资源：OpenSearch 3.9GB 磁盘 + 2.8GB 内存 / backend 0.5GB 内存即可承载。
2. **真实模型**：qwen3.5 系 thinking 模型在线饱和（5VU）；**生产选非 thinking 模型（qwen3:8b）或为 thinking 模型单独设计异步/任务式回答**。容量结论 deterministic 与真实模型不可混——本报告已分列。
3. **限流**：默认 600/min/进程在 50VU 压测下触发 429（这是产品语义，不是容量瓶颈）；多副本下每副本 600，总限额=600×副本数；压测/验收需临时放宽。
4. **副本数**：backend 2 副本 + Session 已 PG 外部化（滚动升级/故障切换不丢登录态）；OpenSearch 单副本即可（源重建可恢复）。
5. **恢复**：源重建路径 RTO 1M ≈ 2 分钟主体，无快照仓库运维；备份=pg_dump + MinIO 对象 + sha256 manifest（`deploy/backup/backup.sh`），恢复 `restore.sh`，演练 `verify-recovery.sh`。
6. **瓶颈项**：检索（OpenSearch 单分片 1M P50=46ms）与真实模型生成段（thinking 模型 87s 首 token）；embedding 生成在 deterministic 下瞬时，真实 embedding（qwen3-embedding）需另行测量。
7. **生产前待办**：5M 目标硬件校准（§4）、非 thinking 模型在线验证、真实 embedding 容量。
