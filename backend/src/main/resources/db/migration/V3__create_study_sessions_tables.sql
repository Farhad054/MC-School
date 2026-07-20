-- A study session is one pass through a set of a student's cards until every
-- card has been answered correctly (PRD glossary: "Сессия"). Sessions are
-- persisted so a student can leave and resume, and so completed sessions form
-- the session history.
--
-- session_type:
--   SCHEDULED  the mandatory homework run over cards that are due today;
--              completing it advances the SM-2 schedule.
--   PRACTICE   the voluntary "practice now" run; it never changes the schedule.
CREATE TABLE study_sessions (
    id                UUID        PRIMARY KEY,
    student_id        UUID        NOT NULL
        CONSTRAINT fk_sessions_student REFERENCES users (id),
    session_type      VARCHAR(20) NOT NULL
        CONSTRAINT sessions_type_check CHECK (session_type IN ('SCHEDULED', 'PRACTICE')),
    status            VARCHAR(20) NOT NULL
        CONSTRAINT sessions_status_check CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    total_cards       INTEGER     NOT NULL,
    -- Number of cards answered correctly on the first attempt (shown on the result screen).
    correct_first_try INTEGER     NOT NULL DEFAULT 0,
    started_at        TIMESTAMPTZ NOT NULL,
    completed_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    version           BIGINT      NOT NULL DEFAULT 0
);

-- At most one in-progress session per student, so "resume" is unambiguous.
CREATE UNIQUE INDEX uq_sessions_one_in_progress_per_student
    ON study_sessions (student_id) WHERE status = 'IN_PROGRESS';

-- One row per card taking part in a session, tracking its progress through the run.
--   state             PENDING until answered correctly, then ANSWERED_CORRECT
--   had_wrong_attempt whether the student ever got it wrong in this session
--   queue_position    ordering of the remaining queue; a wrong answer moves the
--                     card to the back so it comes round again later
CREATE TABLE study_session_items (
    id                UUID        PRIMARY KEY,
    session_id        UUID        NOT NULL
        CONSTRAINT fk_items_session REFERENCES study_sessions (id) ON DELETE CASCADE,
    card_id           UUID        NOT NULL
        CONSTRAINT fk_items_card REFERENCES cards (id),
    queue_position    INTEGER     NOT NULL,
    state             VARCHAR(20) NOT NULL
        CONSTRAINT items_state_check CHECK (state IN ('PENDING', 'ANSWERED_CORRECT')),
    had_wrong_attempt BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_session_card UNIQUE (session_id, card_id)
);

-- Serving the next pending card in queue order.
CREATE INDEX idx_items_queue ON study_session_items (session_id, state, queue_position);
