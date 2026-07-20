-- Interface language chosen by the user (PRD: German and Russian in the MVP).
-- Students pick their language in Settings; the column also lets notification
-- emails be written in the right language later.
ALTER TABLE users
    ADD COLUMN preferred_language VARCHAR(5) NOT NULL DEFAULT 'RU'
        CONSTRAINT users_language_check CHECK (preferred_language IN ('DE', 'RU'));
