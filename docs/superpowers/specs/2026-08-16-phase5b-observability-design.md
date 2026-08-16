# Phase 5-b Observability Design

**Date:** 2026-08-16

**Status:** Approved for implementation planning

**Scope:** Phase 5-b of Enterprise Pilot Readiness

## 1. Goal

Phase 5-b gives operators enough telemetry to diagnose the online RAG, ingestion, outbox, and indexing paths during a 20-50 person enterprise pilot without recording user questions, prompts, answers, document chunks, credentials, or identifiers in metrics, spans, or logs.

The phase delivers:

- Micrometer business observations and Prometheus metrics;
- OpenTelemetry traces exported through an OpenTelemetry Collector to Tempo;
- provisioned Prometheus, Grafana, Collector, and Tempo services for local validation;
- queue, model, retrieval, indexing, and API alert rules;
- controlled, encrypted, short-lived trace-body capture that is disabled by default;
- a session-only, platform-admin trace-body read API with mandatory access reason and auditing.

## 2. Non-Goals

Phase 5-b does not:

- add a trace-body frontend;
- add Alertmanager or notification channels;
- provide cluster-global telemetry storage or high-availability observability services;
- change the lifecycle of conversation messages, feedback snapshots, or evaluation records;
- harden public Actuator network exposure; that belongs to Phase 5-c;
- add application containers, Kubernetes manifests, or Helm charts; those belong to Phase 5-d;
- implement automatic ingestion retry, outbox replay, or DLQ recovery;
- make trace-body retention equivalent to deletion from backups, replicas, conversations, feedback, or evaluation data.

## 3. Current State

The application has Spring Boot Actuator and exposes `health`, `info`, `metrics`, and `prometheus`, but it does not include a Prometheus registry, Micrometer tracing bridge, OTLP exporter, business observations, dashboards, or alert rules. `/actuator/prometheus` therefore has no registered Prometheus endpoint today.

The online QA path is hand-orchestrated across `qa`, `retrieval`, `generation`, and `trace`. It calls a custom `DeterministicChatModel` directly, so Spring AI ChatClient observations do not cover the current model call. Retrieval calls OpenSearch and `EmbeddingModel` directly, not a Spring AI `VectorStore`.

The ingestion path uses an outbox and RabbitMQ. Its message payload has no trace context or request ID headers. The Compose stack contains PostgreSQL, RabbitMQ, Redis, MinIO, and OpenSearch only.

`query_run` currently persists the raw question, normalized question, and raw exception message indefinitely. It does not persist a full expanded prompt, answer, or entered-context chunk bodies. Answers also exist in conversation messages; feedback and evaluation data may contain separate question/answer copies.

## 4. Chosen Approach

Use explicit observations at stable business orchestration boundaries, backed by standard Micrometer and OpenTelemetry integrations.

The alternatives were rejected as follows:

- automatic instrumentation alone cannot describe retrieval degradation, refusal, model, ingestion, outbox, and indexing business outcomes;
- a standalone observability business module would create unnecessary dependencies from most domain modules to a cross-cutting facade.

No new business module is created. Cross-cutting instrumentation lives in a narrow `shared::observability` named interface. Sensitive trace-body capture stays inside the `trace` module. Deployment configuration lives under `deploy/compose/observability`.

## 5. Architecture and Module Boundaries

### 5.1 `shared::observability`

The named interface exposes only low-level, stable observability primitives:

- metric and observation names;
- fixed outcome and stage values;
- helpers that reject or avoid high-cardinality and sensitive tags;
- Rabbit trace-context and request-ID propagation helpers;
- fixed error-code mapping.

It must not depend on a business module. Consumers include `qa`, `retrieval`, `generation`, `ingestion`, `indexing`, and shared outbox infrastructure.

APIs in this interface must not accept arbitrary maps of tags or unvalidated exception messages. Call sites choose values from fixed enums or bounded configured provider/model names.

### 5.2 `trace`

The trace module owns:

