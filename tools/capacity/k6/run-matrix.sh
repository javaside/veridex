#!/usr/bin/env bash
set -euo pipefail
# 梯度矩阵（spec §4.5）：deterministic 与 ollama 双曲线 × 5/20/50 VU；可选 WITH_EVAL=1 评测干扰组。
#
# 用法示例：
#   KB_IDS=<kb1>,<kb2> ./run-matrix.sh                                    # deterministic × 5/20/50
#   VERIDEX_CHAT_PROVIDER=ollama KB_IDS=... ./run-matrix.sh               # ollama 曲线（backend 需以
#                                                                         #   VERIDEX_CHAT_PROVIDER=ollama 重启）
#   WITH_EVAL=1 EVAL_DATASET_ID=... EVAL_PROFILE_ID=... KB_IDS=... ./run-matrix.sh
# 验证/短跑（本任务冒烟）：VUS_LIST="1" DURATION=20s KB_IDS=... ./run-matrix.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${ROOT_DIR}/results/$(date +%Y%m%dT%H%M%S)"
mkdir -p "${OUT_DIR}"
KB_IDS="${KB_IDS:?需要 KB_IDS（逗号分隔，来自 SeedGenerator 输出）}"
BASE_URL="${BASE_URL:-http://127.0.0.1:8090}"
PROVIDER="${VERIDEX_CHAT_PROVIDER:-deterministic}"
PROM_URL="${PROM_URL:-http://localhost:9090}"
DURATION="${DURATION:-5m}"            # 每档稳态时长（Task 8 全量 5m；验证可缩短）
VUS_LIST="${VUS_LIST:-5 20 50}"       # 默认三档；验证可覆盖为 "1"
EVAL_VUS="${EVAL_VUS:-50}"            # 干扰组 VU 档
PROM_WINDOW="${PROM_WINDOW:-5m}"      # first_token PromQL 查询窗口
LOGIN_USERNAME="${LOGIN_USERNAME:-admin}"
LOGIN_PASSWORD="${LOGIN_PASSWORD:-veridex}"

# 评测干扰组前置（WITH_EVAL=1 时必填）：PG 中需预置评测 dataset + 配置档（Phase 3 已有：
#   dataset ce263b98-7994-4e0f-bfd8-1c1fb243144a「技术书籍问答评测集」v1/v2/v3，
#   profile ae61c1aa-b747-4864-8136-f5d14a835938「检索基线」v1 / 4a239222-...「高召回配置」v2）。
EVAL_DATASET_ID="${EVAL_DATASET_ID:-}"
EVAL_DATASET_VERSION_NO="${EVAL_DATASET_VERSION_NO:-1}"
EVAL_PROFILE_ID="${EVAL_PROFILE_ID:-}"
EVAL_PROFILE_VERSION_NO="${EVAL_PROFILE_VERSION_NO:-1}"

# first_token 时延（Prometheus，直连 compose 的 prometheus :9090）。
# 实测：veridex.generation.first_token 是 Micrometer Timer（无 SLO bucket），Prometheus 只暴露
#   veridex_generation_first_token_seconds_{count,sum,max}，_bucket 系列不存在 → histogram_quantile
#   返回空。脚本先试 P99（若未来 backend 配了 bucket 系列则生效），为空则退化为 max_over_time 尾部代理，
#   并额外落 mean（rate(sum)/rate(count)）。
prom_first_token() {
  local out="$1" tmp
  tmp="$(mktemp)"
  local q="histogram_quantile(0.99, sum(rate(veridex_generation_first_token_seconds_bucket[${PROM_WINDOW}])) by (le))"
  if curl -fsSG "${PROM_URL}/api/v1/query" --data-urlencode "query=${q}" -o "${tmp}" \
      && python3 - "${tmp}" <<'PYEOF'
import json, sys
r = json.load(open(sys.argv[1]))["data"]["result"]
sys.exit(0 if r else 1)
PYEOF
  then
    mv "${tmp}" "${out}"
  else
    rm -f "${tmp}"
    echo "WARN: first_token histogram buckets 不存在（Timer 未配 SLO bucket），P99 退化为 max_over_time 尾部代理" >&2
    curl -fsSG "${PROM_URL}/api/v1/query" \
      --data-urlencode "query=max_over_time(veridex_generation_first_token_seconds_max[${PROM_WINDOW}])" \
      -o "${out}"
  fi
  curl -fsSG "${PROM_URL}/api/v1/query" \
    --data-urlencode "query=sum(rate(veridex_generation_first_token_seconds_sum[${PROM_WINDOW}]))/sum(rate(veridex_generation_first_token_seconds_count[${PROM_WINDOW}]))" \
    -o "${out%.json}-mean.json"
}

