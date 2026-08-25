-- =====================================================================
-- V25 · Adjustments that a re-scoring cannot absorb.
--
-- The shown risk is the model's score plus a ledger overlay, and
-- pressing Assess clears the overlay on the premise that the model has
-- now read the same evidence for itself. That premise holds for board
-- state: blocked tasks, reopens, comments and silence are all live
-- readings the features re-read every time.
--
-- It does not hold for the requirement-change register. A logged change
-- is a historical fact about a sprint -- "scope was added on the ninth"
-- -- and no feature vector represents it: the models read requirement
-- churn from the requirements table, and a project that tracks its work
-- as stories rather than formal requirements has none. So the charge
-- was applied, Assess wiped it, the score fell back to where it started,
-- and the register was left describing something the number denied.
--
-- This flags the entries that have to keep counting. Default FALSE, so
-- every existing row and every board signal behaves exactly as before.
-- =====================================================================

ALTER TABLE sprint_risk_adjustments
    ADD COLUMN survives_scoring BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN sprint_risk_adjustments.survives_scoring IS
    'TRUE where no re-scoring can absorb this entry, because the evidence '
    'behind it is not a model input. Such entries keep counting past the '
    'assessment cutoff instead of being folded into the model''s own figure.';

-- The register's own charges, retrospectively. Anything already logged was
-- charged and then silently discarded by the next Assess; the sprint's
-- history is more accurate with them counted than without.
--
-- Every REQUIREMENT_CHANGE row comes from the register -- it is the only
-- writer of that type -- so the type is the whole test. Matching on the
-- reason text would have been a guess that quietly stopped working the
-- first time somebody reworded one.
UPDATE sprint_risk_adjustments
   SET survives_scoring = TRUE
 WHERE risk_type = 'REQUIREMENT_CHANGE';
