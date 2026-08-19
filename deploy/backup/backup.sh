#!/usr/bin/env bash
set -euo pipefail

# PG + MinIO 一致性备份（Phase 5-e spec §4.1）。OpenSearch 不备份（恢复走源重建，spec D1）。
# 用法: ./deploy/backup/backup.sh [--stack <dir>] [--out <dir>]
# 产物: <out>/<timestamp>/{manifest.json, postgres.dump, objects/<对象键>, SHA256SUMS}
# 失败: 非零退出并清理半成品目录（trap），无 manifest 即无有效备份。
#
# 实现说明（相对计划草案的两处实测修正）：
# 1. MinIO 对象导出不用 docker cp 逐对象拷贝 /data：单机模式下 /data 是 XL 内部格式
#    （对象为目录 + xl.meta），直接拷会得到内部元数据而非对象内容；且 minio 镜像内置
#    mc、无 find/tar。改为容器内 `mc mirror` 导出到 /tmp 暂存再一次性 docker cp 拷出
#    （实测 321MiB 亚秒级完成），对象键结构原样保留。
# 2. flyway_schema_history.version 是 text 列：`MAX(version)` 按字典序比较（'9' > '15'），
#    且与整数 0 作 COALESCE 直接类型报错；改为 `MAX(version::integer)`。

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STACK="${ROOT_DIR}/deploy/compose"
OUT_DIR="${ROOT_DIR}/backups"
BUCKET="veridex-documents"
# 容器内导出暂存目录（导出后即清理，失败也由 cleanup 回收）。
MINIO_STAGE="/tmp/veridex-backup-stage"

while [ $# -gt 0 ]; do
  case "$1" in
    --stack) STACK="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
COMPOSE=(docker compose -f "${STACK}/compose.yml")

STAMP="$(date +%Y%m%dT%H%M%S)"
BACKUP_DIR="${OUT_DIR}/${STAMP}"
TMP_DIR=""

# 用 EXIT trap 而非 ERR trap：`cmd || fail` 的 || 上下文会抑制 ERR trap，
# fail() 内的显式 exit 也不触发它；EXIT 对成功/失败路径都收敛到这里，
# 成功时 TMP_DIR 已 mv 为正式目录，[ -d ] 判空即无操作。
cleanup() {
  # 容器内暂存尽力回收（栈已停也不至于误报失败）。
  "${COMPOSE[@]}" exec -T minio sh -c "rm -rf '${MINIO_STAGE}'" >/dev/null 2>&1 || true
  if [ -n "${TMP_DIR:-}" ] && [ -d "${TMP_DIR}" ]; then
    echo "backup: 失败，清理半成品 ${TMP_DIR}" >&2
    rm -rf "${TMP_DIR}"
  fi
}
trap cleanup EXIT

fail() { echo "backup: $1" >&2; exit 1; }

echo "==> 校验目标栈在运行（postgres/minio）"
for svc in postgres minio; do
  "${COMPOSE[@]}" ps "${svc}" --format '{{.State}}' 2>/dev/null | grep -qx running \
    || fail "${svc} 容器未运行（先 docker compose -f ${STACK}/compose.yml up -d）"
done

mkdir -p "${OUT_DIR}"
TMP_DIR="$(mktemp -d "${OUT_DIR}/.partial-${STAMP}-XXXXXX")"
mkdir -p "${TMP_DIR}/objects"

echo "==> 1/4 PostgreSQL 逻辑备份（pg_dump -Fc）"
# 凭据取 postgres 容器自身环境（compose environment 注入），宿主机无需配置。
"${COMPOSE[@]}" exec -T postgres sh -c \
  'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "${TMP_DIR}/postgres.dump" \
  || fail "pg_dump 失败"
[ -s "${TMP_DIR}/postgres.dump" ] || fail "pg_dump 产物为空"

echo "==> 2/4 读取迁移版本与活跃发布状态"
SCHEMA_VERSION="$("${COMPOSE[@]}" exec -T postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT COALESCE(MAX(version::integer),0) FROM flyway_schema_history WHERE success"' \
  | tr -d '[:space:]')"
ACTIVE_RELEASES="$("${COMPOSE[@]}" exec -T postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT knowledge_base_id || '"'"':'"'"' || index_name FROM index_release WHERE is_active"' \
  | tr '\n' ';' )"

echo "==> 3/4 MinIO 对象导出（${BUCKET}）"
# 凭据取 minio 容器自身环境变量；缺失时回退 compose 默认 veridex/veridex-local-secret。
"${COMPOSE[@]}" exec -T -e BUCKET="${BUCKET}" -e STAGE="${MINIO_STAGE}" minio sh -c '
  export MC_HOST_bk="http://${MINIO_ROOT_USER:-veridex}:${MINIO_ROOT_PASSWORD:-veridex-local-secret}@localhost:9000"
  mc stat "bk/${BUCKET}" >/dev/null
  rm -rf "${STAGE}"
  mkdir -p "${STAGE}"
  # --quiet 仍向 stdout 打印每个对象一行（1M 对象场景不可接受），stdout 丢弃、
  # 错误经退出码/stderr 上抛。
  mc mirror --quiet "bk/${BUCKET}" "${STAGE}" >/dev/null
' || fail "MinIO 对象导出失败（mc mirror）"

MINIO_CID="$("${COMPOSE[@]}" ps -q minio)"
docker cp "${MINIO_CID}:${MINIO_STAGE}/." "${TMP_DIR}/objects/" \
  || fail "导出对象到宿主机失败（docker cp）"
"${COMPOSE[@]}" exec -T minio sh -c "rm -rf '${MINIO_STAGE}'" >/dev/null 2>&1 || true

OBJECT_COUNT="$(find "${TMP_DIR}/objects" -type f | wc -l | tr -d '[:space:]')"
echo "    导出 ${OBJECT_COUNT} 个对象"

echo "==> 4/4 manifest 与校验和"
# SHA256SUMS 覆盖 postgres.dump 与全部对象；manifest.json 自身不参与校验（写在其后），
# .sums 须排除——find 执行时 .sums 已被本命令的重定向创建，纳入会自引用且内容不定。
( cd "${TMP_DIR}" \
  && find . -type f ! -name manifest.json ! -name .sums -print0 \
  | xargs -0 shasum -a 256 > "${TMP_DIR}/.sums" )

APP_VERSION="$( (cd "${ROOT_DIR}" && git rev-parse --short HEAD 2>/dev/null) || echo unknown )"
cat > "${TMP_DIR}/manifest.json" <<EOF
{
  "created_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "app_version": "${APP_VERSION}",
  "schema_version": ${SCHEMA_VERSION:-0},
  "active_releases": "${ACTIVE_RELEASES}",
  "postgres_dump": "postgres.dump",
  "object_count": ${OBJECT_COUNT},
  "checksums_file": "SHA256SUMS"
}
EOF
mv "${TMP_DIR}/.sums" "${TMP_DIR}/SHA256SUMS"

echo "==> 原子发布备份目录"
mv "${TMP_DIR}" "${BACKUP_DIR}"
trap - EXIT
echo "backup: 完成 ${BACKUP_DIR}（schema_version=${SCHEMA_VERSION}, objects=${OBJECT_COUNT}）"
