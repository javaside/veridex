# 发布流程

本文档说明如何发布 Veridex 的新版本（打 tag、发版说明、GitHub Release、镜像构建）。

## 版本号

遵循[语义化版本](https://semver.org/lang/zh-CN/)：`主版本.次版本.修订号`。

当前源码版本为 `0.1.0-SNAPSHOT`（见根 `pom.xml`）。发布时去掉 `-SNAPSHOT` 作为 tag（如 `v0.1.0`）。

## 发布前检查

从仓库根目录跑完整质量门禁，确认全绿：

```bash
./scripts/verify.sh
```

该脚本依次执行后端 `clean verify`、前端测试、前端生产构建、可观测性/安全/部署检查与 `git diff --check`。注意：它依赖 Docker（后端 Testcontainers 集成测试、镜像与 Helm 检查）。

## 编写发版说明

1. 在 `docs/release-notes/vX.Y.Z.md` 写详细发版说明（功能、已知限制、安全声明）。
2. 在 `CHANGELOG.md` 的索引表顶部加一行指向该文件。

发版说明里的链接一律写**相对路径**（`../../LICENSE`、`../architecture.md`），这样在仓库文件视图、本地编辑器、任意托管站都成立。

## 打 tag 并推送

```bash
git add -A
git commit -m "release: v0.1.0"
git tag -a v0.1.0 -m "Veridex v0.1.0"
git push origin main --tags
```

## 创建 GitHub Release

GitHub 会自动为每个 tag 附上 source code（zip / tar.gz）。用发版说明内容创建 Release：

```bash
gh release create v0.1.0 \
  --title "Veridex v0.1.0" \
  --notes-file docs/release-notes/v0.1.0.md
```

> 注意：GitHub Release 页面解析不了 `docs/release-notes/` 里的相对链接。若 Release 说明里需要可点击的链接，发布时把相对路径替换为 `https://github.com/<owner>/<repo>/blob/<tag>/...` 绝对 URL，但**源文件保持相对路径不动**（不要为一个渲染环境污染另外三个环境）。

## 构建离线交付包（用于 GitHub Release 资产）

发布物以源码为主，另附离线交付包作为可部署资产：构建镜像 → 生成离线交付物（镜像 tar + Helm chart + SHA-256 清单）→ 打包成 `dist/veridex-<版本>-offline.tar.gz`。

```bash
# 完整构建（含镜像构建）
./scripts/build-release.sh v0.1.0

# 复用本地已构建镜像（跳过镜像构建）
SKIP_BUILD=1 ./scripts/build-release.sh v0.1.0
```

产物输出到 `<repo>/dist/veridex-<版本>-offline.tar.gz`（及其 `.sha256`），`dist/` 已 git 忽略、不进仓库。上传到 Release：

```bash
gh release upload v0.1.0 dist/veridex-0.1.0-offline.tar.gz
```

## 构建并推送 Docker 镜像（可选）

镜像用于私有化部署。构建并推送：

```bash
# backend
docker build -f backend/Dockerfile -t <registry>/veridex-backend:v0.1.0 .
docker push <registry>/veridex-backend:v0.1.0

# web
docker build -f web/Dockerfile -t <registry>/veridex-web:v0.1.0 .
docker push <registry>/veridex-web:v0.1.0
```

镜像的 OCI 标签（`org.opencontainers.image.source` / `.licenses`）已在 Dockerfile 中声明为 `javaside/veridex` 与 `Apache-2.0`。

Helm chart 位于 `deploy/helm/veridex`；离线交付物由 `./scripts/verify-deployment.sh 5` 校验生成逻辑，均随仓库发布。

## 发布后

- 确认 CI 绿灯（`.github/workflows/ci.yml` 在 push 到 `main` 后自动运行）。
- 在 SECURITY.md 的「支持的版本」表中更新支持范围（如适用）。
