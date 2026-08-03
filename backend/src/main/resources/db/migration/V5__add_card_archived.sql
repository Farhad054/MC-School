-- Soft-delete for cards. A teacher "deleting" a card only archives it: the row
-- stays so study-session history that references it (study_session_items.card_id)
-- remains intact and there is no foreign-key violation. Archived cards are
-- excluded from every teacher/student list, summary, and study query.
ALTER TABLE cards
    ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;

-- Most queries filter to a student's non-archived cards.
CREATE INDEX idx_cards_student_active ON cards (student_id) WHERE archived = FALSE;
