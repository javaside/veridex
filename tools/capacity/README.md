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
jackson-databind 2.20.1、httpclient 4.5.14 / httpcore 4.4.16。

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

## k6 压测

Task 7 补充（另见 Phase 5-e 计划）。基准负载建议：
`/api/qa/ask`（SSE 问答）与检索类接口，数据面使用上面生成的 1M chunk 知识库。

## 结果落盘位置

- PG：`knowledge_base` / `document` / `document_version` / `index_release`（`is_active=true`）
- MinIO：`veridex-documents/<kbId>/<docId>/v1/doc-{i}.md`、`.parsed.json`、`.chunks.json`
- OpenSearch：`veridex-<kbId>-<versionNo>`（每 KB 一个索引）+ alias `veridex-<kbId>-active`
- 容量观测：`docker compose ps` / Grafana `:3000`（prometheus/opensearch 指标）

## 注意

- 本模块**不进根 pom `<modules>`、不进 `verify.sh`**；独立构建、独立交付。
- 生成器幂等性：每次运行创建新 KB（slug 带 UUID 前缀），可重复运行不冲突；
  1M 全量前如需清场，删 KB 或重建 compose 卷即可。
- 重跑 1M 全量建议先清理历史索引（`curl -X DELETE localhost:9200/veridex-*`），
  避免 OpenSearch 堆/磁盘被旧索引占满（compose 只给了 512m 堆）。
