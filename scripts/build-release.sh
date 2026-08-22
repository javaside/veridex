#!/usr/bin/env bash
set -euo pipefail

# 固化的发布包构建脚本：构建镜像 → 生成离线交付物 → 打包成 tar.gz 到 dist/。
#
# 用法：
#   ./scripts/build-release.sh [VERSION]
#
#   VERSION 缺省时从 deploy/helm/veridex/Chart.yaml 的 version 读取。
#   产物输出到 <repo>/dist/veridex-<VERSION>-offline.tar.gz（dist/ 已 git 忽略）。
#
# 环境变量：
#   SKIP_BUILD=1        跳过镜像构建（复用本地已构建镜像）
#   OFFLINE_DIR=<path>  覆盖离线物生成目录（默认 deploy/offline/veridex-offline）

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHART_DIR="${ROOT_DIR}/deploy/helm/veridex"
DIST_DIR="${ROOT_DIR}/dist"
OFFLINE_DIR="${OFFLINE_DIR:-${ROOT_DIR}/deploy/offline/veridex-offline}"
IMAGES_FILE="${OFFLINE_DIR}/images.txt"

command -v docker >/dev/null 2>&1 || { echo "build-release: docker required" >&2; exit 1; }

# 版本号：参数 > Chart.yaml version。统一去掉前导 v（v0.1.0 → 0.1.0），
# 产物名与镜像 tag 都使用不带 v 的版本号（images.txt / Chart.yaml 均不带 v）。
if [[ $# -ge 1 ]]; then
  VERSION="$1"
else
  VERSION="$(awk '/^version:/ {print $2; exit}' "${CHART_DIR}/Chart.yaml")"
fi
VERSION="${VERSION#v}"
[[ -n "${VERSION}" ]] || { echo "build-release: unable to determine VERSION" >&2; exit 1; }

# 镜像名从 images.txt 解析（去掉注释/空行）。列表里的镜像名即为构建目标。
IMAGES=()
while read -r image; do
  case "${image}" in '' | '#'*) continue ;; esac
  IMAGES+=("${image}")
done < "${IMAGES_FILE}"
[[ ${#IMAGES[@]} -gt 0 ]] || { echo "build-release: no images in ${IMAGES_FILE}" >&2; exit 1; }

# 1. 构建镜像（如未跳过）
if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  echo "==> 构建镜像"
  # backend 与 web 的 Dockerfile 分别在 backend/Dockerfile 与 web/Dockerfile，
  # 构建上下文均为仓库根。这里按 images.txt 里的镜像名反推组件名。
  for image in "${IMAGES[@]}"; do
    component="${image%%:*}"
    tag="${image##*:}"
    case "${component}" in
      veridex-backend) dockerfile="backend/Dockerfile" ;;
      veridex-web)     dockerfile="web/Dockerfile" ;;
      *) echo "build-release: unknown image component ${component}" >&2; exit 1 ;;
    esac
    echo "==> 构建 ${image}（${dockerfile}）"
    docker build -f "${dockerfile}" -t "${image}" "${ROOT_DIR}"
  done
else
  echo "==> 跳过镜像构建（SKIP_BUILD=1）"
fi

# 2. 生成离线交付物（镜像 tar + helm chart + sha256），版本注入到镜像 tag 与 chart。
#    export-images.sh 内部读取 images.txt 与 Chart.yaml，此处无需重复传版本。
echo "==> 生成离线交付物"
"${ROOT_DIR}/deploy/offline/scripts/export-images.sh"

# 3. 打包成 tar.gz 到 dist/
mkdir -p "${DIST_DIR}"
BUNDLE_DIR="$(basename "${OFFLINE_DIR}")"
ARCHIVE="${DIST_DIR}/veridex-${VERSION}-offline.tar.gz"
echo "==> 打包 ${ARCHIVE}"
tar -czf "${ARCHIVE}" -C "$(dirname "${OFFLINE_DIR}")" "${BUNDLE_DIR}"
echo "==> SHA256"
(cd "${DIST_DIR}" && shasum -a 256 "$(basename "${ARCHIVE}")") | tee "${ARCHIVE}.sha256"

echo "build-release: done -> ${ARCHIVE}"
