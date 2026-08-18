#!/usr/bin/env bash
set -euo pipefail

# Phase 5-d 部署校验门禁。
# 用法: ./scripts/verify-deployment.sh [stage]
#   stage: 1=镜像构建 2=镜像运行时检查 3=Compose 完整栈
#          4=Helm lint/template + kind 集群验收 5=离线包
#          all=1..5（默认）。无 Docker/kind/helm 时自动降级为 host-only 检查。
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STAGE="${1:-all}"
BACKEND_IMAGE="veridex-backend:0.1.0"
WEB_IMAGE="veridex-web:0.1.0"
COMPOSE=(docker compose --env-file "${ROOT_DIR}/deploy/compose/.env.example" -f "${ROOT_DIR}/deploy/compose/compose.yml")

have_docker() { command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; }

stage1() {
  echo "==> [deploy-1] backend/web 镜像构建"
  have_docker || { echo "Docker unavailable; image build skipped (host-only mode)."; return 0; }
  docker build -f "${ROOT_DIR}/backend/Dockerfile" -t "${BACKEND_IMAGE}" "${ROOT_DIR}"
  echo "verify-deployment stage 1 (backend image) passed."
}

stage2() {
  echo "==> [deploy-2] web 镜像构建 + 运行时非 root/只读检查"
  have_docker || { echo "Docker unavailable; image runtime checks skipped (host-only mode)."; return 0; }
  docker build -f "${ROOT_DIR}/web/Dockerfile" -t "${WEB_IMAGE}" "${ROOT_DIR}"
  # 镜像层契约：固定非 root 用户
  docker inspect -f '{{.Config.User}}' "${BACKEND_IMAGE}" | grep -Fxq '10001:10001'
  docker inspect -f '{{.Config.User}}' "${WEB_IMAGE}" | grep -Fxq '101:101'
  # backend：只读根文件系统 + tmpfs 解析目录下 JVM 可启动（java -version 即验证运行层自洽）
  docker run --rm --read-only --tmpfs /tmp/veridex-parser:size=2g,uid=10001,gid=10001 \
    --entrypoint java "${BACKEND_IMAGE}" -version
  # web：只读根文件系统 + /tmp 与 conf.d 可写（envsubst 渲染目标）
  WEB_CHECK_PORT=18090
  if lsof -i :${WEB_CHECK_PORT} -sTCP:LISTEN >/dev/null 2>&1; then WEB_CHECK_PORT=18091; fi
  docker rm -f veridex-web-check >/dev/null 2>&1 || true
  docker run -d --rm --name veridex-web-check --read-only \
    --tmpfs /tmp:rw,noexec,nosuid,size=64m \
    --tmpfs /etc/nginx/conf.d:rw,noexec,nosuid,size=1m,uid=101,gid=101 \
    -p 127.0.0.1:${WEB_CHECK_PORT}:8080 "${WEB_IMAGE}" >/dev/null
  trap 'docker rm -f veridex-web-check >/dev/null 2>&1 || true' EXIT
  sleep 2
  curl -fsS http://127.0.0.1:${WEB_CHECK_PORT}/healthz | grep -Fxq ok
  curl -fsS http://127.0.0.1:${WEB_CHECK_PORT}/ | grep -q '<div id="root">'
  # SPA fallback：未知路由回 index.html
  test "$(curl -fsS -o /dev/null -w '%{http_code}' http://127.0.0.1:${WEB_CHECK_PORT}/knowledge)" = "200"
  # upstream（默认 veridex-backend）在默认 bridge 网络不可解析 → 固定 502 错误页
  test "$(docker exec veridex-web-check curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/auth/csrf)" = "502"
  curl -sS http://127.0.0.1:${WEB_CHECK_PORT}/api/auth/csrf | grep -q '服务暂不可用'
  echo "verify-deployment stage 2 (web image + runtime) passed."
}

case "${STAGE}" in
  1) stage1 ;;
  2) stage2 ;;
  all) stage1; stage2; echo "verify-deployment: later stages not yet implemented in this task." ;;
  *) echo "unknown or not-yet-implemented stage: ${STAGE}" >&2; exit 2 ;;
esac
