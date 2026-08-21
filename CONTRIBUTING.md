# Contributing to Veridex

感谢你对 Veridex 的关注。这份文档说明如何高效地参与贡献，包括报告问题、提交代码和本地开发流程。

## 行为准则

请遵守我们的[行为准则](CODE_OF_CONDUCT.md)。我们致力于维护一个友好、包容、尊重他人的社区。

## 报告问题（Issue）

在提交 issue 前，请先搜索已有 issue，确认没有重复。

提交 bug 报告时，请尽量包含：

- 对问题的清晰描述，以及期望行为 vs 实际行为
- 复现步骤（尽量最小化）
- 运行环境：操作系统、Docker 版本、Java 版本、Node.js 版本
- 相关日志片段（注意**不要**粘贴任何真实凭据、密钥或敏感数据）

功能请求请说明使用场景和动机，并尽量描述你期望的行为。

## 提交代码（Pull Request）

1. 先开 issue 或参与已有 issue 讨论，确认方案方向后再动手，避免返工。
2. Fork 仓库并基于 `main` 分支创建功能分支。
3. 保持提交小而聚焦；一个 PR 解决一个问题。
4. 遵守项目既有代码风格，并补充或更新相应测试。
5. 提交前在本地跑通质量门禁（见下文），确保不引入回归。
6. 在 PR 描述里说明改动动机、实现要点与测试情况。

## 本地开发

### 环境要求

- Java 21+
- Docker Desktop（后端集成测试依赖 Testcontainers）
- Docker Compose v2+
- Node.js 22.13.0+ 与 npm

### 快速开始

```bash
# 1. 启动基础设施（PostgreSQL / RabbitMQ / MinIO / OpenSearch）
docker compose -f deploy/compose/compose.yml up -d

# 2. 启动后端
./mvnw -pl backend spring-boot:run

# 3. 启动前端（另开终端）
npm --prefix web ci
npm --prefix web run dev
```

更多细节见 [README.md](README.md#快速启动)。

### 质量门禁

提交前请运行完整质量门禁：

```bash
./scripts/verify.sh
```

该脚本依次执行后端 `clean verify`、前端测试、前端生产构建与 `git diff --check`。也可以分步执行：

```bash
# 后端测试与构建
./mvnw clean verify

# 前端测试
npm --prefix web test

# 前端 lint
npm --prefix web run lint

# 前端生产构建
npm --prefix web run build
```

### 代码风格

- 后端遵循项目既有 Java 风格（模块化单体，模块边界由 `ArchitectureTest` 强制）。
- 前端遵循 TypeScript + ESLint 配置。
- 提交信息使用 Conventional Commits 风格（`feat:` / `fix:` / `docs:` / `test:` / `chore:` 等）。

## 许可证

提交代码即表示你同意将你的贡献以项目采用的 [Apache-2.0 许可证](LICENSE) 授权。
