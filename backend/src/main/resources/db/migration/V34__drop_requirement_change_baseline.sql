-- V33 stored the requirement-change model's raw reading and the sprint's first reading,
-- because the score was briefly shown as the rise between them. The team chose the
-- model's full reading plus the board instead, so risk_score holds the raw reading again
-- and these two columns have nothing left to hold.
ALTER TABLE risk_assessments
    DROP COLUMN IF EXISTS model_score,
    DROP COLUMN IF EXISTS baseline_score;
