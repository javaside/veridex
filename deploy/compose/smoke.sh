#!/usr/bin/env bash
set -euo pipefail

# Veridex web 冒烟测试（Compose 完整栈与 kind 集群验收共用）。
# 环境变量：
#   VERIDEX_WEB_URL          web 入口（默认 http://127.0.0.1:8090）
#   VERIDEX_PROMETHEUS_URL   Prometheus 入口；为空时跳过第 5 节（kind 验收无 Prometheus）
WEB="${VERIDEX_WEB_URL:-http://127.0.0.1:8090}"
PROM="${VERIDEX_PROMETHEUS_URL:-}"
JAR=$(mktemp)
trap 'rm -f "${JAR}"' EXIT
fail() { echo "smoke: $1" >&2; exit 1; }

# 1. web 与 SPA fallback
curl -fsS "${WEB}/healthz" | grep -Fxq ok || fail "web /healthz not ok"
curl -fsS "${WEB}/" | grep -q '<div id="root">' || fail "SPA index missing"
test "$(curl -fsS -o /dev/null -w '%{http_code}' "${WEB}/knowledge")" = "200" || fail "SPA fallback broken"

# 2. CSRF：GET /api/auth/csrf 下发 XSRF-TOKEN cookie（5-c SPA 语义）
curl -fsS -c "${JAR}" "${WEB}/api/auth/csrf" >/dev/null || fail "csrf token endpoint failed"

# 3. 登录建立 Session（login 豁免 CSRF），/me 需要 Session
curl -fsS -b "${JAR}" -c "${JAR}" -X POST "${WEB}/api/auth/login?username=admin&password=veridex" >/dev/null \
  || fail "login with seed admin failed"
curl -fsS -b "${JAR}" "${WEB}/api/auth/me" | grep -q '"username":"admin"' || fail "session /me failed"

# 4. 浏览器写请求 CSRF：携带 X-XSRF-TOKEN header 调用 logout
XSRF=$(awk '$6 == "XSRF-TOKEN" {print $NF}' "${JAR}" | tail -n1)
test -n "${XSRF}" || fail "XSRF-TOKEN cookie missing"
curl -fsS -b "${JAR}" -H "X-XSRF-TOKEN: ${XSRF}" -X POST "${WEB}/api/auth/logout" >/dev/null \
  || fail "CSRF-protected logout failed"

# 5. web 不代理 Actuator
code=$(curl -s -o /dev/null -w '%{http_code}' "${WEB}/actuator/health")
test "${code}" = "404" || fail "web must not proxy /actuator (got ${code})"

# 6. Prometheus 抓取容器化 backend 管理端口（可选）
if [ -n "${PROM}" ]; then
  curl -fsS --max-time 10 "${PROM}/api/v1/query?query=up" | grep -q 'backend:8081' \
    || fail "Prometheus has no backend:8081 target"
  curl -fsS --max-time 10 "${PROM}/api/v1/query?query=up%7Bjob%3D%22veridex%22%7D" | grep -q '"1"' \
    || fail "veridex prometheus target is not up"
fi

echo "compose smoke: all checks passed."
