CREATE TABLE index_release (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    document_version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
    version_no INTEGER NOT NULL,
    index_name VARCHAR(300) NOT NULL,
    alias_name VARCHAR(300) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    UNIQUE (knowledge_base_id, version_no)
);
