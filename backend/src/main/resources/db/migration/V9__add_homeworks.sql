CREATE TABLE homeworks (
    id         UUID        PRIMARY KEY,
    student_id UUID       NOT NULL
        CONSTRAINT fk_homeworks_student REFERENCES users (id),
    start_date DATE       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version    BIGINT     NOT NULL DEFAULT 0
);

CREATE INDEX idx_homeworks_student_start ON homeworks (student_id, start_date);

ALTER TABLE cards ADD COLUMN homework_id UUID;

-- Existing cards become one-card legacy homeworks, preserving their current availability.
INSERT INTO homeworks (id, student_id, start_date, created_at, updated_at, version)
SELECT (
           substr(md5(c.id::text), 1, 8) || '-' ||
           substr(md5(c.id::text), 9, 4) || '-' ||
           substr(md5(c.id::text), 13, 4) || '-' ||
           substr(md5(c.id::text), 17, 4) || '-' ||
           substr(md5(c.id::text), 21, 12)
       )::uuid,
       c.student_id,
       COALESCE(c.due_date, c.created_at::date, CURRENT_DATE),
       c.created_at,
       c.updated_at,
       0
FROM cards c
WHERE c.homework_id IS NULL;

UPDATE cards c
SET homework_id = (
        substr(md5(c.id::text), 1, 8) || '-' ||
        substr(md5(c.id::text), 9, 4) || '-' ||
        substr(md5(c.id::text), 13, 4) || '-' ||
        substr(md5(c.id::text), 17, 4) || '-' ||
        substr(md5(c.id::text), 21, 12)
    )::uuid
WHERE c.homework_id IS NULL;

ALTER TABLE cards
    ALTER COLUMN homework_id SET NOT NULL,
    ADD CONSTRAINT fk_cards_homework FOREIGN KEY (homework_id) REFERENCES homeworks (id);

CREATE INDEX idx_cards_homework_id ON cards (homework_id);
CREATE INDEX idx_cards_student_homework_due ON cards (student_id, homework_id, status, repetition_number, due_date);
