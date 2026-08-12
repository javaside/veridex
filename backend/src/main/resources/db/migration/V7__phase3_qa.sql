-- Phase 3 带权限的 RAG 查询：会话 / 消息 / 查询运行 / 证据链

CREATE TABLE conversation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    title VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE message (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    query_run_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE query_run (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    conversation_id UUID REFERENCES conversation(id),
    question TEXT NOT NULL,
    normalized_question TEXT,
    knowledge_scope JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(30) NOT NULL,
    refusal_reason VARCHAR(60),
    error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE TABLE retrieval_hit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    query_run_id UUID NOT NULL REFERENCES query_run(id) ON DELETE CASCADE,
    knowledge_base_id UUID NOT NULL,
    document_version_id UUID NOT NULL,
    chunk_index INTEGER NOT NULL,
    channel VARCHAR(10) NOT NULL,
    bm25_score DOUBLE PRECISION,
    vector_score DOUBLE PRECISION,
    fusion_score DOUBLE PRECISION,
    rank INTEGER NOT NULL,
    entered_context BOOLEAN NOT NULL DEFAULT false,
    filter_reason VARCHAR(100)
);

CREATE TABLE generation_run (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    query_run_id UUID NOT NULL REFERENCES query_run(id) ON DELETE CASCADE,
    model VARCHAR(100) NOT NULL,
    input_tokens INTEGER NOT NULL DEFAULT 0,
    output_tokens INTEGER NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    degradation VARCHAR(100),
    context_hash VARCHAR(64)
);

CREATE TABLE citation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    query_run_id UUID NOT NULL REFERENCES query_run(id) ON DELETE CASCADE,
    citation_index INTEGER NOT NULL,
    document_version_id UUID NOT NULL,
    chunk_index INTEGER NOT NULL,
    source_location VARCHAR(300),
    citation_text TEXT,
    validation_status VARCHAR(30) NOT NULL
);
