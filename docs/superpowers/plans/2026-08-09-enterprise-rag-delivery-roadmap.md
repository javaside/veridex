# Enterprise RAG Platform Delivery Roadmap

> **For agentic workers:** Each phase requires its own detailed implementation plan and produces independently demonstrable software. Do not implement later phases before the preceding quality gate passes.

**Goal:** Deliver the confirmed enterprise RAG design as a sequence of independently testable vertical increments over approximately three months.

**Architecture:** Build a Spring Boot 4 modular monolith with independently scalable Java ingestion and evaluation workers. Use Spring AI 2.0 GA for model integration and standard RAG orchestration while retaining platform-owned knowledge, permission, index-release, trace, citation, and evaluation domains.

**Tech Stack:** Java 21+, Spring Boot 4.x, Spring AI 2.0 GA, Spring Modulith, PostgreSQL, OpenSearch, MinIO/S3, RabbitMQ, Redis, React, TypeScript, Testcontainers, OpenTelemetry, Prometheus, Grafana, Docker Compose, Kubernetes, Helm.

## Global Constraints

- Target one privately deployed enterprise installation, not multi-tenant SaaS.
- Design for 500–5000 users and validate with 1–5 million chunks before pilot.
- Preserve Document, DocumentVersion, and IndexRelease as separate platform-owned concepts.
- Enforce authorization in retrieval, citations, previews, downloads, caches, and trace access.
- Use Spring AI 2.0 GA as an integration framework, not as the owner of platform domain state.
- Keep Python optional and isolated to OCR, layout analysis, and multimodal parsing.
- Use test-driven development, real infrastructure integration tests, and frequent commits.
- Do not introduce GraphRAG, generic agents, workflow builders, billing, or multi-tenant infrastructure in the first release.

---

## Phase 1: Executable Foundation

**Outcome:** A reproducible project skeleton starts locally, exposes health checks, enforces module boundaries, and runs integration tests against real infrastructure.

**Detailed plan:** `docs/superpowers/plans/2026-08-09-rag-foundation-implementation.md`

**Exit gate:** CI-equivalent backend checks pass; Compose dependencies become healthy; database migrations run; a documented architecture test prevents forbidden module dependencies.

## Phase 2: Knowledge Ingestion Vertical Slice

**Outcome:** An administrator creates a knowledge base, uploads a PDF/DOCX/TXT/Markdown document, observes durable asynchronous processing, previews parsed chunks, and publishes an immutable document/index release.

**Primary modules:** `iam`, `knowledge`, `ingestion`, `indexing`, `audit`.

**Required behavior:**

- Local users and PLATFORM_ADMIN/KNOWLEDGE_ADMIN/EMPLOYEE roles;
- knowledge-base VIEW/MANAGE grants;
- Document and DocumentVersion lifecycle;
- S3 object persistence and file hash validation;
- RabbitMQ + transactional Outbox;
- parser and structure-first chunker;
- Spring AI EmbeddingModel adapter;
- OpenSearch write index and alias-backed IndexRelease;
- idempotent processing and retry/dead-letter behavior;
- parsed-content and chunk previews;
- publish, rollback, offline, and delete workflows.

**Exit gate:** Duplicate message delivery does not duplicate chunks; worker restart resumes work; a failed new version leaves the old release queryable; offline documents are immediately excluded.

## Phase 3: Authorized RAG Query Vertical Slice

**Outcome:** An authorized employee asks a question and receives a streamed, evidence-bound answer with validated citations; an unauthorized employee cannot discover or access protected content.

**Primary modules:** `retrieval`, `generation`, `conversation`, `trace`, `iam`.

**Required behavior:**

- backend-computed knowledge scope;
- BM25 and vector recall in OpenSearch;
- rank fusion, deduplication, RerankProvider, and context assembly;
- Spring AI RetrievalAugmentationAdvisor integration;
- local and external ChatModel routing with egress policy;
- explicit refusal outcomes;
- SSE lifecycle events;
- citation parsing, validation, preview re-authorization;
- QueryRun, RetrievalHit, GenerationRun, and Citation persistence;
- cancellation, timeout, fallback, and degradation recording.

**Exit gate:** ACL bypass test matrix passes; every non-refusal answer has valid evidence; dual retrieval failure is reported as a system failure; document emergency-offline is enforced before completion.

## Phase 4: Quality and Operations Loop

**Outcome:** Knowledge administrators manage versioned evaluation datasets, run repeatable comparisons, inspect bad cases, and convert production feedback into regression cases.

**Primary modules:** `evaluation`, `feedback`, `trace`, `configuration`.

**Required behavior:**

- versioned datasets and cases;
- ANSWER/REFUSE expected behavior;
- Recall@K, MRR, NDCG, citation hit, latency, and refusal metrics;
- optional judge-model records with model and prompt versions;
- human PASS/MINOR_ISSUE/FAIL review;
- thumbs-up/down reason codes;
- feedback-to-evaluation-case workflow;
- immutable retrieval, generation, chunking, prompt, and model profiles;
- release comparison and regression gate report.

**Exit gate:** The same dataset can compare two immutable configurations; incomplete runs cannot pass a gate; at least 50 real questions are loaded and reviewed.

## Phase 5: Enterprise Pilot Readiness

**Outcome:** The platform can be installed in an enterprise test environment, observed, secured, backed up, restored, load-tested, and used by a 20–50-person pilot.

**Primary areas:** API governance, observability, security hardening, deployment, capacity, and recovery.

**Required behavior:**

- scoped API keys, revocation, rate limiting, request IDs, and OpenAPI;
- Spring AI/Micrometer/OpenTelemetry instrumentation without default prompt/content logging;
- controlled trace-body storage and retention;
- Prometheus metrics, Grafana dashboards, and queue/model/index alerts;
- Docker images, Compose, Kubernetes manifests, and Helm chart;
- externalized secrets and restricted-network installation path;
- PostgreSQL, S3, and OpenSearch backup/recovery;
- upload-parser sandbox limits and malicious-file tests;
- permission enumeration, stale URL, cache, prompt-injection, and egress tests;
- online load, bulk ingestion, evaluation isolation, and 1–5 million chunk capacity report.

**Exit gate:** Actual recovery drill succeeds; permission leakage count is zero; critical policy questions have zero severe factual errors; production-like load and failure-recovery reports are approved.

## Planning Rule for Later Phases

Before starting each phase, create a detailed file-level implementation plan using the current codebase state. Preserve the interfaces and constraints established by earlier phases, and adjust only through an explicit architecture decision and regression tests.
