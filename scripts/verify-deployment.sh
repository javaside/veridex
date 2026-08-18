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

stage3() {
  echo "==> [deploy-3] Compose 完整应用栈验收"
  have_docker || { echo "Docker unavailable; compose acceptance skipped (host-only mode)."; return 0; }
  "${COMPOSE[@]}" up -d --build backend web prometheus
  for attempt in $(seq 1 60); do
    if curl -fsS http://127.0.0.1:8090/healthz >/dev/null 2>&1; then break; fi
    sleep 5
  done
  # Prometheus 检查地址：127.0.0.1:9090 可能被本机其他进程（如 ClashX）占用，
  # 依次探测 IPv6 回环与 IPv4，取可达者。
  PROM_URL=""
  for candidate in "http://[::1]:9090" "http://127.0.0.1:9090"; do
    if curl -fsS --max-time 5 "${candidate}/-/ready" >/dev/null 2>&1; then PROM_URL="${candidate}"; break; fi
  done
  if [ -n "${PROM_URL}" ]; then
    VERIDEX_WEB_URL=http://127.0.0.1:8090 VERIDEX_PROMETHEUS_URL="${PROM_URL}" \
      "${ROOT_DIR}/deploy/compose/smoke.sh"
  else
    echo "warn: local Prometheus unreachable on :9090; running smoke without metrics checks." >&2
    VERIDEX_WEB_URL=http://127.0.0.1:8090 "${ROOT_DIR}/deploy/compose/smoke.sh"
  fi
  "${COMPOSE[@]}" stop backend web >/dev/null
  echo "verify-deployment stage 3 (compose stack) passed."
}

have_helm() { command -v helm >/dev/null 2>&1; }

stage4() {
  echo "==> [deploy-4] Helm lint/template 矩阵 + kind 集群验收"
  if have_helm; then
    helm lint "${ROOT_DIR}/deploy/helm/veridex"
    local out; out="$(mktemp -d)"
    helm template verify "${ROOT_DIR}/deploy/helm/veridex" > "${out}/default.yaml"
    # 默认：2 NetworkPolicy、2 PDB、无 Ingress/HPA/ServiceMonitor
    test "$(grep -c 'kind: NetworkPolicy' "${out}/default.yaml")" = "2"
    test "$(grep -c 'kind: PodDisruptionBudget' "${out}/default.yaml")" = "2"
    ! grep -q 'kind: Ingress' "${out}/default.yaml"
    ! grep -q 'kind: HorizontalPodAutoscaler' "${out}/default.yaml"
    ! grep -q 'kind: ServiceMonitor' "${out}/default.yaml"
    ! grep -q '0.0.0.0/0' "${out}/default.yaml"
    ! grep -q 'tag: latest' "${out}/default.yaml"
    ! grep -q ':latest' "${out}/default.yaml"
    # ingress+TLS：只指向 web Service
    helm template verify "${ROOT_DIR}/deploy/helm/veridex" -f "${ROOT_DIR}/deploy/helm/veridex/ci/ingress-values.yaml" > "${out}/ingress.yaml"
    grep -q 'kind: Ingress' "${out}/ingress.yaml"
    grep -q 'secretName: veridex-tls-test' "${out}/ingress.yaml"
    ! grep -A200 'kind: Ingress' "${out}/ingress.yaml" | grep -q '\-management'
    # HPA：渲染 HPA 且 Deployment 不再固定 replicas
    helm template verify "${ROOT_DIR}/deploy/helm/veridex" --set autoscaling.enabled=true > "${out}/hpa.yaml"
    grep -q 'kind: HorizontalPodAutoscaler' "${out}/hpa.yaml"
    ! grep -q 'replicas: 2' "${out}/hpa.yaml"
    # ServiceMonitor：只抓管理 Service 的命名端口
    helm template verify "${ROOT_DIR}/deploy/helm/veridex" --set serviceMonitor.enabled=true > "${out}/sm.yaml"
    grep -q 'kind: ServiceMonitor' "${out}/sm.yaml"
    grep -q 'port: management' "${out}/sm.yaml"
    grep -q 'veridex.io/management: "true"' "${out}/sm.yaml"
    # NetworkPolicy 关闭
    ! helm template verify "${ROOT_DIR}/deploy/helm/veridex" --set networkPolicy.enabled=false | grep -q 'kind: NetworkPolicy'
    # registry 重写与 digest 锁定
    helm template verify "${ROOT_DIR}/deploy/helm/veridex" --set global.imageRegistry=registry.corp.example \
      | grep -q 'registry.corp.example/veridex/backend:0.1.0'
    helm template verify "${ROOT_DIR}/deploy/helm/veridex" \
      --set backend.image.digest="sha256:$(printf 'a%.0s' $(seq 1 64))" \
      | grep -q 'veridex/backend@sha256:'
    # schema 负例：latest tag / 空 TLS secretName / 非法 egress type 必须渲染失败
    if helm template verify "${ROOT_DIR}/deploy/helm/veridex" --set backend.image.tag=latest >/dev/null 2>&1; then
      echo "values.schema must reject tag=latest" >&2; exit 1
    fi
    if helm template verify "${ROOT_DIR}/deploy/helm/veridex" \
        --set ingress.enabled=true --set ingress.tls.enabled=true --set ingress.tls.secretName= >/dev/null 2>&1; then
      echo "values.schema/required must reject empty tls.secretName" >&2; exit 1
    fi
    if helm template verify "${ROOT_DIR}/deploy/helm/veridex" \
        --set 'networkPolicy.externalEgress[0].type=bogus' >/dev/null 2>&1; then
      echo "networkpolicy template must reject unknown egress type" >&2; exit 1
    fi
    rm -rf "${out}"
  else
    echo "helm unavailable; lint/template matrix skipped (host-only mode)."
  fi
  # kind 集群验收（工具齐备时执行；ENFORCE_NETWORKPOLICY 可选）
  if have_docker && command -v kind >/dev/null 2>&1 && command -v kubectl >/dev/null 2>&1 && have_helm; then
    "${ROOT_DIR}/deploy/kind/run-acceptance.sh"
  else
    echo "kind/kubectl unavailable; cluster acceptance skipped (host-only mode)."
  fi
  echo "verify-deployment stage 4 (helm + cluster) passed."
}

