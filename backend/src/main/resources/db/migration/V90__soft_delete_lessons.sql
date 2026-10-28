-- Soft deletion hides a single lesson occurrence from schedules without
-- deleting recorded activity, students' documents, or class annotations.
ALTER TABLE lesson_history ADD COLUMN deleted_at TIMESTAMPTZ;
CREATE INDEX lesson_history_deleted_at_idx ON lesson_history(teacher_id, deleted_at)
    WHERE deleted_at IS NOT NULL;
