#!/usr/bin/env bash
set -euo pipefail

# 固化「Compose 发布包」构建脚本：构建 backend/web 应用镜像 → 导出镜像 tar →
# 生成不含 build 段的 compose 文件 + .env 模板 + 安装运行说明 → 打包 tar.gz 到 dist/。
#
# 目标受众：拿到发布包的人**不需要源码、不需要 Maven/Node 构建环境**，只需要
# Docker + Docker Compose。导入镜像、配好 .env、跑一条命令即可起全栈。
#
# 用法：
#   ./scripts/build-compose-release.sh [VERSION]
#
#   VERSION 缺省时从 deploy/helm/veridex/Chart.yaml 的 version 读取。
#   产物输出到 <repo>/dist/veridex-<VERSION>-compose.tar.gz（dist/ 已 git 忽略）。
#
# 环境变量：
#   SKIP_BUILD=1    跳过镜像构建（复用本地已构建镜像 veridex-backend:<VERSION> / veridex-web:<VERSION>）

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHART_DIR="${ROOT_DIR}/deploy/helm/veridex"
DIST_DIR="${ROOT_DIR}/dist"
STAGING="$(mktemp -d)"
trap 'rm -rf "${STAGING}"' EXIT

command -v docker >/dev/null 2>&1 || { echo "build-compose-release: docker required" >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "build-compose-release: python3 required" >&2; exit 1; }

