-- Store active learning seconds; event UUID makes retries idempotent.
CREATE TABLE learning_activity_events (
  event_id UUID PRIMARY KEY,
  student_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  activity_type VARCHAR(12) NOT NULL CHECK (activity_type IN ('CARDS','HOMEWORK')),
  reference_id UUID NOT NULL,
  duration_seconds SMALLINT NOT NULL CHECK (duration_seconds BETWEEN 1 AND 120),
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_learning_activity_student_time ON learning_activity_events(student_id,recorded_at DESC);
CREATE INDEX idx_learning_activity_reference ON learning_activity_events(activity_type,reference_id);
