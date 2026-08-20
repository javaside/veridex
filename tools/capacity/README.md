# veridex-capacity-tools（tools/capacity）

容量合成数据生成器与重灌基准。独立 Maven 模块：**parent 不指向根 pom**（根 pom 只聚合
`backend`），**不进 `verify.sh`**，构建用 `cd tools/capacity && ../../mvnw package`。

交付物：

| 类 | 作用 |
|---|---|
| `SeedGenerator` | 合成数据生成器：PG 元数据 + MinIO 对象 + OpenSearch 索引/alias/release |
| `DeterministicEmbedding` | backend `DeterministicEmbeddingModel` 的无依赖纯 Java 复刻（128 维） |
| `ReindexBenchmark` | 删除索引→重建→重灌全量→计时 |

依赖版本与 backend 锁定版本对齐（`./mvnw -f backend/pom.xml dependency:tree` 确认）：
postgresql 42.7.8、opensearch-java 3.9.0、opensearch-rest-client 3.8.0、minio 8.6.0、
jackson-databind 2.21.2（opensearch-java 3.9.0 传递声明版本，显式锚定；backend 运行时经
minio 8.6.0 先声明实际解析为 2.20.1，两端 JSON 序列化兼容，评审 Important-1 修复）、
httpclient 4.5.14 / httpcore 4.4.16。

## 前置条件

1. compose 栈已起（`docker compose -f deploy/compose/compose.yml up -d`，含
   postgres/minio/opensearch/rabbitmq/backend/web）。
2. 容器内存建议（本机 Docker VM 内存紧张时）：
   - OpenSearch 已配 `OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m`（compose 默认）；
     1M chunk 全量生成前建议给 Docker 至少 8 GB 内存，避免索引写满堆 OOM。
   - 生成器自身是纯 Java 客户端，默认堆即可；embedding 是纯本地算法，无模型 API。
3. 本工具直连宿主机端口（compose 已发布）：
   `localhost:5432`（PG）、`localhost:9000`（MinIO）、`localhost:9200`（OpenSearch）。

环境变量（默认即 compose 本地值，一般不用设）：

```bash
VERIDEX_DB_URL=jdbc:postgresql://localhost:5432/veridex
VERIDEX_DB_USERNAME=veridex
VERIDEX_DB_PASSWORD=veridex-local
VERIDEX_MINIO_ENDPOINT=http://localhost:9000
VERIDEX_MINIO_ACCESS_KEY=veridex
VERIDEX_MINIO_SECRET_KEY=veridex-local-secret
VERIDEX_MINIO_BUCKET=veridex-documents
VERIDEX_OPENSEARCH_URIS=http://localhost:9200
```

## 构建

```bash
cd tools/capacity
../../mvnw -q package
```

产出 `target/veridex-capacity-tools-0.1.0.jar`（shade fat jar，manifest 默认
`mainClass=SeedGenerator`；`ReindexBenchmark` 用 `-cp` 方式运行，见下）。

## 1K 自校验

```bash
cd tools/capacity
java -jar target/veridex-capacity-tools-0.1.0.jar --kbs 2 --docs-per-kb 5 --chunks-per-doc 100
```

期望输出：`total_chunks=1000`，两个 KB 各自 `os_count=500 OK`。随后做检索命中验证：

```bash
# 对账（可任选一个 KB 的索引名）
curl -s 'http://localhost:9200/veridex-<kbId>-active/_count?pretty'

# backend HTTP 问答（admin / veridex，经 web 8090 代理）
curl -s -c /tmp/vx.jar -b /tmp/vx.jar -X POST \
  'http://localhost:8090/api/auth/login?username=admin&password=veridex'
CSRF=$(curl -s -b /tmp/vx.jar http://localhost:8090/api/auth/csrf | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
curl -s -N -b /tmp/vx.jar -H "X-XSRF-TOKEN: $CSRF" -H 'Content-Type: application/json' \
  -X POST http://localhost:8090/api/qa/ask -d "{\"question\":\"员工请假应当经过哪些审批环节？\",\"knowledgeBaseIds\":[\"<kbId>\"],\"conversationId\":null}"
```

