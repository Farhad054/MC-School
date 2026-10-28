CREATE TABLE completed_lesson_documents (
  id UUID PRIMARY KEY,
  teacher_id UUID NOT NULL REFERENCES users(id),
  event_id VARCHAR(255) NOT NULL,
  class_id UUID NOT NULL,
  board_id VARCHAR(128) NOT NULL,
  filename VARCHAR(255) NOT NULL,
  pdf BYTEA NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_completed_lesson_document UNIQUE (teacher_id, event_id, class_id, board_id)
);
CREATE INDEX idx_completed_lesson_teacher_event ON completed_lesson_documents(teacher_id, event_id, updated_at DESC);
