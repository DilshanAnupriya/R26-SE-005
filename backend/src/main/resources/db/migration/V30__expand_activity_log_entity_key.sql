-- Widen entity_key in activity_logs so that longer keys and entity identifiers
-- (like descriptive sprint or requirement labels) fit without throwing constraint errors.
ALTER TABLE activity_logs ALTER COLUMN entity_key TYPE VARCHAR(120);
