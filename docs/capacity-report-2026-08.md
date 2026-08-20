# Veridex 容量与恢复报告（2026-08）

> 环境：macOS Docker Desktop 单节点（Apple Silicon，资源上限与实测占用见 §1）、compose 栈（postgres 17 / opensearch 3.2.0 / minio / rabbitmq 4）、应用镜像版本 `9b6e71f`（Phase 5-e 收口前最后一次后端改动）。
> 规模：1M chunk 实测（20 KB × 50 doc/KB × 1000 chunk/doc）；5M 为外推（§4，非实测）。
> 负载工具：k6 2.2.0；容量工具：`tools/capacity`（独立模块，不进 CI）。

## 1. 数据生成（1M）

<!-- 待 Task 8b 实测填充：SeedGenerator 总耗时、PG/MinIO/OpenSearch 各阶段耗时、磁盘占用 -->

## 2. 全量重建 RTO（源重建路径）

<!-- 待填充：ReindexBenchmark 计时与恢复演练 reindex 计时互证 -->

## 3. 在线负载（三档 × 双模型）

<!-- 待填充：deterministic 与 ollama(qwen3.5:9b-mlx) 的 5/20/50 VU：
     P50/P95/P99 全链路、吞吐、错误率、first_token 指标 -->

## 4. 5M 外推（非实测）

<!-- 待填充：磁盘线性、检索复杂度依据、bulk 吞吐线性外推；生产前建议目标硬件校准 -->

## 5. 恢复演练记录

<!-- 待填充：verify-recovery.sh 演练日期、RTO、抽查结果（Task 5 已实跑 RTO=21s，1M 数据后复测） -->

## 6. 评测干扰量化

<!-- 待填充：50 VU ± 评测运行 的 P99 漂移；部署建议 -->

## 7. 结论与生产部署建议

<!-- 待填充：资源配额、副本数、瓶颈项、Session/限流多副本语义 -->
