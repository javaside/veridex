# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式。项目尚未发布正式版本，变更按开发阶段分组记录。

## [Unreleased]

### Added

#### 阶段五 · 企业试点准备（Phase 5）

- **5-e 备份与恢复**：PG 逻辑备份 + MinIO 对象导出 + SHA-256 完整性清单；`restore.sh` 含 `--fresh` 销毁式恢复；一键恢复演练（实测 RTO=21s）。
- **5-e 容量工具**：1M chunk 种子生成器、OpenSearch 重建基准、k6 问答负载矩阵（含评测干扰场景）。
- **5-d 容器化与部署**：非 root backend/web 镜像、Compose 完整应用栈、原生 Helm chart、NetworkPolicy、离线交付包。
- **5-c 安全加固**：出站访问策略（防 SSRF）、解析沙箱、上传预算、对象授权隔离、提示词注入隔离、Actuator 管理端口隔离。
- **5-b 可观测性**：Prometheus / Grafana / OTel Collector / Tempo 本地栈；受控 Trace Body（AES-256-GCM 加密、审计读取 API、保留策略）。
- **5-a API 治理**：scoped API key、作用域授权、治理过滤器与委派签发校验。

#### 阶段四 · 质量评测与运营闭环（Phase 4）

- 版本化评测集（`DatasetVersion` 不可变快照 + 冻结用例，ANSWER / REFUSE 期望行为）。
- 不可变 RAG 配置版本（chunking / retrieval / generation / prompt / model 五维草稿 + 发布冻结快照 + 「当前生效」标记）。
- 同步评测运行与自动指标（Recall@K、MRR、NDCG、citation hit、refusal match、latency）。
- 两评测运行逐指标对比与回归门禁（相对退化阈值）。
- 点赞/点踩反馈落库与坏例转评测集闭环。

#### 阶段三 · 带权限的 RAG 查询（Phase 3）

- 授权知识范围（多知识库交集）上的混合检索（BM25 + 向量 + RRF 融合 + 上下文组装）。
- 有限多轮会话、SSE 流式回答、`[n]` 引用校验与原文预览。
- 依据不足时明确拒答（`ACCESS_RESTRICTED` 统一文案）。

#### 阶段二 · 知识入库垂直切片（Phase 2）

- PDF / DOCX / TXT / Markdown 上传与 Apache Tika 解析。
- 结构优先分块、PostgreSQL Transactional Outbox + RabbitMQ 异步入库。
- OpenSearch 全文与向量字段写入、不可变 IndexRelease 发布/回滚/离线流程。

#### 阶段一 · 可执行基础（Phase 1）

- 本地用户、Session 登录与角色体系。
- 知识库创建及 VIEW / MANAGE 授权模型。

### Changed

- 默认对话模型切换为 `deepseek`（`deepseek-v4-flash`），`deterministic` 降级为测试占位实现。
- 默认嵌入模型支持切换 Ollama `qwen3-embedding`（1024 维真实语义）。
- 后端业务端口从默认 `8080` 调整为 `8088`（本地开发）。

### Fixed

- 修复真实模型（deepseek/ollama）引用缺失：引用格式指令从可覆盖模板中解耦为强制约束。
- 修复混合检索公平性、语义重排延迟与评测/引用缺陷。
- 修复 PDF 分块标题误判（shell 注释 `#` 不再被当作 Markdown 标题）。
- 处理空流式 chunk，避免生成错误。

---

## 说明

- 本文件是面向发布的高层摘要。完整的逐条提交历史请查看 `git log`。
- 归档的设计与实施快照位于 [`docs/superpowers/`](docs/superpowers/)，保留原始决策过程，不随代码演进更新。
