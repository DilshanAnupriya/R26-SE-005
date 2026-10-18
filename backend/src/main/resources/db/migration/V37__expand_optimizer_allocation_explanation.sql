-- Expand optimizer_allocations explanation column from VARCHAR(2000) to TEXT
-- to accommodate detailed JSON payloads containing SHAP feature attributions,
-- factor contribution breakdowns, and AI recommendation rationale.

ALTER TABLE optimizer_allocations ALTER COLUMN explanation TYPE TEXT;