- `QueryRun` execution metadata;
- trace-body capture policy;
- body envelope serialization;
- AES-256-GCM encryption and key-ring lookup;
- body persistence and expiration;
- the platform-admin read API;
- access auditing and retention cleanup.

No other module can read or decrypt `trace_body` directly. The QA orchestrator supplies the in-memory body material through a narrow trace API at terminal run state.

### 5.3 Standard Instrumentation

Spring Boot and Micrometer provide HTTP, JVM, process, JDBC pool, Rabbit, and executor telemetry where available. Explicit Veridex observations add business meaning without duplicating framework metrics.

### 5.4 Local Observability Stack

The local stack is:

```text
Veridex application
  ├─ /actuator/prometheus ──> Prometheus ──> Grafana
  └─ OTLP ──> OTel Collector ──> Tempo ──> Grafana

RabbitMQ management Prometheus endpoint ──> Prometheus
```

The application remains a host process, not a Compose service. Prometheus reaches it through `host.docker.internal:8080`, with Linux `host-gateway` compatibility.

## 6. Business Observations and Metrics

### 6.1 Root Observations

The phase defines six platform root observations:

| Observation | Boundary | Allowed low-cardinality tags |
|---|---|---|
| `veridex.qa.run` | complete online QA orchestration | `outcome=completed/refused/failed/cancelled`, `conversation=new/existing` |
| `veridex.retrieval.run` | hybrid retrieval through context assembly | `outcome=success/degraded/failed` |
| `veridex.generation.model` | one model invocation | bounded `provider`, bounded `model`, `outcome=success/error` |
| `veridex.ingestion.run` | one Rabbit ingestion delivery | `result=success/already_ready/failed`, fixed `failure_stage` |
| `veridex.indexing.publish` | one index release publication | `outcome=success/error` |
| `veridex.outbox.publish` | one outbox delivery attempt | `outcome=success/error` |

Observations produce both timer metrics and spans. Nested spans are limited to externally meaningful phases such as retrieval channels, model call, parsing, embedding batch, OpenSearch bulk, and alias switch. Pure in-memory helpers such as rank fusion are measured only if profiling proves them significant.

### 6.2 Counters, Gauges, and Distributions

Additional telemetry includes:

- retrieval channel failures and dual failures;
- retrieval degradation rate;
- refusal reason and citation validation outcomes;
- model input/output token estimates and duration;
- outbox pending count, oldest unpublished age, and publish failures;
- ingestion ack, duplicate, reject, processing duration, and fixed failure stage;
- document versions stuck in `PROCESSING` beyond the configured threshold;
- indexing chunks, embedding batch duration, bulk failures, refresh failures, and alias-switch failures;
- trace-body capture, skipped capture, read, denied read, and cleanup counts;
- upload bytes, retrieval scope size, hit count, evidence count, context character count, and chunk count as distributions.

Counts and sizes are metric values, never tags.

### 6.3 Cardinality Rules

Metrics must not use any of the following as tags:

- `runId`, request ID, user ID, conversation ID;
- knowledge-base, document, version, chunk, release, or outbox UUID;
- filename, document title, structure path, index name, or alias;
- question length, token count, hit count, scope size, or other continuous values;
- exception class names or exception messages.

A run ID may be attached to a span or structured log solely for correlation. It is never a metric label. Provider and model tags must be mapped to configured bounded values; arbitrary provider-returned strings are not accepted.

### 6.4 Sensitive Data Prohibition

Metrics, span names, span attributes, span events, and normal logs must never contain:

- question or normalized question;
- system prompt, expanded prompt, prompt template body, or conversation history;
- answer, completion, or SSE deltas;
- evidence or chunk text;
- document title, filename, source location, or citation text;
- embedding input or vectors;
- user, conversation, knowledge-base, document, chunk, or release identifiers except the run correlation rule above;
- OpenSearch query DSL;
- raw exception or degradation messages;
- authorization headers, API keys, cookies, passwords, or infrastructure credentials.

