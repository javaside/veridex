#!/usr/bin/env bash
set -euo pipefail

# 把 images.txt 中的本地镜像重标记并推送到私有 registry（REGISTRY 必填）。
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
OFFLINE_DIR="${OFFLINE_DIR:-${ROOT_DIR}/deploy/offline/veridex-offline}"
IMAGES_FILE="${ROOT_DIR}/deploy/offline/veridex-offline/images.txt"
if [ ! -f "${IMAGES_FILE}" ]; then
  IMAGES_FILE="${OFFLINE_DIR}/images.txt"
fi

: "${REGISTRY:?push-images: REGISTRY must be set, e.g. REGISTRY=registry.corp.example:5000 $0}"
command -v docker >/dev/null 2>&1 || { echo "push-images: docker required" >&2; exit 1; }
test -f "${IMAGES_FILE}" || { echo "push-images: missing ${IMAGES_FILE}" >&2; exit 1; }

while read -r image; do
  case "${image}" in '' | '#'*) continue ;; esac
  docker image inspect "${image}" >/dev/null 2>&1 \
    || { echo "push-images: image not built locally: ${image}" >&2; exit 1; }
  target="${REGISTRY}/${image}"
  docker tag "${image}" "${target}"
  docker push "${target}"
  echo "pushed ${target}"
done < "${IMAGES_FILE}"

cat <<EOF
push-images: done。
安装 chart 时合并 values/registry-values.yaml 并设置 global.imageRegistry=${REGISTRY}
（注意：registry 前缀不要包含协议 scheme）。
EOF
