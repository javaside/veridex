#!/usr/bin/env bash
set -euo pipefail

# 恢复 + OpenSearch 源重建（spec §4.2）。
# 用法: ./deploy/backup/restore.sh --backup backups/<stamp> [--fresh]
# --fresh: 删除并重建 postgres/opensearch 数据卷（需输入 YES 确认）
# 无 --fresh: 目标数据卷非空则拒绝（防误覆盖）。
#
# 实现说明（相对计划草案的实测修正）：
# 1. flyway_schema_history.version 是 text 列：`MAX(version)` 按字典序比较（'9' > '15'），
#    且与整数 0 作 COALESCE 直接类型报错；改为 `MAX(version::integer)`（与 backup.sh 一致）。
# 2. compose project 名为 veridex（compose.yml `name:` 声明），数据卷实测为
#    veridex_postgres-data / veridex_opensearch-data；`docker compose down -v postgres
#    opensearch`（实测 --dry-run 确认）只删除这两个服务的容器与其数据卷，不动 minio 等。
# 3. compose run 用镜像默认 entrypoint（java -jar），仅注入 reindex profile；跑完 --rm 即退。
# 4. psql 取值统一用外层单引号 + 内层双引号（backup.sh 同款）：草案的双层双引号
#    转义在 set -u 下会被外层 bash 提前展开报错，导致非空卷守卫失效；现改为 fail-closed——
#    先 up/等 postgres 就绪再查表数，查不到即中止，杜绝误覆盖。
# 5. information_schema 查询用 `current_schema()`（函数需括号，草案漏写）。

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STACK="${ROOT_DIR}/deploy/compose"
COMPOSE=(docker compose -f "${STACK}/compose.yml")
BACKUP_DIR=""
FRESH=0

while [ $# -gt 0 ]; do
  case "$1" in
    --backup) BACKUP_DIR="$2"; shift 2 ;;
    --fresh) FRESH=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

fail() { echo "restore: $1" >&2; exit 1; }
[ -n "${BACKUP_DIR}" ] && [ -d "${BACKUP_DIR}" ] || fail "--backup <dir> 必填且存在"
[ -f "${BACKUP_DIR}/manifest.json" ] || fail "manifest.json 缺失（无 manifest = 无有效备份）"
[ -f "${BACKUP_DIR}/postgres.dump" ] || fail "postgres.dump 缺失"
[ -d "${BACKUP_DIR}/objects" ] || fail "objects/ 缺失"

echo "==> 1/6 校验备份完整性"
( cd "${BACKUP_DIR}" && shasum -a 256 -c SHA256SUMS --quiet ) || fail "SHA-256 校验失败"
BACKUP_SCHEMA="$(python3 -c "import json;print(json.load(open('${BACKUP_DIR}/manifest.json'))['schema_version'])")"

echo "==> 2/6 目标卷状态检查"
if [ "${FRESH}" = "1" ]; then
  echo "警告: --fresh 将删除 postgres/opensearch 全部数据。输入 YES 确认:"
  read -r CONFIRM
  [ "${CONFIRM}" = "YES" ] || fail "未确认，中止"
  "${COMPOSE[@]}" down -v postgres opensearch >/dev/null 2>&1 || true
  docker volume rm -f "${COMPOSE_PROJECT_VOLUME_PREFIX:-veridex}_postgres-data" \
    "${COMPOSE_PROJECT_VOLUME_PREFIX:-veridex}_opensearch-data" >/dev/null 2>&1 || true
else
  # 非空卷拒绝恢复（fail-closed）：先确保 postgres 可达再查表数，查不到即中止，
  # 避免守卫因 shell 引用/容器未起而失效导致误覆盖（实测修正）。
  "${COMPOSE[@]}" up -d postgres >/dev/null 2>&1 || true
  "${COMPOSE[@]}" exec -T postgres sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"' >/dev/null \
    || fail "postgres 不可达，无法校验目标库状态"
  PG_ROWS="$("${COMPOSE[@]}" exec -T postgres sh -c \
    'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=current_schema()"' 2>/dev/null | tr -d '[:space:]')"
  [ -n "${PG_ROWS}" ] || fail "无法读取目标库表数，中止（防误覆盖）"
  [ "${PG_ROWS}" = "0" ] || fail "目标数据库非空（${PG_ROWS} 张表）；确要覆盖请用 --fresh"
fi

echo "==> 3/6 起基础设施并恢复 PostgreSQL"
"${COMPOSE[@]}" up -d postgres minio rabbitmq opensearch
"${COMPOSE[@]}" exec -T postgres sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"' >/dev/null
"${COMPOSE[@]}" exec -T postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid()" >/dev/null; \
   psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public"' >/dev/null
"${COMPOSE[@]}" exec -T postgres sh -c \
  'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --no-privileges' < "${BACKUP_DIR}/postgres.dump" \
  || fail "pg_restore 失败"

echo "==> 4/6 校验迁移版本兼容"
RESTORED_SCHEMA="$("${COMPOSE[@]}" exec -T postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT COALESCE(MAX(version::integer),0) FROM flyway_schema_history WHERE success"' \
  | tr -d '[:space:]')"
[ "${RESTORED_SCHEMA}" -le "${BACKUP_SCHEMA}" ] \
  || fail "恢复出的 schema(${RESTORED_SCHEMA}) 高于备份(${BACKUP_SCHEMA})，数据异常"

echo "==> 5/6 MinIO 对象回灌"
MINIO_CID="$("${COMPOSE[@]}" ps -q minio)"
find "${BACKUP_DIR}/objects" -type f | while IFS= read -r local; do
  rel="${local#${BACKUP_DIR}/objects/}"
  docker exec "${MINIO_CID}" mkdir -p "/data/veridex-documents/$(dirname "${rel}")"
  docker cp "${local}" "${MINIO_CID}:/data/veridex-documents/${rel}" || fail "对象回灌失败: ${rel}"
done
echo "    回灌 $(find "${BACKUP_DIR}/objects" -type f | wc -l | tr -d ' ') 个对象"

echo "==> 6/6 OpenSearch 源重建（一次性 reindex 容器）"
START_NS=$(date +%s)
# compose run 用镜像默认 entrypoint（java -jar），仅注入 profile；跑完 --rm 即退
"${COMPOSE[@]}" run --rm --no-deps \
  -e SPRING_PROFILES_ACTIVE=reindex \
  -e VERIDEX_MANAGEMENT_ADDRESS=127.0.0.1 \
  backend \
  || fail "reindex 容器失败（可重跑，publish 幂等）"
END_NS=$(date +%s)
echo "restore: 完成（reindex 耗时 $((END_NS - START_NS))s，RTO 主体）"
