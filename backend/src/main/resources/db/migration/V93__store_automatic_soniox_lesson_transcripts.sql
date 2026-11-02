-- Keep automatically transcribed lesson content separate from manually
-- provided notes and PDF uploads. A failed stream must never overwrite a
-- teacher's uploaded transcript.
ALTER TABLE lesson_preparations
  ADD COLUMN IF NOT EXISTS soniox_transcript_text TEXT,
  ADD COLUMN IF NOT EXISTS soniox_transcript_updated_at TIMESTAMPTZ;