Errors are mapped to a fixed bounded code such as `opensearch_timeout`, `embedding_unavailable`, `dual_retrieval_failed`, `model_error`, `storage_error`, `parse_error`, or `unknown`.

Spring AI content logging remains disabled:

```yaml
spring.ai.chat.client.observations.log-prompt: false
spring.ai.chat.client.observations.log-completion: false
spring.ai.vectorstore.observations.log-query-response: false
```

Tests treat these values as security defaults.

## 7. Trace Context Across RabbitMQ

Outbox publication writes observability information as Rabbit headers, not business payload fields:

- W3C `traceparent` and `tracestate` when a valid parent context exists;
- `x-request-id` when the current request ID is valid;
- `x-outbox-event-id` for correlation;
- a fixed telemetry header schema version.

The ingestion listener restores the remote parent and starts a consumer span. It also places the propagated request ID in MDC for the delivery scope and clears it in `finally`. The `ingestion.completed` audit event uses that propagated request ID.

Missing, malformed, or unsupported headers are ignored safely and result in a new root consumer span. They never reject a business message. Header values are size- and character-limited.

The business event JSON schema remains unchanged.

## 8. Controlled Trace-Body Storage

### 8.1 Default and Policies

`veridex.trace.body.capture-policy` supports:

- `NONE`: default; never persist a body;
- `ERRORS`: persist failed and refused runs only;
- `ALL`: persist completed, refused, and failed runs.

`CANCELLED` is treated as an error terminal state when body material is available. Access-restricted requests that never start a run have no trace body.

Defaults:

- capture policy: `NONE`;
- retention: 24 hours;
- maximum serialized plaintext: 256 KiB;
- cleanup interval: 1 hour.

All values are externally configurable. Capture policy is process-wide in Phase 5-b; there is no per-user or per-request override.

### 8.2 V13 Data Model

Flyway V13 creates:

```sql
trace_body (
  query_run_id UUID PRIMARY KEY REFERENCES query_run(id) ON DELETE CASCADE,
  capture_policy VARCHAR(20) NOT NULL,
  encrypted_body BYTEA NOT NULL,
  encryption_key_id VARCHAR(100) NOT NULL,
  nonce BYTEA NOT NULL,
  schema_version SMALLINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL
)
```

An index on `expires_at` supports cleanup.

V13 also adds a fixed-length `question_fingerprint` to `query_run`. Existing `query_run.question`, `normalized_question`, and `error` columns remain for compatibility but are scrubbed to fixed non-sensitive values. New runs never place raw question or error messages in those columns.

The fingerprint is an HMAC-SHA-256 over normalized question text using the current trace encryption key when capture is enabled, or a separate configured fingerprint key when capture is disabled. If no fingerprint key is configured in the default `NONE` mode, the fingerprint remains null. A plain SHA-256 fingerprint is not used because low-entropy questions are dictionary-attackable.

The migration does not attempt to derive fingerprints for existing rows. Existing raw question, normalized question, and error values are replaced by fixed placeholders in V13.

### 8.3 Body Envelope

Schema version 1 contains only:

- question;
- expanded prompt messages in role/content order;
- answer when produced;
- evidence snapshots that actually entered model context;
- citations;
- fixed terminal outcome and error code.

It does not contain authentication credentials, infrastructure configuration, raw exception messages, embedding vectors, retrieval hits excluded from context, or unrelated conversation messages.

The JSON envelope is serialized in memory. If its UTF-8 size exceeds the configured maximum, capture is skipped entirely and `veridex.trace.body.capture.skipped{reason=too_large}` increments. The implementation does not truncate JSON or individual content fields.

### 8.4 Encryption and Key Ring

Bodies use AES-256-GCM with a fresh 96-bit random nonce per row. Associated authenticated data is the canonical byte sequence of:

```text
veridex-trace-body:<queryRunId>:<schemaVersion>:<encryptionKeyId>
```

This prevents ciphertext replacement across runs, schemas, or key IDs.

