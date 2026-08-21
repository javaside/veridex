# Veridex

[![CI](https://github.com/javaside/veridex/actions/workflows/ci.yml/badge.svg)](https://github.com/javaside/veridex/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/javaside/veridex?display_name=tag&sort=semver&label=release)](https://github.com/javaside/veridex/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/javaside/veridex/total?label=downloads)](https://github.com/javaside/veridex/releases)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange)](https://adoptium.net/)

**[⬇️ 下载最新版](https://github.com/javaside/veridex/releases/latest)** ·
[变更日志](CHANGELOG.md) ·
[参与贡献](CONTRIBUTING.md) ·
[安全策略](SECURITY.md)

---

Veridex 是面向企业私有化部署的 RAG（Retrieval-Augmented Generation，检索增强生成）平台。它以「知识库 → 检索 → 带引用回答」为主线，覆盖从文档入库、索引发布、授权问答，到质量评测与运营反馈闭环的完整链路。

应用已提供 backend/web 双容器镜像、Compose 完整应用栈、原生 Helm chart 与受限网络离线交付。

## 当前状态

- **Phase 1：可执行基础** — 已完成
- **Phase 2：知识入库垂直切片** — 已完成
- **Phase 3：带权限的 RAG 查询** — 已完成
- **Phase 4：质量评测与运营闭环** — 已完成
- **Phase 5-b：可观测性与受控 Trace Body** — 已完成
- **Phase 5：企业试点准备** — 持续进行中

详细阶段目标见[交付路线图](docs/superpowers/plans/2026-08-09-enterprise-rag-delivery-roadmap.md)。

## 已实现能力

- 本地用户、Session 登录和 `PLATFORM_ADMIN` / `KNOWLEDGE_ADMIN` / `EMPLOYEE` 角色
- 知识库创建及 VIEW / MANAGE 授权模型
- PDF、DOCX、TXT、Markdown 文档上传（单文件最大 50 MB）
- MinIO 原始文件和解析结果存储
- PostgreSQL Transactional Outbox + RabbitMQ 异步入库
- Apache Tika 文档解析及结构优先分块
- OpenSearch 全文和向量字段写入
- 不可变 IndexRelease，以及发布、设为当前、离线和删除流程
- 解析文本、Chunk 和文档版本预览
- 重复消息幂等、失败版本隔离及离线立即排除
- 授权知识范围（多知识库交集）上的混合检索（BM25 + 向量 + RRF 融合）
- 有限多轮会话、SSE 流式回答、`[n]` 引用校验与原文预览
- 依据不足时明确拒答（含 `ACCESS_RESTRICTED` 统一文案）
- 版本化评测集（`DatasetVersion` 不可变快照 + 冻结用例，支持 ANSWER / REFUSE 期望行为）
- 不可变 RAG 配置版本（chunking / retrieval / generation / prompt / model 五维草稿 + 发布冻结快照）
- 同步评测运行与自动指标（Recall@K、MRR、NDCG、citation hit、refusal match、latency）
- 两个评测运行的逐指标对比与回归门禁（相对退化 10% 阈值）
- 点赞/点踩反馈落库与坏例转评测集闭环

## 当前限制

- 默认 `DeterministicEmbeddingModel` 为 128 维确定性哈希（无语义）；可通过 `VERIDEX_EMBEDDING_PROVIDER=ollama` 切换 Ollama `qwen3-embedding:0.6b`（真实语义，1024 维；同时需设置 `VERIDEX_EMBEDDING_DIMENSIONS=1024`）。
- 默认对话模型由 `VERIDEX_CHAT_PROVIDER=deepseek` 决定（模型 `deepseek-v4-flash`，也可切换 `ollama`）；`deterministic` 为测试占位实现。配置 Profile 的 `chatModel`/`embeddingModel` 字段目前仅作标识记录，未接入运行时模型路由（当前单一模型装配）。
- 评测为「仅自动指标」的同步运行；judge-model 自动评判与人工评审尚未实现。
- 回归门禁阈值为硬编码默认值（相对退化 10%），尚未配置化。
- 配置版本（Profile）已同时驱动评测运行与在线问答的检索/生成参数、prompt 模板、会话历史轮数，以及入库时的分块参数；`chatModel`/`embeddingModel` 仍仅作标识记录（不做模型路由）。改 `chunking` 后，仅对之后新入库的文档生效，存量文档需重新入库才会按新参数重切。
- PDF / DOCX 的结构识别较基础；没有 Markdown 标题时主要按文本长度分块。
- 失败消息进入 DLQ；自动重试、退避以及 Outbox 定时恢复仍待增强。

## 技术栈

| 区域 | 技术 |
|---|---|
| 后端 | Java 21、Spring Boot 4、Spring Modulith、Spring Data JPA、Spring Security、Spring AI 2 |
| 前端 | React 19、TypeScript、Vite、Vitest |
| 业务数据库 | PostgreSQL 17 |
| 消息队列 | RabbitMQ 4 |
| 对象存储 | MinIO |
| 检索索引 | OpenSearch 3.2 |
| 会话存储 | PostgreSQL（Spring Session JDBC，迁移 V15） |
| 文档解析 | Apache Tika 3 |
| 集成测试 | JUnit、Testcontainers |

## 项目结构

```text
.
├── backend/                  Spring Boot 模块化单体
├── web/                      React + Vite 管理控制台
├── deploy/compose/           本地基础设施 Compose 配置
├── docs/                     架构、路线图和专题文档
├── scripts/verify.sh         项目一键质量门禁
├── pom.xml                   Maven 父项目
└── mvnw                      Maven Wrapper
```

## 容器化与部署

- **Compose 完整应用栈**：`deploy/compose/compose.yml`（基础设施 + backend + web），冒烟验收 `deploy/compose/smoke.sh`。
- **原生 Helm chart**：`deploy/helm/veridex`（双端口 Service、existing Secret、Ingress/PDB/HPA/NetworkPolicy/ServiceMonitor）。
- **部署门禁**：`scripts/verify-deployment.sh`（镜像、Compose 栈、Helm lint/template 矩阵、kind 集群验收、离线交付包）。
- **受限网络离线交付**：`deploy/offline/` 生成离线包，安装步骤见 `deploy/offline/veridex-offline/INSTALL.txt`。

> ⚠️ 无论哪种部署方式，都要先把 Chat 与 Embedding 模型配成真实 provider，**不要用默认 `deterministic`**（详见 [配置说明](docs/configuration.md)）。

## 快速开始

> ⚠️ **必须先配好模型，否则问答检索跑不出真实结果。** 默认的 `deterministic` embedding 是 128 维无语义哈希，向量检索基本没用；`deepseek` 对话模型必须提供 `VERIDEX_DEEPSEEK_API_KEY`。启动前请按 [配置说明](docs/configuration.md) 把 `VERIDEX_CHAT_PROVIDER` 与 `VERIDEX_EMBEDDING_PROVIDER` 都配成真实模型，**不要用默认值**。

启动基础设施、后端和前端三个部分：

```bash
# 1. 启动基础设施（PostgreSQL / RabbitMQ / MinIO / OpenSearch）
docker compose -f deploy/compose/compose.yml up -d

# 2. 启动后端（已配好 chat/embedding 模型后）
./mvnw -pl backend spring-boot:run

# 3. 启动前端（另开终端）
npm --prefix web ci
npm --prefix web run dev
```

访问 `http://localhost:5173`，使用 `admin` / `veridex` 登录。完整的环境要求、分步说明、服务地址与默认账号、停止清理见[快速启动指南](docs/getting-started.md)。

## 文档

| 主题 | 文档 |
|---|---|
| 快速启动与环境 | [docs/getting-started.md](docs/getting-started.md) |
| 配置与模型 provider | [docs/configuration.md](docs/configuration.md) |
| 架构、部署拓扑与可观测性 | [docs/architecture.md](docs/architecture.md) |
| 知识入库管道 | [docs/knowledge-ingestion-pipeline.md](docs/knowledge-ingestion-pipeline.md) |
| RAG 配置参数语义 | [docs/rag-configuration-parameters.md](docs/rag-configuration-parameters.md) |
| 容量与恢复报告 | [docs/capacity-report-2026-08.md](docs/capacity-report-2026-08.md) |

## 许可证

Copyright 2026 Xinghua Zhou。本项目以 [Apache License 2.0](LICENSE) 开源（见 [`LICENSE`](LICENSE)、[`NOTICE`](NOTICE)）。

选它的理由：与所依赖的 Spring Boot / Spring AI / Spring Modulith 全栈一致（均 Apache 2.0），并附带显式专利授权。所依赖的第三方组件（后端 Spring 生态为 Apache 2.0、PostgreSQL JDBC 为 BSD 2-Clause，前端 React / React Router 等为 MIT）均为宽松许可，各自保留其原始许可，本项目不对其再许可；完整清单见 [`NOTICE`](NOTICE)。

## 贡献

欢迎贡献！请先阅读[贡献指南](CONTRIBUTING.md)与[行为准则](CODE_OF_CONDUCT.md)。安全漏洞请按 [SECURITY.md](SECURITY.md) 所述方式私下报告，不要通过公开 issue 披露。变更历史见 [CHANGELOG.md](CHANGELOG.md)。
