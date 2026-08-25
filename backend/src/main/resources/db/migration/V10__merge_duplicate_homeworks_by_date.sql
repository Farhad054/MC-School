-- V9 created a safe legacy homework for every existing card. The product model
-- is one dated homework folder with many cards, so collapse same-student,
-- same-date legacy rows into one canonical homework and repoint their cards.
WITH ranked_homeworks AS (
    SELECT
        id,
        FIRST_VALUE(id) OVER (
            PARTITION BY student_id, start_date
            ORDER BY created_at ASC, id ASC
        ) AS canonical_id
    FROM homeworks
),
duplicate_homeworks AS (
    SELECT id, canonical_id
    FROM ranked_homeworks
    WHERE id <> canonical_id
)
UPDATE cards c
SET homework_id = d.canonical_id
FROM duplicate_homeworks d
WHERE c.homework_id = d.id;

WITH ranked_homeworks AS (
    SELECT
        id,
        FIRST_VALUE(id) OVER (
            PARTITION BY student_id, start_date
            ORDER BY created_at ASC, id ASC
        ) AS canonical_id
    FROM homeworks
),
duplicate_homeworks AS (
    SELECT id
    FROM ranked_homeworks
    WHERE id <> canonical_id
)
DELETE FROM homeworks h
USING duplicate_homeworks d
WHERE h.id = d.id;

DROP INDEX IF EXISTS idx_homeworks_student_start;

CREATE UNIQUE INDEX uq_homeworks_student_start ON homeworks (student_id, start_date);
