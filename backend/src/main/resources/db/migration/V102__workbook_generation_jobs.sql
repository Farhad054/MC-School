-- Durable, actor-scoped generation. No OAuth credentials or signed download URLs.
CREATE TABLE workbook_generation_jobs (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES users(id),
    actor_role VARCHAR(16) NOT NULL,
    teacher_id UUID NOT NULL REFERENCES users(id),
    event_id VARCHAR(512) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    request_json TEXT,
    state VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    draft_id UUID REFERENCES lesson_pdf_drafts(id),
    failure_code VARCHAR(40),
    worker_id UUID,
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    UNIQUE(actor_id, teacher_id, idempotency_key),
    CHECK (state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    CHECK (request_json IS NULL OR octet_length(request_json) <= 750000),
    CHECK (length(idempotency_key) BETWEEN 1 AND 128)
);
CREATE INDEX workbook_generation_queue ON workbook_generation_jobs(created_at) WHERE state = 'QUEUED';
