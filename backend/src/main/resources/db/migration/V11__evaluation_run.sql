-- Phase 4-d 评测运行与指标

CREATE TABLE evaluation_run (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_id UUID NOT NULL REFERENCES evaluation_dataset(id) ON DELETE CASCADE,
    dataset_version_id UUID NOT NULL REFERENCES dataset_version(id) ON DELETE CASCADE,
    profile_id UUID NOT NULL,
    profile_version_id UUID NOT NULL,
    knowledge_scope JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(20) NOT NULL,
    metrics JSONB NOT NULL DEFAULT '{}'::jsonb,
    error TEXT,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE TABLE evaluation_run_case (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES evaluation_run(id) ON DELETE CASCADE,
    case_position INTEGER NOT NULL,
    question TEXT NOT NULL,
    expected_behavior VARCHAR(20) NOT NULL,
    ground_truth_evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    actual_behavior VARCHAR(20) NOT NULL,
    answer TEXT,
    citations JSONB NOT NULL DEFAULT '[]'::jsonb,
    retrieved_chunks JSONB NOT NULL DEFAULT '[]'::jsonb,
    metrics JSONB NOT NULL DEFAULT '{}'::jsonb
);