stage5() {
  echo "==> [deploy-5] 离线交付包校验"
  local off="${ROOT_DIR}/deploy/offline/veridex-offline"
  for file in images.txt images.sha256 values/registry-values.yaml INSTALL.txt \
              ../scripts/export-images.sh ../scripts/import-images.sh ../scripts/push-images.sh; do
    test -f "${off}/${file}" || { echo "missing offline artifact: ${off}/${file}" >&2; exit 1; }
  done
  for script in export-images.sh import-images.sh push-images.sh; do
    grep -Fq 'set -euo pipefail' "${ROOT_DIR}/deploy/offline/scripts/${script}"
    test -x "${ROOT_DIR}/deploy/offline/scripts/${script}"
    bash -n "${ROOT_DIR}/deploy/offline/scripts/${script}"
    # 离线包内副本不得与仓库脚本漂移
    diff -q "${ROOT_DIR}/deploy/offline/scripts/${script}" "${off}/scripts/${script}" >/dev/null \
      || { echo "offline bundle script drifted: ${script}" >&2; exit 1; }
  done
  grep -Fq 'veridex-backend:' "${off}/images.txt"
  grep -Fq 'veridex-web:' "${off}/images.txt"
  # 有镜像时的 export→import 往返验收
  if have_docker && have_helm \
     && docker image inspect "${BACKEND_IMAGE}" >/dev/null 2>&1 \
     && docker image inspect "${WEB_IMAGE}" >/dev/null 2>&1; then
    local tmp; tmp="$(mktemp -d)"
    OFFLINE_DIR="${tmp}" "${ROOT_DIR}/deploy/offline/scripts/export-images.sh" >/dev/null
    OFFLINE_DIR="${tmp}" "${ROOT_DIR}/deploy/offline/scripts/import-images.sh" >/dev/null
    rm -rf "${tmp}"
  else
    echo "offline roundtrip skipped (images/helm unavailable)."
  fi
  echo "verify-deployment stage 5 (offline bundle) passed."
}

case "${STAGE}" in
  1) stage1 ;;
  2) stage2 ;;
  3) stage3 ;;
  4) stage4 ;;
  5) stage5 ;;
  all) stage1; stage2; stage3; stage4; stage5; echo "verify-deployment: all stages passed." ;;
  *) echo "unknown stage: ${STAGE}" >&2; exit 2 ;;
esac
