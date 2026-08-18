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

case "${STAGE}" in
  1) stage1 ;;
  all) stage1; echo "verify-deployment: later stages not yet implemented in this task." ;;
  *) echo "unknown or not-yet-implemented stage: ${STAGE}" >&2; exit 2 ;;
esac
