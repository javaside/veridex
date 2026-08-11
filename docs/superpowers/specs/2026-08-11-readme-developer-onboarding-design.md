# Veridex README 开发者上手文档设计

## 目标

在项目根目录新增 `README.md`，为首次接触 Veridex 的开发者提供单一、可信、可执行的入口，使其能够理解当前项目状态，启动基础设施、后端和前端，使用默认账号验证知识入库流程，并运行项目质量检查。

## 目标读者

- 第一次克隆项目、需要在本机运行系统的开发者
- 需要理解当前可用功能与尚未实现范围的协作者
- 需要运行测试、构建和基础设施检查的维护者

README 不承担生产部署手册职责。当前 Compose 只编排开发依赖，应用镜像、Kubernetes 和生产安全配置属于后续阶段。

## 文档结构

根目录 `README.md` 按以下顺序组织：

1. **项目简介**：说明 Veridex 是企业私有化 RAG 平台，以及当前完成到 Phase 2。
2. **当前能力与限制**：列出知识库、上传、异步解析、分块、索引发布等已实现能力；明确真实 RAG 查询和语义模型尚未实现。
3. **技术栈与目录结构**：概述 Spring Boot、React、PostgreSQL、RabbitMQ、MinIO、OpenSearch，以及主要目录职责。
4. **环境要求**：Java 21、Docker Compose、Node.js 22.13+、npm。
5. **快速启动**：分别启动基础设施、后端和前端，并说明需要三个终端或后台进程。
6. **访问地址与开发账号**：列出 Web、Backend、Actuator、RabbitMQ、MinIO、OpenSearch 地址，以及三个种子用户。
7. **首次使用流程**：登录后创建知识库、上传支持格式、观察异步处理、预览解析结果及管理 Release。
8. **配置说明**：列出后端支持的环境变量和 Compose `.env` 的作用，提醒默认凭据只适合本地开发。
9. **验证与测试**：提供一键验证、后端测试、前端测试、Lint 和构建命令。
10. **停止与清理**：区分停止容器、删除容器和删除持久化卷。
11. **常见问题**：覆盖端口冲突、Docker 未启动、OpenSearch 内存、登录失败、文档长期处于 UPLOADED/FAILED、清理本地数据等场景。
12. **进一步阅读**：链接路线图、平台设计和知识入库管道文档。

## 命令和事实来源

README 中的信息必须直接来自当前项目配置：

- Compose：`deploy/compose/compose.yml`
- 后端默认配置：`backend/src/main/resources/application.yml`
- 前端代理和端口：`web/vite.config.ts` 与 Vite 默认端口
- Node 版本：`web/package.json`
- 一键验证：`scripts/verify.sh`
- 默认用户：`V2__identity.sql`
- 当前阶段：企业交付路线图及当前代码状态

关键命令在写入后实际执行或做等价验证，避免文档与项目脱节。

## 快速启动语义

README 明确区分三层进程：

```text
Docker Compose：PostgreSQL / RabbitMQ / Redis / MinIO / OpenSearch
Maven：Spring Boot 后端（8080）
npm + Vite：React 前端（5173，代理 /api 和 /actuator 到 8080）
```

推荐步骤：

```bash
docker compose -f deploy/compose/compose.yml up -d
./mvnw -pl backend spring-boot:run
npm --prefix web install
npm --prefix web run dev
```

首次依赖安装可使用 `npm ci --prefix web`，但为了避免命令参数位置歧义，README 统一使用 `npm --prefix web ci`。如果依赖已安装，可以跳过该步骤。

## 安全边界

README 必须明确：

- Compose 和应用配置中的密码是开发默认值，禁止直接用于共享或生产环境。
- 当前 OpenSearch 开发配置关闭安全插件。
- 默认用户仅用于本地演示和开发。
- 当前确定性 Embedding 不提供语义相似度，不能代表生产 RAG 质量。

## 验证标准

README 完成后满足以下条件：

- 根目录存在 `README.md`，无占位符、TODO 或未解释命令。
- 所有文件路径、端口、账号、环境变量与当前代码一致。
- Compose 配置可解析，基础设施可启动并达到健康状态。
- 后端启动命令可编译并启动应用。
- 前端安装、测试、Lint 和构建命令有效。
- `scripts/verify.sh` 通过。
- Markdown 命令块可以直接从仓库根目录执行。
- README 清楚区分“当前已实现”与“路线图计划”，不暗示 Phase 3 已交付。
