-- Phase 4-c 反馈转坏例

CREATE TABLE feedback (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    query_run_id UUID,
    rating VARCHAR(10) NOT NULL,
    reason_code VARCHAR(40),
    question TEXT NOT NULL,
    answer TEXT NOT NULL,
    evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    converted_case_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
