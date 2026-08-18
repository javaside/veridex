#!/usr/bin/env bash
set -euo pipefail

# 校验 SHA-256 后 docker load 离线镜像，并确认 images.txt 全部就位。
# 目录 OFFLINE_DIR（默认仓库内 deploy/offline/veridex-offline）。
# 校验/载入以 OFFLINE_DIR 为准；images.txt 优先取 OFFLINE_DIR（目标环境包内自带）。
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
OFFLINE_DIR="${OFFLINE_DIR:-${ROOT_DIR}/deploy/offline/veridex-offline}"
IMAGES_FILE="${OFFLINE_DIR}/images.txt"
if [ ! -f "${IMAGES_FILE}" ] && [ -f "${ROOT_DIR}/deploy/offline/veridex-offline/images.txt" ]; then
  IMAGES_FILE="${ROOT_DIR}/deploy/offline/veridex-offline/images.txt"
fi

sum_check() {
  if command -v sha256sum >/dev/null 2>&1; then (cd "${OFFLINE_DIR}" && sha256sum -c "$1"); \
  else (cd "${OFFLINE_DIR}" && shasum -a 256 -c "$1"); fi
}

command -v docker >/dev/null 2>&1 || { echo "import-images: docker required" >&2; exit 1; }
test -f "${OFFLINE_DIR}/images.sha256" || { echo "import-images: missing images.sha256" >&2; exit 1; }

# 过滤注释行后校验
checks="$(mktemp)"
grep -v '^#' "${OFFLINE_DIR}/images.sha256" > "${checks}"
test -s "${checks}" || { echo "import-images: images.sha256 has no checksum entries" >&2; exit 1; }
sum_check "${checks}"
rm -f "${checks}"

for tar in "${OFFLINE_DIR}"/images/*.tar; do
  test -f "${tar}" || continue
  docker load -i "${tar}"
done

# images.txt 与本地镜像一致性
while read -r image; do
  case "${image}" in '' | '#'*) continue ;; esac
  docker image inspect "${image}" >/dev/null 2>&1 \
    || { echo "import-images: image missing after load: ${image}" >&2; exit 1; }
done < "${IMAGES_FILE}"
echo "import-images: all images verified and loaded."
