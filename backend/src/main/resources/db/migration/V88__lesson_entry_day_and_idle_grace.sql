-- Additive migration: do not end existing rooms during deployment.
-- The first successful provider snapshot initializes their idle grace.
ALTER TABLE online_classes ADD COLUMN empty_since TIMESTAMPTZ;
ALTER TABLE online_classes ADD COLUMN media_close_pending BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX online_classes_pending_media_close_idx ON online_classes (id) WHERE media_close_pending;
