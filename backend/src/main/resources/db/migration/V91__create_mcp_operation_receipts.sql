-- Shared, atomic receipts for newly exposed OAuth-only MCP website writes.
-- Store only a canonical request hash and a bounded result, never the request or uploaded files.
CREATE TABLE mcp_operation_receipts (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES users(id),
    teacher_id UUID REFERENCES users(id),
    scope_key VARCHAR(50) NOT NULL,
    operation VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    result_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_mcp_operation_receipt UNIQUE(actor_id, scope_key, operation, idempotency_key),
    CONSTRAINT ck_mcp_receipt_key CHECK (length(idempotency_key) BETWEEN 1 AND 128),
    CONSTRAINT ck_mcp_receipt_hash CHECK (payload_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_mcp_receipt_result_bound CHECK (result_json IS NULL OR octet_length(result_json) <= 262144),
    CONSTRAINT ck_mcp_receipt_completion CHECK ((result_json IS NULL) = (completed_at IS NULL)),
    CONSTRAINT ck_mcp_receipt_scope CHECK (
        (teacher_id IS NULL AND scope_key = 'school')
        OR (teacher_id IS NOT NULL AND scope_key = 'teacher:' || teacher_id::text)
    )
);