Keys are supplied only through environment configuration as a key ring:

- current write key ID;
- one Base64-encoded 256-bit key for the current ID;
- zero or more historical read keys.

The repository contains no default encryption key. With capture policy `NONE`, the application starts without a key ring. With `ERRORS` or `ALL`, startup fails if the current key is missing, malformed, not 256 bits, duplicated, or absent from the read ring.

Rotation changes the current write key while retaining old keys for decryption until all old rows expire. Key material is never logged, returned by Actuator, or exposed through configuration-property endpoints.

### 8.5 Capture Lifecycle

The QA orchestration holds a bounded in-memory capture context only for the duration of one run. At terminal state it supplies the selected envelope to the trace module.

- `NONE` discards the context without serialization;
- `ERRORS` encrypts only failed, refused, and cancelled terminal runs;
- `ALL` encrypts all terminal runs.

The body is written once at terminal state in a dedicated transaction. A body capture failure must not change a successful business outcome or turn a completed run into failed. It increments a fixed-reason metric and emits a redacted warning.

No plaintext body is persisted between stages.

## 9. Trace-Body Read API

The endpoint is:

```text
GET /api/traces/{runId}/body
```

Authorization rules:

- only a `PLATFORM_ADMIN` browser Session is accepted;
- `KNOWLEDGE_ADMIN` and `EMPLOYEE` Sessions receive 403;
- every API key receives 403 because no API-key scope maps to `/api/traces/**`;
- anonymous requests follow the existing authentication entry point.

A request must include `X-Trace-Access-Reason`. The reason is 1-200 characters and accepts a conservative printable character set. It is included in the audit event but not in metrics or span attributes.

Every access attempt records an audit event without body content:

- `trace.body.read` on success;
- `trace.body.read_denied` on authorization or reason validation failure;
- `trace.body.not_found` when the run/body does not exist or has expired;
- `trace.body.decrypt_failed` when the key or authentication tag cannot be validated.

The API checks `expires_at` before decrypting. An expired row is deleted and returned as 404. A missing historical key or failed GCM authentication returns a generic server error and records a fixed error code; it never returns partial plaintext or cryptographic details.

The response contains the envelope plus `runId`, `createdAt`, and `expiresAt`. It is marked `Cache-Control: no-store` and is not available through a frontend page in Phase 5-b.

## 10. Retention Cleanup

A scheduled cleanup runs once per configured interval, default one hour. It:

1. deletes expired `trace_body` rows in bounded batches;
2. scrubs any legacy or accidentally populated raw `query_run.question`, `normalized_question`, and `error` values to fixed placeholders;
3. records only deleted/scrubbed counts and a fixed outcome.

The scheduler is safe under multiple instances by using database row claiming or idempotent bounded deletes. Duplicate cleanup attempts are harmless.

Retention covers the online `trace_body` table only. Documentation states explicitly that conversation, feedback, evaluation, database backups, replicas, exports, and disaster-recovery media require separate lifecycle policies.

## 11. Prometheus, Grafana, Collector, and Tempo

### 11.1 Dependencies and Application Configuration

The backend adds:

- `micrometer-registry-prometheus`;
- `micrometer-tracing-bridge-otel`;
- `opentelemetry-exporter-otlp`.

Configuration defines:

- application/service name `veridex`;
- externally configurable trace sampling probability;
- externally configurable OTLP endpoint;
- bounded common tags such as application and environment;
- histogram/SLO buckets for critical timers;
- trace-body capture and key-ring properties.

The exporter uses asynchronous batching. Collector or Tempo failure cannot block a business request. Export errors use rate-limited, redacted logging.

### 11.2 Compose Services

`deploy/compose/compose.yml` adds:

- Prometheus on `9090`;
- Grafana on `3000`;
- OpenTelemetry Collector on OTLP ports `4317` and `4318`;
- Tempo for local trace storage and query;
- RabbitMQ management with its Prometheus endpoint enabled.

