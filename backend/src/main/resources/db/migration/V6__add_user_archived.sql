-- Soft-delete for student accounts. The user row stays in place because cards
-- and study_sessions reference it for history, but archived accounts are hidden
-- from teacher lists and cannot authenticate or activate an invitation.
ALTER TABLE users
    ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_users_teacher_active
    ON users (teacher_id) WHERE archived = FALSE;
