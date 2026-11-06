-- Per-day review history snapshots for the teacher pilot view.
-- Rows are not derived from current card state after the fact: due_count and
-- completed_count are stored for the specific calendar day.
CREATE TABLE daily_review_history (
    id              UUID        PRIMARY KEY,
    student_id      UUID        NOT NULL
        CONSTRAINT fk_daily_review_history_student REFERENCES users (id),
    review_date     DATE        NOT NULL,
    due_count       INTEGER     NOT NULL,
    completed_count INTEGER     NOT NULL,
    status          VARCHAR(20) NOT NULL
        CONSTRAINT daily_review_history_status_check CHECK (status IN ('COMPLETED', 'PARTIAL', 'MISSED')),
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_daily_review_history_student_date UNIQUE (student_id, review_date)
);

CREATE INDEX idx_daily_review_history_student_date
    ON daily_review_history (student_id, review_date DESC);