Configuration files live below `deploy/compose/observability`:

```text
prometheus/prometheus.yml
prometheus/rules/veridex-alerts.yml
otel-collector/config.yml
tempo/tempo.yml
grafana/provisioning/datasources/datasources.yml
grafana/provisioning/dashboards/dashboards.yml
grafana/dashboards/veridex-overview.json
```

Grafana provisions Prometheus and Tempo automatically and links exemplar/trace navigation where supported.

### 11.3 Dashboard

The Veridex overview dashboard contains:

- application availability, HTTP request rate, 5xx rate, and p50/p95/p99 latency;
- QA completed/refused/failed rates and latency;
- retrieval success/degradation/failure and channel failures;
- model request rate, error rate, latency, and token distributions;
- outbox pending count and oldest age;
- Rabbit queue depth, unacked messages, rejection rate, and DLQ depth;
- ingestion success/failure/stuck-processing values;
- indexing publish/bulk/refresh/alias-switch outcomes;
- JVM heap, GC, threads, CPU, database-pool use, and process uptime;
- trace-body capture/skipped/cleanup/read/denied counts.

Panels use only bounded labels and do not display trace-body content.

## 12. Alert Rules

Prometheus loads executable alert rules. Phase 5-b does not configure notification delivery.

Required alert families:

- outbox pending count above zero beyond a grace period;
- oldest unpublished outbox age beyond SLA;
- outbox publish failures increasing;
- Rabbit ingestion queue backlog and unacked growth;
- ingestion reject rate above threshold;
- ingestion DLQ depth above zero;
- document versions stuck in `PROCESSING` beyond SLA;
- model error rate and p95 latency above thresholds;
- retrieval dual failures and sustained degradation rate;
- indexing publish, bulk, refresh, or alias-switch failures;
- API 5xx error rate and p95 latency;
- Veridex scrape target down.

Thresholds are pilot defaults and are documented as tunable. Alert labels remain low-cardinality. Grafana displays pending/firing states from Prometheus.

## 13. Error Handling

Observability is fail-open for business processing:

- metric registration or recording failure cannot fail QA, ingestion, or indexing;
- malformed incoming trace headers produce a new trace instead of rejecting a message;
- OTLP export failure drops telemetry asynchronously;
- trace-body capture failure does not alter run outcome;
- dashboard or Prometheus unavailability does not affect the application.

Configuration is fail-fast where silence would create a false security assumption:

- capture policy `ERRORS` or `ALL` with an invalid key ring prevents startup;
- invalid retention, size, sampling, or cleanup values prevent startup;
- duplicate or unknown encryption key IDs prevent startup.

Decryption and authorization failures return generic errors and write audited fixed codes. Cryptographic material, plaintext, raw exceptions, and infrastructure details never appear in the response.

## 14. Testing Strategy

### 14.1 Unit Tests

Unit tests cover:

- metric and observation name constants;
- allowed bounded tag values and rejection of sensitive/high-cardinality input;
- observation outcome on success, refusal, degradation, and exception;
- fixed error-code mapping;
- Rabbit header inject/extract, malformed-header fallback, request-ID validation, and MDC cleanup;
- AES-GCM round trip, unique nonce, wrong AAD, tampering, missing key, and historical-key decryption;
- key-ring validation and rotation;
- `NONE`, `ERRORS`, and `ALL` capture behavior;
- 256 KiB size boundary and too-large skip;
- TTL and cleanup batching;
- access-reason validation and no-store response behavior.

### 14.2 Integration Tests

Integration tests cover:

- `/actuator/prometheus` returns 200 and contains JVM, HTTP, and Veridex metrics;
- one QA run produces the expected bounded metrics without content;
- retrieval degradation and model failure counters;
- outbox publish and ingestion metrics;
- indexing publication metrics;
- Rabbit trace context and request ID survive the producer-consumer boundary;
- `ingestion.completed` audit contains the propagated request ID;
- admin Session can decrypt an unexpired body with an access reason;
- knowledge admin, employee, API key, missing reason, missing body, and expired body are rejected correctly;
- every body access outcome produces the expected audit action without plaintext;
- cleanup removes expired rows and scrubs legacy `query_run` fields;
- capture disabled starts without encryption keys;
- capture enabled with invalid key configuration fails application startup.

