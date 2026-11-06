ALTER TABLE trial_leads
    ADD COLUMN IF NOT EXISTS email VARCHAR(320),
    ADD COLUMN IF NOT EXISTS meta_fbp VARCHAR(500),
    ADD COLUMN IF NOT EXISTS meta_fbc VARCHAR(500),
    ADD COLUMN IF NOT EXISTS meta_event_source_url VARCHAR(2000),
    ADD COLUMN IF NOT EXISTS meta_client_ip VARCHAR(64),
    ADD COLUMN IF NOT EXISTS meta_user_agent VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS meta_marketing_consent BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS meta_schedule_sent_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS meta_qualified_lead_sent_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS meta_purchase_sent_at TIMESTAMPTZ;

-- Continue the audit/outbox design from the earlier Meta contract implementation.
-- Do not reuse its V60 number, which is already occupied in the production branch.
CREATE TABLE IF NOT EXISTS trial_lead_meta_conversions (
    lead_id UUID NOT NULL REFERENCES trial_leads(id) ON DELETE CASCADE,
    event_name VARCHAR(40) NOT NULL,
    event_id VARCHAR(160) NOT NULL UNIQUE,
    occurred_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    sent_at TIMESTAMPTZ,
    last_error VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (lead_id, event_name)
);

-- Also tolerate an installation that already applied the earlier contract table.
ALTER TABLE trial_lead_meta_conversions
    DROP CONSTRAINT IF EXISTS chk_trial_lead_meta_event,
    DROP CONSTRAINT IF EXISTS chk_trial_lead_meta_status,
    ADD COLUMN IF NOT EXISTS test_event_code VARCHAR(200),
    ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ;

ALTER TABLE trial_lead_meta_conversions
    ADD CONSTRAINT chk_trial_lead_meta_event
        CHECK (event_name IN ('Schedule','QualifiedLead','ConvertedLead','ContractSigned')),
    ADD CONSTRAINT chk_trial_lead_meta_status
        CHECK (status IN ('PENDING','SENDING','TEST_SENT','SENT','FAILED','EXPIRED','SUPPRESSED'));

CREATE INDEX IF NOT EXISTS idx_trial_meta_retry
    ON trial_lead_meta_conversions(next_attempt_at)
    WHERE status IN ('PENDING','FAILED','SENDING');

-- Expose already acknowledged legacy contracts without transmitting them again.
UPDATE trial_leads AS lead
SET meta_purchase_sent_at = conversion.sent_at
FROM trial_lead_meta_conversions AS conversion
WHERE conversion.lead_id = lead.id AND conversion.event_name = 'ContractSigned'
  AND conversion.status IN ('SENT','TEST_SENT') AND conversion.sent_at IS NOT NULL
  AND lead.meta_purchase_sent_at IS NULL;
