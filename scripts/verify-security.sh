#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_YML="${ROOT_DIR}/backend/src/main/resources/application.yml"
COMPOSE=(docker compose --env-file "${ROOT_DIR}/deploy/compose/.env.example" -f "${ROOT_DIR}/deploy/compose/compose.yml")

require_file() {
  test -f "$1" || { echo "missing required file: $1" >&2; exit 1; }
}

require_file "$APP_YML"

# 管理端口独立且端点白名单仅 health/info/prometheus
grep -Fq 'VERIDEX_MANAGEMENT_PORT' "$APP_YML" || { echo "management server port not configured" >&2; exit 1; }
grep -Fq 'VERIDEX_MANAGEMENT_ADDRESS' "$APP_YML" || { echo "management server address not configured" >&2; exit 1; }
grep -Fq 'include: health,info,prometheus' "$APP_YML" || { echo "Actuator exposure must be health,info,prometheus" >&2; exit 1; }

# 禁止暴露诊断端点
if grep -Eq 'include:.*\b(env|heapdump|beans|configprops|mappings|loggers)\b' "$APP_YML"; then
  echo "Actuator must not expose diagnostic endpoints" >&2
  exit 1
fi

# Prometheus 从管理端口抓取（容器化 backend 服务名）
grep -Fq 'backend:8081' "${ROOT_DIR}/deploy/compose/observability/prometheus/prometheus.yml" \
  || { echo "Prometheus must scrape the containerized backend management port" >&2; exit 1; }

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  "${COMPOSE[@]}" config --quiet
fi

echo "verify-security.sh: all security config checks passed."
