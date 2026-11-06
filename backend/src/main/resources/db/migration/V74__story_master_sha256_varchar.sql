-- Align the SHA256 column with Hibernate's standard string mapping.
ALTER TABLE story_template_master ALTER COLUMN sha256 TYPE VARCHAR(64);
