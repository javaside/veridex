#!/usr/bin/env bash
set -euo pipefail

# kind 集群验收：临时集群 → 导入本地镜像 → 部署测试依赖 → 安装 chart →
# 冒烟 → Pod 删除恢复 → 滚动升级无不可用窗口 → Ingress 开关 → 清理。
# 依赖: docker、kind、kubectl、helm。
# 环境变量:
#   SKIP_KIND_DELETE=1        结束后保留集群（排障）
#   ENFORCE_NETWORKPOLICY=1   安装 Calico 并执行 NetworkPolicy 拒绝测试
#                             （kind 默认 CNI kindnet 不执行 NetworkPolicy）
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CLUSTER="veridex-acceptance"
NS="veridex-test"
RELEASE="verify"
FULLNAME="${RELEASE}-veridex"
WEB_PORT="18080"

for tool in docker kind kubectl helm; do
  command -v "${tool}" >/dev/null 2>&1 || { echo "run-acceptance: ${tool} not installed" >&2; exit 2; }
done

cleanup() {
  if [ -n "${PF_PID:-}" ]; then kill "${PF_PID}" 2>/dev/null || true; fi
  if [ "${SKIP_KIND_DELETE:-0}" = "1" ]; then
    echo "run-acceptance: keeping cluster ${CLUSTER} (SKIP_KIND_DELETE=1)"
  else
    kind delete cluster --name "${CLUSTER}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

echo "==> [kind] create cluster"
CLUSTER_CONFIG=$(cat <<YAML
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
networking:
  disableDefaultCNI: $( [ "${ENFORCE_NETWORKPOLICY:-0}" = "1" ] && echo true || echo false )
# 本机 DNS 污染（registry-1.docker.io 被解析到假 IP）：kind 节点内 containerd
# 不走 docker daemon mirror，需在此显式声明镜像站；干净环境同样适用（仅添加镜像源）。
containerdConfigPatches:
- |-
  [plugins."io.containerd.grpc.v1.cri".registry.mirrors."docker.io"]
    endpoint = ["https://docker.m.daocloud.io"]
YAML
)
kind create cluster --name "${CLUSTER}" --config - <<<"${CLUSTER_CONFIG}"

if [ "${ENFORCE_NETWORKPOLICY:-0}" = "1" ]; then
  echo "==> [kind] install calico (NetworkPolicy enforcement)"
  kubectl apply -f https://raw.githubusercontent.com/projectcalico/calico/v3.28.2/manifests/calico.yaml
  kubectl -n kube-system wait pods -l k8s-app=calico-node --for=condition=Ready --timeout=300s
fi

echo "==> [kind] load images"
kind load docker-image veridex-backend:0.1.0 veridex-web:0.1.0 --name "${CLUSTER}"

echo "==> [kind] deploy test dependencies"
echo "==> [kind] pre-pull dependency images via host docker + ctr import"
# 依赖镜像先经宿主机 docker daemon（已配置 mirror）拉取，再 docker save 管道导入节点：
# 1) 本机 DNS 污染下节点内 containerd 直连不可靠；
# 2) kind load 用 --all-platforms 需宿主机具备全平台 blob（本机仅 arm64 单平台会 digest 缺失），
#    docker save 导出单平台 tar，ctr import 接受。
NODE_IMAGES=(
  postgres:17-alpine
  rabbitmq:4-alpine
  minio/minio:RELEASE.2025-07-23T15-54-02Z
  opensearchproject/opensearch:3.2.0
)
for image in "${NODE_IMAGES[@]}"; do
  echo "    pulling docker.io/${image} (host) ..."
  docker pull "${image}" || { echo "run-acceptance: host pull failed for ${image}" >&2; exit 1; }
  docker save "${image}" | docker exec -i "${CLUSTER}-control-plane" \
    ctr --namespace=k8s.io images import - \
    || { echo "run-acceptance: node import failed for ${image}" >&2; exit 1; }
done
kubectl create namespace "${NS}"
kubectl -n "${NS}" apply -f "${ROOT_DIR}/deploy/kind/test-deps.yaml"
kubectl -n "${NS}" wait pod/veridex-test-deps --for=condition=Ready --timeout=600s

echo "==> [kind] install chart"
helm upgrade --install "${RELEASE}" "${ROOT_DIR}/deploy/helm/veridex" \
  --namespace "${NS}" -f "${ROOT_DIR}/deploy/kind/kind-values.yaml" \
  --wait --timeout 10m
kubectl -n "${NS}" rollout status "deploy/${FULLNAME}-backend" --timeout=600s
kubectl -n "${NS}" rollout status "deploy/${FULLNAME}-web" --timeout=300s

echo "==> [kind] smoke via port-forward"
kubectl -n "${NS}" port-forward "svc/${FULLNAME}-web" "${WEB_PORT}:80" >/dev/null 2>&1 &
PF_PID=$!
WEB_READY=0
for attempt in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${WEB_PORT}/healthz" >/dev/null 2>&1; then WEB_READY=1; break; fi
  sleep 1
done
test "${WEB_READY}" = "1" || { echo "run-acceptance: web port-forward not ready" >&2; exit 1; }
VERIDEX_WEB_URL="http://127.0.0.1:${WEB_PORT}" "${ROOT_DIR}/deploy/compose/smoke.sh"

echo "==> [kind] pod deletion recovery"
kubectl -n "${NS}" delete pod -l "app.kubernetes.io/component=backend,app.kubernetes.io/instance=${RELEASE}" --wait=false >/dev/null
kubectl -n "${NS}" rollout status "deploy/${FULLNAME}-backend" --timeout=600s
curl -fsS "http://127.0.0.1:${WEB_PORT}/healthz" >/dev/null

echo "==> [kind] rolling upgrade without downtime"
FAIL_FILE=$(mktemp)
( for i in $(seq 1 60); do
    curl -fsS -m 2 "http://127.0.0.1:${WEB_PORT}/healthz" >/dev/null || echo down >>"${FAIL_FILE}"
    sleep 1
  done ) &
PROBE_PID=$!
helm upgrade --install "${RELEASE}" "${ROOT_DIR}/deploy/helm/veridex" \
  --namespace "${NS}" -f "${ROOT_DIR}/deploy/kind/kind-values.yaml" \
  --set-string backend.podAnnotations.rolloutProbe="$(date +%s)" --wait --timeout 10m
wait "${PROBE_PID}"
test ! -s "${FAIL_FILE}" || { echo "rolling upgrade had downtime:" >&2; cat "${FAIL_FILE}" >&2; exit 1; }
rm -f "${FAIL_FILE}"

echo "==> [kind] ingress toggle"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=veridex.test" \
  -keyout /tmp/veridex-tls.key -out /tmp/veridex-tls.crt >/dev/null 2>&1
kubectl -n "${NS}" create secret tls veridex-tls --key /tmp/veridex-tls.key --cert /tmp/veridex-tls.crt
rm -f /tmp/veridex-tls.key /tmp/veridex-tls.crt
helm upgrade --install "${RELEASE}" "${ROOT_DIR}/deploy/helm/veridex" \
  --namespace "${NS}" -f "${ROOT_DIR}/deploy/kind/kind-values.yaml" -f "${ROOT_DIR}/deploy/helm/veridex/ci/ingress-values.yaml" \
  --set ingress.hostname=veridex.test --wait --timeout 10m
test "$(kubectl -n "${NS}" get ingress -o name)" = "ingress.networking.k8s.io/${FULLNAME}-web"
# 管理 Service 不出现在任何 Ingress 规则里
! kubectl -n "${NS}" get ingress -o jsonpath='{.items[*].spec.rules[*].http.paths[*].backend.service.name}' | grep -q management

if [ "${ENFORCE_NETWORKPOLICY:-0}" = "1" ]; then
  echo "==> [kind] networkpolicy denial"
  # 未授权 Pod 访问 backend 业务端口必须失败
  if kubectl -n "${NS}" run np-probe --rm -i --restart=Never --image=curlimages/curl:8.10.1 \
      --command -- curl -fsS -m 3 "http://${FULLNAME}-backend:8080/api/auth/csrf" >/dev/null 2>&1; then
    echo "networkpolicy must deny unauthorized ingress to backend" >&2; exit 1
  fi
  # web 到 backend 的合法路径仍然可用
  kubectl -n "${NS}" exec "deploy/${FULLNAME}-web" -c web -- \
    curl -fsS -m 3 "http://${FULLNAME}-backend:8080/api/auth/csrf" >/dev/null
else
  echo "==> [kind] networkpolicy enforcement checks skipped (set ENFORCE_NETWORKPOLICY=1)"
fi

kill "${PF_PID}" 2>/dev/null || true
echo "kind acceptance: all checks passed."
