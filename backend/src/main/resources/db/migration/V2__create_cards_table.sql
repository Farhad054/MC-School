-- Flashcards. Each card belongs to exactly one student (individual cards, per
-- the PRD) and is created by that student's teacher. A card carries only the
-- question and the correct answer; the wrong options (distractors) shown during
-- a session are generated on the fly from the correct answers of the student's
-- other cards, so they are never stored here.
--
-- Spaced-repetition state (simplified SM-2, see Sm2Scheduler):
--   repetition_number  how many successful reviews have happened (0..3)
--   due_date           the next day this card must be reviewed (NULL once LEARNED)
--   status             ACTIVE while it is still being learned, LEARNED after 3 reviews
CREATE TABLE cards (
    id                    UUID          PRIMARY KEY,
    student_id            UUID          NOT NULL
        CONSTRAINT fk_cards_student REFERENCES users (id),
    created_by_teacher_id UUID          NOT NULL
        CONSTRAINT fk_cards_teacher REFERENCES users (id),
    question              VARCHAR(1000) NOT NULL,
    correct_answer        VARCHAR(500)  NOT NULL,
    status                VARCHAR(20)   NOT NULL
        CONSTRAINT cards_status_check CHECK (status IN ('ACTIVE', 'LEARNED')),
    repetition_number     INTEGER       NOT NULL DEFAULT 0,
    due_date              DATE,
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL,
    version               BIGINT        NOT NULL DEFAULT 0
);

-- Teachers list all cards of one student; students study their own cards.
CREATE INDEX idx_cards_student_id ON cards (student_id);
-- Fast "which cards are due for this student today" lookups.
CREATE INDEX idx_cards_due ON cards (student_id, status, due_date);
