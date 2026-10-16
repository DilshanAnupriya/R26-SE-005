-- The requirement-change score is the model's movement since the sprint was first scored,
-- not its raw reading. These keep both halves of that subtraction beside the score, so the
-- gauge can say what the model actually read instead of looking as if it never ran.
-- Null for the other risk types and for rows written before this, which stored the raw
-- reading as risk_score.
ALTER TABLE risk_assessments
    ADD COLUMN model_score    NUMERIC(4, 3),
    ADD COLUMN baseline_score NUMERIC(4, 3);
