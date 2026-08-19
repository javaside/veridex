#!/usr/bin/env bash
set -euo pipefail

# 真实 Ollama Chat 显式验收（设计 §9.3）。不进默认 CI：
#   1) 需要外部 Ollama 可达（VERIDEX_OLLAMA_BASE_URL，默认 http://localhost:11434）
#   2) 需要 Compose 栈以 VERIDEX_CHAT_PROVIDER=ollama 运行（含 web 入口）
#   3) Ollama 需已安装 VERIDEX_OLLAMA_CHAT_MODEL（默认 qwen3:8b）
# 覆盖验收项：模型可达、首 delta 先于流结束、引用通过并完成、事件序列无 run.failed。
# 人工验收项（记录结果后人工确认，见文末清单）：MODEL_ERROR 无回退、CANCELLED、日志不泄露。

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OLLAMA_BASE="${VERIDEX_OLLAMA_BASE_URL:-http://localhost:11434}"
MODEL="${VERIDEX_OLLAMA_CHAT_MODEL:-qwen3:8b}"
WEB="${VERIDEX_WEB_URL:-http://127.0.0.1:8090}"
JAR=$(mktemp)
SSE_LOG=$(mktemp)
trap 'rm -f "${JAR}" "${SSE_LOG}"' EXIT
fail() { echo "verify-ollama-chat: $1" >&2; exit 1; }

echo "==> 1. 检查 Ollama 可达性（${OLLAMA_BASE}）"
if ! curl -fsS --max-time 5 "${OLLAMA_BASE}/api/tags" >/dev/null 2>&1; then
  echo "Ollama 不可达，跳过真实模型验收（默认 deterministic 不受影响）。"
  exit 0
fi
curl -fsS --max-time 10 "${OLLAMA_BASE}/api/tags" | grep -q "\"${MODEL}\"" \
  || fail "模型 ${MODEL} 未安装，请先: ollama pull ${MODEL}"

echo "==> 2. 登录并建立会话"
curl -fsS -c "${JAR}" "${WEB}/api/auth/csrf" >/dev/null || fail "csrf endpoint failed"
curl -fsS -b "${JAR}" -c "${JAR}" -X POST "${WEB}/api/auth/login?username=admin&password=veridex" >/dev/null \
  || fail "login failed"
XSRF=$(awk '$6 == "XSRF-TOKEN" {print $NF}' "${JAR}" | tail -n1)
test -n "${XSRF}" || fail "XSRF-TOKEN cookie missing"

echo "==> 3. 流式问答：首 answer.delta 先于流结束，最终 answer.completed"
# 需要至少一个已发布且当前账号可访问的知识库；smoke.sh 未建库，因此这里仅做协议层断言，
# 知识库种子数据由部署者预先准备（与 smoke 一致的 admin 账号）。
curl -NsS --max-time 120 -H "Content-Type: application/json" \
  -b "${JAR}" -H "X-XSRF-TOKEN: ${XSRF}" \
  -d '{"question":"请假几天","knowledgeBaseIds":[]}' \
  "${WEB}/api/qa/ask" | tee "${SSE_LOG}" | awk '
    /^event:/ { ev=substr($0,7); gsub(/\r/, "", ev); seq=seq " " ev }
    END { print "事件序列:" seq > "/dev/stderr" }
  ' 2>&1 || fail "SSE 请求失败，见 ${SSE_LOG}"

grep -q "event:answer.delta" "${SSE_LOG}" || fail "缺少 answer.delta（模型未流式返回或流为空）"
grep -q "event:answer.completed" "${SSE_LOG}" || fail "缺少 answer.completed（引用终检未通过或模型失败）"
grep -q "event:run.failed" "${SSE_LOG}" && fail "出现 run.failed"

python3 - "${SSE_LOG}" <<'PYEOF'
import sys
lines = open(sys.argv[1]).read().splitlines()
events = [l[6:].strip() for l in lines if l.startswith("event:")]
assert "answer.delta" in events, events
assert "answer.completed" in events, events
assert events.index("answer.delta") < events.index("answer.completed"), events
assert "run.failed" not in events, events
print("OK: 首 delta 先于 completed，无 run.failed，事件序列 =", events)
PYEOF

echo "==> 4. 人工验收清单（记录结果后人工确认）"
cat <<'MANUAL'
- [ ] 模型不可达时返回 run.failed（MODEL_ERROR/MODEL_TIMEOUT），无 deterministic 静默回退
- [ ] 客户端中断后 QueryRun 为 CANCELLED（查询 trace 页面验证）
- [ ] 默认日志/metrics/SSE 错误消息不泄露 prompt/completion/内部 URL
MANUAL

echo "verify-ollama-chat: all checks passed."