# 版本号：参数 > Chart.yaml version。统一去掉前导 v（v0.1.0 → 0.1.0），
# 产物名与镜像 tag 都使用不带 v 的版本号（与 Chart.yaml / images.txt 一致）。
if [[ $# -ge 1 ]]; then
  VERSION="$1"
else
  VERSION="$(awk '/^version:/ {print $2; exit}' "${CHART_DIR}/Chart.yaml")"
fi
VERSION="${VERSION#v}"
[[ -n "${VERSION}" ]] || { echo "build-compose-release: unable to determine VERSION" >&2; exit 1; }

PKG_DIR="${STAGING}/veridex-${VERSION}-compose"
BACKEND_IMAGE="veridex-backend:${VERSION}"
WEB_IMAGE="veridex-web:${VERSION}"

# 1. 构建镜像（如未跳过）
if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  echo "==> 构建 backend 镜像 ${BACKEND_IMAGE}"
  docker build -f "${ROOT_DIR}/backend/Dockerfile" -t "${BACKEND_IMAGE}" "${ROOT_DIR}"
  echo "==> 构建 web 镜像 ${WEB_IMAGE}"
  docker build -f "${ROOT_DIR}/web/Dockerfile" -t "${WEB_IMAGE}" "${ROOT_DIR}"
else
  echo "==> 跳过镜像构建（SKIP_BUILD=1）"
fi

docker image inspect "${BACKEND_IMAGE}" >/dev/null 2>&1 \
  || { echo "build-compose-release: image not built locally: ${BACKEND_IMAGE}" >&2; exit 1; }
docker image inspect "${WEB_IMAGE}" >/dev/null 2>&1 \
  || { echo "build-compose-release: image not built locally: ${WEB_IMAGE}" >&2; exit 1; }

# 2. 组装发布目录
mkdir -p "${PKG_DIR}/images"

echo "==> 导出应用镜像"
docker save -o "${PKG_DIR}/images/veridex-backend.tar" "${BACKEND_IMAGE}"
docker save -o "${PKG_DIR}/images/veridex-web.tar" "${WEB_IMAGE}"

# 3. 生成发布用 compose.yml：去掉 build 段（发布包无源码，无法 build），
#    并把应用镜像 tag 从 0.1.0 替换为本次 VERSION。
echo "==> 生成 compose.yml（去除 build 段）"
python3 - "${ROOT_DIR}/deploy/compose/compose.yml" "${PKG_DIR}/compose.yml" "${VERSION}" <<'PY'
import sys
src, dst, version = sys.argv[1], sys.argv[2], sys.argv[3]
out = []
skip_indent = None
with open(src) as f:
    for raw in f:
        line = raw.rstrip("\n")
        # 若正处于 build 块内：跳过缩进比 build 更深（含子键）的行
        if skip_indent is not None:
            stripped = line.lstrip(" ")
            indent = len(line) - len(stripped)
            if stripped and indent > skip_indent:
                continue
            skip_indent = None
        # 命中 build: 行则跳过，并进入 build 块
        stripped = line.lstrip(" ")
        indent = len(line) - len(stripped)
        if stripped == "build:":
            skip_indent = indent
            continue
        # 应用镜像 tag 替换（仅 :0.1.0，不影响 postgres:17-alpine 等）
        line = line.replace(":0.1.0", f":{version}")
        out.append(line)
with open(dst, "w") as f:
    f.write("\n".join(out) + "\n")
print("generated", dst)
PY

# 4. 可观测性配置：compose.yml 里 prometheus/tempo/otel-collector/grafana
#    通过 ./observability/... 相对路径挂载，必须随包携带，否则这些容器起不来。
cp -R "${ROOT_DIR}/deploy/compose/observability" "${PKG_DIR}/observability"

# 5. .env 模板
cp "${ROOT_DIR}/deploy/compose/.env.example" "${PKG_DIR}/.env.example"

# 5. 运行脚本（load + 冒烟）
cp "${ROOT_DIR}/deploy/compose/smoke.sh" "${PKG_DIR}/smoke.sh"
chmod +x "${PKG_DIR}/smoke.sh"

cat > "${PKG_DIR}/load-images.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
echo "==> 导入应用镜像（backend / web）"
for f in images/*.tar; do
  docker load -i "$f"
done
echo "==> 镜像导入完成。可执行 docker compose up -d 启动。"
EOF
chmod +x "${PKG_DIR}/load-images.sh"

cat > "${PKG_DIR}/start.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
if [ ! -f .env ]; then
  echo "未找到 .env。请先执行：cp .env.example .env 并填入模型配置（见 README.md）。" >&2
  exit 1
fi
./load-images.sh
echo "==> 启动全栈（基础设施 + backend + web）"
docker compose up -d
# web 依赖 backend healthy，backend 首次就绪（含 OpenSearch/启动）可能较慢，这里等待就绪。
WEB_PORT="${VERIDEX_WEB_PORT:-8090}"
echo "==> 等待服务就绪（web http://127.0.0.1:${WEB_PORT}/healthz，最多 5 分钟）"
ready=""
for _ in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:${WEB_PORT}/healthz" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 5
done
if [ -z "${ready}" ]; then
  echo "服务未在超时内就绪，请用 docker compose logs backend 排查。" >&2
  exit 1
fi
echo "==> 服务状态"
docker compose ps
echo "==> 运行冒烟验证"
VERIDEX_WEB_URL="http://127.0.0.1:${WEB_PORT}" ./smoke.sh
echo "==> 完成。访问 http://localhost:${WEB_PORT}（默认账号 admin / veridex）"
EOF
chmod +x "${PKG_DIR}/start.sh"

cat > "${PKG_DIR}/stop.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
docker compose down
EOF
chmod +x "${PKG_DIR}/stop.sh"

# 6. 安装运行说明
cp "${ROOT_DIR}/deploy/compose/RELEASE-README.md" "${PKG_DIR}/README.md"

# 7. 打包
mkdir -p "${DIST_DIR}"
ARCHIVE="${DIST_DIR}/veridex-${VERSION}-compose.tar.gz"
echo "==> 打包 ${ARCHIVE}"
tar -czf "${ARCHIVE}" -C "${STAGING}" "veridex-${VERSION}-compose"
echo "==> SHA256"
(cd "${DIST_DIR}" && shasum -a 256 "$(basename "${ARCHIVE}")") | tee "${ARCHIVE}.sha256"

echo "build-compose-release: done -> ${ARCHIVE}"
