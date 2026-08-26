-- =====================================================================
-- V26 · Remediation claims outlive the scoring they were made against.
--
-- Ticking a step writes a negative ledger entry, and the entry used to
-- expire at the next assessment's cutoff on the premise that re-scoring
-- would measure whatever the step achieved. That premise is only true
-- once the work is actually visible on the board. Until then nothing the
-- model reads has changed, so re-scoring returned the same figure, the
-- credit fell behind the cutoff, and the points a team had just taken
-- off came straight back the moment anybody pressed Assess. A checkbox
-- whose effect the next click undoes is one people stop using.
--
-- New entries are written standing by
-- SprintRiskAdjustmentService.restateStanding. This is the same
-- correction for the rows already in the table: they were claims made
-- against a scoring that has since been superseded, and every one of
-- them was silently discarded. Counting them is the more accurate
-- history, and it is also what the ticked boxes on screen already say.
--
-- Releasing a claim is now a separate decision, made by
-- RiskRemediationService.settleAfterScoring: it withdraws an entry once
-- the assessment stops naming its reading as one raising the score,
-- which is the point at which the fall is in the model's own figure and
-- paying it from the overlay too would count the same work twice.
--
-- Scoped to the STEP: prefix, so board events and the requirement-change
-- register are untouched.
-- =====================================================================

UPDATE sprint_risk_adjustments
   SET survives_scoring = TRUE
 WHERE source_key LIKE 'STEP:%'
   AND survives_scoring = FALSE;