# 评测触发：POST /api/evaluation/runs 在 CSRF 保护内且 requireAdmin → 需 admin 会话 + X-XSRF-TOKEN。
# 修正 brief 草案的 -b <(echo)（空 cookie jar → 无会话 → 401/302）：用 curl 登录 jar + CSRF。
# 请求体以 evaluation/api 的 StartRunRequest 实测为准：
#   {"datasetId","datasetVersionNo","profileId","profileVersionNo","knowledgeBaseIds"}
trigger_eval() {
  local jar csrf body
  jar="$(mktemp)"
  curl -fsS -c "${jar}" -X POST \
    "${BASE_URL}/api/auth/login?username=${LOGIN_USERNAME}&password=${LOGIN_PASSWORD}" -o /dev/null
  csrf="$(curl -fsS -b "${jar}" "${BASE_URL}/api/auth/csrf" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')"
  body="$(cat <<JSON
{"datasetId":"${EVAL_DATASET_ID}","datasetVersionNo":${EVAL_DATASET_VERSION_NO},"profileId":"${EVAL_PROFILE_ID}","profileVersionNo":${EVAL_PROFILE_VERSION_NO},"knowledgeBaseIds":[$(printf '"%s"' "${KB_IDS}" | sed 's/,/","/g')]}
JSON
)"
  curl -fsS -b "${jar}" -H "X-XSRF-TOKEN: ${csrf}" -H "Content-Type: application/json" \
    -X POST "${BASE_URL}/api/evaluation/runs" -d "${body}" -o "${OUT_DIR}/eval-run.json"
  rm -f "${jar}"
  echo "eval run 已触发并落盘 ${OUT_DIR}/eval-run.json（status 见文件内 status 字段）"
}

for vus in ${VUS_LIST}; do
  echo "==> ${PROVIDER} ${vus} VU"
  k6 run "${ROOT_DIR}/k6/qa-load.js" \
    -e BASE_URL="${BASE_URL}" -e KB_IDS="${KB_IDS}" -e VUS="${vus}" -e DURATION="${DURATION}" \
    --summary-export "${OUT_DIR}/${PROVIDER}-vus${vus}.json"
  prom_first_token "${OUT_DIR}/${PROVIDER}-vus${vus}-first-token.json"
done

if [ "${WITH_EVAL:-0}" = "1" ]; then
  if [ -z "${EVAL_DATASET_ID}" ] || [ -z "${EVAL_PROFILE_ID}" ]; then
    echo "WARN: WITH_EVAL=1 但 EVAL_DATASET_ID/EVAL_PROFILE_ID 未设置，跳过干扰组（前置见 README）" >&2
  else
    # M-2: 干扰档需有同档无评测基线（vus${EVAL_VUS}.json）可比——EVAL_VUS 必须 ∈ VUS_LIST
    #      （默认 "5 20 50"；VUS_LIST 被覆盖时按实际档位校验）。
    case " ${VUS_LIST} " in
      *" ${EVAL_VUS} "*) ;;
      *)
        echo "ERROR: WITH_EVAL=1 但 EVAL_VUS=${EVAL_VUS} 不在 VUS_LIST（${VUS_LIST}）中：" \
             "干扰档 vus${EVAL_VUS}-with-eval 无同档基线可比；请设 EVAL_VUS ∈ VUS_LIST（默认 5 20 50）" >&2
        exit 1
        ;;
    esac
    echo "==> 评测干扰组: ${EVAL_VUS} VU + 评测运行（dataset=${EVAL_DATASET_ID} profile=${EVAL_PROFILE_ID}）"
    # 后台触发评测（与 k6 压测并发，量化 P99 漂移）；评测在请求线程内同步执行
    trigger_eval &
    EVAL_PID=$!
    k6 run "${ROOT_DIR}/k6/qa-load.js" \
      -e BASE_URL="${BASE_URL}" -e KB_IDS="${KB_IDS}" -e VUS="${EVAL_VUS}" -e DURATION="${DURATION}" \
      --summary-export "${OUT_DIR}/${PROVIDER}-vus${EVAL_VUS}-with-eval.json"
    # M-1: 后台 curl 失败（如 dataset/配置档未在 PG 预置、后端不可达）时输出 WARN，
    #      不静默吞掉，也不掩盖矩阵结果。
    if wait "${EVAL_PID}"; then
      EVAL_RC=0
    else
      EVAL_RC=$?
    fi
    if [ "${EVAL_RC}" -ne 0 ] || [ ! -s "${OUT_DIR}/eval-run.json" ]; then
      echo "WARN: 评测触发失败（后台退出码 ${EVAL_RC}，${OUT_DIR}/eval-run.json 缺失/为空）：" \
           "多为 EVAL_DATASET_ID/EVAL_PROFILE_ID 未在 PG 预置或后端不可达——干扰组无对比数据；矩阵结果不受影响" >&2
    else
      EVAL_STATUS="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("status","?"))' "${OUT_DIR}/eval-run.json" 2>/dev/null || echo '?')"
      echo "eval run 回执：status=${EVAL_STATUS}"
      if [ "${EVAL_STATUS}" != "COMPLETED" ]; then
        echo "WARN: eval run status=${EVAL_STATUS}（非 COMPLETED），干扰组数据可能不可用；矩阵结果不受影响" >&2
      fi
    fi
  fi
fi

echo "run-matrix: 结果在 ${OUT_DIR}（供容量报告引用）"
