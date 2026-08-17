-- Persist the student's first selected answer for each card in a session so the
-- result review survives refreshes and remains aligned with first-try scoring.
ALTER TABLE study_session_items
    ADD COLUMN first_selected_answer VARCHAR(500);