命中判定：SSE 事件出现 `retrieval.completed`（hitCount>0）与 `citation.available`，
且 citation 的 `validationStatus` 为 `VALID`、引文文本来自「请假管理制度」chunk。
命中失败 = DeterministicEmbedding 复刻漂移或 mapping 不一致，修到命中为止。

## 1M 全量生成（Task 8 门禁，本任务不跑）

```bash
cd tools/capacity
java -Xmx4g -jar target/veridex-capacity-tools-0.1.0.jar \
  --kbs 20 --docs-per-kb 50 --chunks-per-doc 1000
```

默认参数即 1M chunk（20 KB × 50 doc × 1000 chunk）。本地 compose 单机预期 **< 1h**
（embedding 为本地纯算法 + 并行，瓶颈在 OpenSearch bulk 写入与磁盘）。每个 KB 一个索引
`veridex-<kbId>-<versionNo>`，alias `veridex-<kbId>-active` 指向最新 release。

## ReindexBenchmark

对已生成的 KB 重灌计时（删除索引 → 同 mapping 重建 → 从 MinIO 读 chunks.json → bulk → 挂回 alias）：

```bash
cd tools/capacity
java -cp target/veridex-capacity-tools-0.1.0.jar io.veridex.capacity.ReindexBenchmark --kb <kbId>
```

输出 `reindex_seconds` 与 `os_count` 对账。

## k6 压测（Task 7）

在线问答负载脚本 `k6/qa-load.js` + 梯度矩阵 `k6/run-matrix.sh`
（另见 Phase 5-e 计划）。负载打在 `/api/qa/ask`（SSE 问答，spec §4.5）：
普通 `http.post` 会读完整 SSE 流（服务端 complete 后关闭连接），全链路时延即
`http_req_duration`；首 token 时延从 Prometheus `veridex.generation.first_token` 查询。

### k6 安装（二选一）

```bash
brew install k6                                    # macOS
# 或 Docker（不装 k6 本体）：
docker run --rm -v "$PWD:/work" -w /work grafana/k6 run /work/tools/capacity/k6/qa-load.js -e ...
```

`run-matrix.sh` 另需 `curl` 与 `python3`（macOS 自带；用于 PromQL 结果判空与 CSRF 解析）。
Docker 方式跑矩阵时需把仓库挂载进容器并调整 `k6`/`BASE_URL`（容器内访问宿主机服务用
`host.docker.internal`）。

### 前置

1. compose 栈已起（backend/web/prometheus 等），且 SeedGenerator 数据在位
   （1K 自校验：`--kbs 2 --docs-per-kb 5 --chunks-per-doc 100`，共 2×500 chunk）。
2. 拿 KB id：`SELECT id FROM knowledge_base WHERE slug LIKE 'capacity-%';`
   （或见 SeedGenerator 输出）。1M 全量压测在 Task 8 用 1M 数据执行，脚本与
   1K 冒烟共用，无需改动。

### 冒烟（1 VU × 30s）

```bash
cd tools/capacity
k6 run k6/qa-load.js -e VUS=1 -e DURATION=30s -e KB_IDS=<kb1>,<kb2>
```

预期：`http_req_failed`=0、`sse ok` / `answer delivered` 全过。

### 梯度矩阵

```bash
cd tools/capacity
# deterministic（默认）× 5/20/50 VU，每档 5m，结果落 results/<时间戳>/
KB_IDS=<kb1>,<kb2> k6/run-matrix.sh

# ollama 曲线：backend 需以 VERIDEX_CHAT_PROVIDER=ollama 重启后再跑（环境变量经脚本透传命名）
VERIDEX_CHAT_PROVIDER=ollama KB_IDS=<kb1>,<kb2> k6/run-matrix.sh

# 评测干扰组（WITH_EVAL=1 时在 50 VU 档后台并发触发一次评测运行）
WITH_EVAL=1 \
  EVAL_DATASET_ID=ce263b98-7994-4e0f-bfd8-1c1fb243144a \
  EVAL_PROFILE_ID=ae61c1aa-b747-4864-8136-f5d14a835938 \
  KB_IDS=<kb1>,<kb2> k6/run-matrix.sh
```

