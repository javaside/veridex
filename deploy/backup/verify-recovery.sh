#!/usr/bin/env bash
set -euo pipefail

# 恢复演练（spec §4.3，5-e 退出门禁显式步骤，不进 verify.sh）：
# 起栈 → 种子数据（建库/传文档/发布/问答）→ 备份 → --fresh 销毁 → 恢复 → smoke + 抽查 → RTO 报告。
#
# RTO 语义：销毁（restore --fresh 内部 down -v）→ 恢复后 smoke 通过 的总时长。
#
# 相对计划草案的实测修正（命令语义以实际 Controller 为准，断言链不变）：
# 1. 文档状态轮询不走 /documents（DocumentView 无 status 字段），改走
#    GET /api/documents/{documentId}/versions（DocumentVersionView.status）；
#    上传响应是版本视图（version id），文档 id 从库内文档列表取。
# 2. 问答 SSE 事件名为 answer.delta / answer.completed（非 answer），按实际事件断言。
# 3. 上传端点 multipart 字段名为 file（DocumentController @RequestParam("file")）。

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STACK="${ROOT_DIR}/deploy/compose"
COMPOSE=(docker compose -f "${STACK}/compose.yml")
WEB="${VERIDEX_WEB_URL:-http://127.0.0.1:8090}"
JAR=$(mktemp)
DOC_FILE="/tmp/drill-doc-$$.md"
DRILL_LOG="$(mktemp)"
trap 'rm -f "${JAR}" "${DOC_FILE}" "${DRILL_LOG}"' EXIT
fail() { echo "verify-recovery: $1" >&2; exit 1; }

echo "==> [drill-1] 起完整应用栈"
"${COMPOSE[@]}" up -d --build backend web
for i in $(seq 1 60); do curl -fsS -m 10 "${WEB}/healthz" >/dev/null 2>&1 && break; sleep 5; done
curl -fsS -m 10 "${WEB}/healthz" >/dev/null || fail "web 未就绪"

echo "==> [drill-2] 灌种子数据"
curl -fsS -m 10 -c "${JAR}" "${WEB}/api/auth/csrf" >/dev/null
curl -fsS -m 10 -b "${JAR}" -c "${JAR}" -X POST "${WEB}/api/auth/login?username=admin&password=veridex" >/dev/null
XSRF=$(awk '$6 == "XSRF-TOKEN" {print $NF}' "${JAR}" | tail -n1)
# 建库
KB_ID=$(curl -fsS -m 30 -b "${JAR}" -H "X-XSRF-TOKEN: ${XSRF}" -H "Content-Type: application/json" \
  -X POST "${WEB}/api/knowledge-bases" -d '{"name":"恢复演练库","description":"drill"}' \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['id'])")
# 上传文档（markdown，走现有解析/摄取；空 KB 边沿：保证该库 ≥1 READY 版本才发布）。
# 内容须 ≥50 字：RefusalPolicy 对证据总字符 <50 判 INSUFFICIENT_EVIDENCE（拒答，无 answer.delta），
# 演练实测短句（~26 字）被拒答；此处与 QaTestFixture.LEAVE_CHUNK 同文，走通生成/引用链路。
printf '员工请假需提前两个工作日向直属主管提交书面申请，经审批后生效；连续请假超过五个工作日的，还需报人力资源部备案。\n' > "${DOC_FILE}"
UPLOAD=$(curl -fsS -m 30 -b "${JAR}" -H "X-XSRF-TOKEN: ${XSRF}" -F "file=@${DOC_FILE};type=text/markdown" \
  "${WEB}/api/knowledge-bases/${KB_ID}/documents") || fail "上传文档失败"
VERSION_ID=$(printf '%s' "${UPLOAD}" | python3 -c "import json,sys;print(json.load(sys.stdin)['id'])")
# 文档 id：上传响应是版本视图，从库内文档列表取（本演练只建一库一文档）
DOC_ID=$(curl -fsS -m 30 -b "${JAR}" "${WEB}/api/knowledge-bases/${KB_ID}/documents" \
  | python3 -c "import json,sys;print(json.load(sys.stdin)[0]['id'])")
# 等待摄取 READY（轮询该文档的版本状态）
STATUS=""
for i in $(seq 1 60); do
  STATUS=$(curl -fsS -m 10 -b "${JAR}" "${WEB}/api/documents/${DOC_ID}/versions" 2>/dev/null | \
    python3 -c "import json,sys
vs=json.load(sys.stdin)
print(next((v['status'] for v in vs if v['id']=='${VERSION_ID}'), ''))" 2>/dev/null || true)
  [ "${STATUS}" = "READY" ] && break
  sleep 2
done
[ "${STATUS}" = "READY" ] || fail "文档未 READY（status=${STATUS}）"
# 发布（KnowledgeBasePublishService 对无 READY 版本必抛，种子已保证 ≥1 READY）
curl -fsS -m 60 -b "${JAR}" -H "X-XSRF-TOKEN: ${XSRF}" -X POST \
  "${WEB}/api/knowledge-bases/${KB_ID}/releases/publish" >/dev/null || fail "发布失败"
echo "    种子完成: kb=${KB_ID} version=${VERSION_ID}"

echo "==> [drill-3] 备份"
"${ROOT_DIR}/deploy/backup/backup.sh" --out "${ROOT_DIR}/backups" || fail "备份失败"
BACKUP_DIR="$(ls -1dt "${ROOT_DIR}/backups"/[0-9]* | head -1)"

echo "==> [drill-4] 销毁数据卷并恢复"
DRILL_START=$(date +%s)
echo YES | "${ROOT_DIR}/deploy/backup/restore.sh" --backup "${BACKUP_DIR}" --fresh 2>&1 | tee "${DRILL_LOG}" \
  || fail "恢复失败"
grep -q "restore: 完成" "${DRILL_LOG}" || fail "恢复日志缺少完成标记"

echo "==> [drill-5] 恢复后验收"
"${COMPOSE[@]}" up -d backend web
for i in $(seq 1 60); do curl -fsS -m 10 "${WEB}/healthz" >/dev/null 2>&1 && break; sleep 5; done
curl -fsS -m 10 "${WEB}/healthz" >/dev/null || fail "web 未就绪"
VERIDEX_WEB_URL="${WEB}" "${STACK}/smoke.sh" || fail "恢复后 smoke 失败"
DRILL_END=$(date +%s)
# 抽查：恢复的库可检索问答（引用链路自证；会话/CSRF 随 pg_dump 一并恢复）
SSE=$(curl -NsS -m 180 -b "${JAR}" -H "X-XSRF-TOKEN: ${XSRF}" -H "Content-Type: application/json" \
  -d "{\"question\":\"请假提前几天\",\"knowledgeBaseIds\":[\"${KB_ID}\"]}" "${WEB}/api/qa/ask") \
  || fail "恢复后问答失败"
if ! echo "${SSE}" | grep -q "event:answer.delta"; then
  echo "verify-recovery: SSE 摘要（前 40 行）:" >&2
  echo "${SSE}" | head -40 >&2
  fail "恢复后问答未返回答案（SSE 无 answer.delta 事件）"
fi
echo "${SSE}" | grep -q "event:answer.completed" || echo "warn: 未见 answer.completed（可能是拒答，人工核对）"

RTO=$((DRILL_END - DRILL_START))
echo "verify-recovery: 全流程通过。RTO=${RTO}s（备份目录 ${BACKUP_DIR}）"
echo "    请将本次演练时间与 RTO 记录到 docs/capacity-report-2026-08.md §5"