Tests inspect meter IDs and span export through in-memory test registries/exporters. They do not require Tempo for normal unit/integration execution.

### 14.3 Deployment Validation

Static and container validation covers:

- `docker compose config --quiet`;
- Prometheus rule syntax validation;
- parseable Grafana dashboard and provisioning JSON/YAML;
- valid Collector and Tempo configuration;
- service readiness and datasource provisioning.

A local acceptance run starts Compose and the host application, then verifies:

- Prometheus target `up{job="veridex"} == 1`;
- Rabbit metrics are scraped;
- Grafana datasources are healthy;
- one QA request and one ingestion delivery appear as traces in Tempo;
- business metrics change after the actions;
- test alert samples can enter pending/firing;
- stopping Collector or Tempo does not fail application requests.

### 14.4 Final Gate

The final gate is:

```bash
./scripts/verify.sh
```

plus the Compose and observability configuration validation commands introduced by the implementation plan.

## 15. Acceptance Criteria

Phase 5-b is complete when all of the following are true:

1. `/actuator/prometheus` is registered, returns 200, and exposes framework plus Veridex metrics.
2. QA, retrieval, model, outbox, ingestion, and indexing operations have bounded business observations with defined success/failure outcomes.
3. A QA request and an ingestion delivery are visible end to end in Tempo through Collector.
4. Metrics, spans, and normal logs contain no question, prompt, answer, chunk body, credentials, raw exception message, or forbidden identifier.
5. Rabbit producer-consumer trace context and request ID propagation work without changing the business payload schema.
6. Prometheus and Grafana load the provided dashboard, recording rules, and alert rules.
7. Queue, outbox, ingestion, retrieval, model, indexing, API, and target-down alerts are present and syntactically valid.
8. Trace-body capture defaults to `NONE` and requires no encryption key.
9. `ERRORS` and `ALL` encrypt eligible bodies with AES-256-GCM, enforce the size limit, and delete them after TTL.
10. New `query_run` rows do not persist raw question, normalized question, or raw exception message; V13 scrubs existing values.
11. Only a `PLATFORM_ADMIN` Session with a valid access reason can read an unexpired body, and every attempt is audited.
12. API keys and non-platform-admin Sessions cannot read trace bodies.
13. Collector, Tempo, Prometheus, or Grafana failure does not block business requests.
14. Invalid enabled-capture key configuration prevents startup.
15. Architecture tests, backend tests, frontend tests, builds, Compose validation, observability config validation, and `git diff --check` pass.

## 16. Fixed Design Decisions

The implementation plan must not revisit these decisions:

- business-boundary observations, not automatic instrumentation alone;
- no standalone observability business module;
- `shared::observability` for bounded cross-cutting helpers;
- Prometheus + Grafana + OTel Collector + Tempo;
- application exports OTLP to Collector, not directly to Tempo;
- Prometheus rules and Grafana display, without Alertmanager in Phase 5-b;
- RabbitMQ management Prometheus metrics for queue depth;
- trace-body capture policies are exactly `NONE`, `ERRORS`, and `ALL`;
- capture defaults to `NONE`;
- default TTL is 24 hours and default maximum plaintext is 256 KiB;
- body storage is a separate V13 table and uses AES-256-GCM;
- encryption keys come from an environment key ring with current and historical keys;
- enabled capture with invalid keys fails startup;
- trace-body read API is Session-only and `PLATFORM_ADMIN`-only;
- every read requires and audits an access reason;
- no trace-body frontend in Phase 5-b;
- telemetry never records content or forbidden identifiers;
- conversation, feedback, evaluation, backup, and replica retention remain outside this phase.