脚本环境变量：

| 变量 | 默认 | 说明 |
|---|---|---|
| `KB_IDS` | 必填 | 逗号分隔的 knowledge base id |
| `VERIDEX_CHAT_PROVIDER` | `deterministic` | 曲线名（透传进输出文件名；backend 需以同名 env 重启） |
| `BASE_URL` | `http://127.0.0.1:8090` | web 代理地址 |
| `PROM_URL` | `http://localhost:9090` | Prometheus 直连地址 |
| `DURATION` | `5m` | 每档稳态时长 |
| `VUS_LIST` | `5 20 50` | 梯度档位（验证可覆盖 `1`） |
| `EVAL_VUS` | `50` | 干扰组 VU 档（必须 ∈ `VUS_LIST`，否则 WITH_EVAL 直接失败——干扰档无同档基线可比） |
| `WITH_EVAL` | `0` | `1` 时跑评测干扰组 |
| `EVAL_DATASET_ID` / `EVAL_PROFILE_ID` | 空 | 评测 dataset/配置档（PG 预置，见下） |
| `EVAL_DATASET_VERSION_NO` / `EVAL_PROFILE_VERSION_NO` | `1` | 评测版本号 |

输出（`tools/capacity/results/<时间戳>/`，不提交 git）：

- `${PROVIDER}-vus${n}.json`：k6 `--summary-export`（P50/P95/P99 全链路——`qa-load.js`
  `options.summaryTrendStats` 已含 `p(99)`：`avg/min/med/p(90)/p(95)/p(99)/max`；
  另含吞吐与错误率）
- `${PROVIDER}-vus${n}-first-token.json`：first_token P99（或退化 max，见下）
- `${PROVIDER}-vus${n}-first-token-mean.json`：first_token 均值
- `${PROVIDER}-vus50-with-eval.json` / `eval-run.json`：干扰组结果与评测运行回执

干扰组对比：同 VU 档（50 VU）有/无评测的 P99 漂移。

### 评测触发端点（实测核实）

- 端点：`POST /api/evaluation/runs`（`evaluation/api` 的 `EvaluationRunController`）
- 鉴权：需 admin 会话 + CSRF（`X-XSRF-TOKEN` + 登录 cookie jar）——不能用空 cookie jar
  （brief 草案的 `-b <(echo)` 会 401）；脚本已改为 curl 登录 jar + CSRF 后触发。
- 请求体（`StartRunRequest`，非草案的 `{"concurrency":1}`）：

```json
{"datasetId":"<uuid>","datasetVersionNo":1,"profileId":"<uuid>","profileVersionNo":1,
 "knowledgeBaseIds":["<kb1>","<kb2>"]}
```

- dataset/配置档前置：PG 需预置（Phase 3 已有：
  dataset `ce263b98-7994-4e0f-bfd8-1c1fb243144a`「技术书籍问答评测集」v1/v2/v3、
  profile `ae61c1aa-b747-4864-8136-f5d14a835938`「检索基线」v1）；运行在请求线程内同步执行。
- 若未预置 dataset：脚本打印 WARN 并跳过干扰组（干扰组实际执行留给 Task 8 的 1M 全量压测）；
  env 已设但触发失败（dataset/配置档在 PG 缺失或后端不可达）时，脚本在干扰档 k6 结束后打印
  WARN 并保留矩阵结果——不静默吞掉，也不掩盖无评测基线的对比缺口。

### first_token PromQL（实测调整）

`veridex.generation.first_token` 是 Micrometer **Timer**（无 SLO bucket），Prometheus
只暴露 `veridex_generation_first_token_seconds_{count,sum,max}`，`_bucket` 系列不存在——
brief 草案的 `veridex_generation_first_token_bucket` 查询会返回空。脚本行为：

1. 先试 `histogram_quantile(0.99, sum(rate(veridex_generation_first_token_seconds_bucket[5m])) by (le))`
   （backend 未来配 bucket 系列后自动生效）；
2. 为空则 WARN 并退化为 `max_over_time(veridex_generation_first_token_seconds_max[5m])`
   作为尾部代理；
3. 另落均值 `sum(rate(..._sum[5m]))/sum(rate(..._count[5m]))`。
   若 Task 8 需要真 P99，需给 backend 的 Timer 加 `publishPercentileHistogram`/SLO bucket。

## 结果落盘位置

- PG：`knowledge_base` / `document` / `document_version` / `index_release`（`is_active=true`）
- MinIO：`veridex-documents/<kbId>/<docId>/v1/doc-{i}.md`、`.parsed.json`、`.chunks.json`
- OpenSearch：`veridex-<kbId>-<versionNo>`（每 KB 一个索引）+ alias `veridex-<kbId>-active`
- 容量观测：`docker compose ps` / Grafana `:3000`（prometheus/opensearch 指标）

## 失败清理与重跑

SeedGenerator 每个 KB 的流程（插 KB 行 → 建索引 → 写文档/MinIO → bulk → 写 release →
挂 alias）在任一步失败时，会在退出前自动执行 per-KB 清理（best-effort，不掩盖原始异常）：

- 删除刚建的 OpenSearch 索引（`veridex-<kbId>-<versionNo>`；删除索引即连同其上 alias 一并移除，
  不会留下「PG 声称 active 但 alias 未指向」或半成品索引）；
- 回滚该 KB 的全部 PG 行（`index_release_document` / `index_release` / `document_version` /
  `document` / `knowledge_base_grant` / `knowledge_base`）；
- 删除本次已写入 MinIO 的 `<kbId>/...` 对象。

失败时 stderr 打印 `[seed] FAILED kb=...` 与 `[seed] CLEANED-UP kb=...`，进程以非零码退出；
清理失败的单项打印 `[seed] cleanup: WARN ...`，不影响其余清理项。

### 半成品检查命令

万一自动清理未能完全执行（如进程被 kill -9、网络中断导致 cleanup WARN），可用以下命令
检查并手动清理残留：

```bash
# 1) 残留 KB 行（slug 形如 capacity-<n>-<uuid8>；正常重跑每次新建 KB，UUID 前缀不冲突）
docker exec veridex-postgres-1 psql -U veridex -d veridex -t -c \
  "SELECT id, name, slug FROM knowledge_base WHERE slug LIKE 'capacity-%' ORDER BY created_at;"

# 2) 残留 PG 行（无对应 KB 行则属半成品，可整删）——删除某 KB 的全部行（含级联）：
docker exec veridex-postgres-1 psql -U veridex -d veridex -c \
  "DELETE FROM knowledge_base WHERE id = '<kbId>';"   # 子表 ON DELETE CASCADE 级联

# 3) 残留 OpenSearch 索引（无 alias 指向、或 index_release 已删的半成品）
curl -s 'http://localhost:9200/_cat/indices/veridex-*?v'
curl -s -X DELETE 'http://localhost:9200/veridex-<kbId>-<versionNo>'   # 手动删除半成品索引

# 4) 残留 MinIO 对象（无对应 KB 行的孤儿对象，可整前缀删）
#    mc rm --recursive --force local/veridex-documents/<kbId>/   （或用 MinIO 控制台）
```

重跑前建议核对 `index_release WHERE is_active` 与 alias 实际指向一致：
`curl -s 'http://localhost:9200/_cat/aliases/veridex-*active?v'`。正常失败清理后
这些检查应为空/一致。

## 注意

- 本模块**不进根 pom `<modules>`、不进 `verify.sh`**；独立构建、独立交付。
- 生成器幂等性：每次运行创建新 KB（slug 带 UUID 前缀），可重复运行不冲突；
  1M 全量前如需清场，删 KB 或重建 compose 卷即可。
- 重跑 1M 全量建议先清理历史索引（`curl -X DELETE localhost:9200/veridex-*`），
  避免 OpenSearch 堆/磁盘被旧索引占满（compose 只给了 512m 堆）。
