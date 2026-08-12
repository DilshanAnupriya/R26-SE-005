-- =====================================================================
-- V14 · Bump M1 after changing how it reports confidence and drivers
--
-- The weights are untouched — a story gets the same number of points as it
-- did under 2.0.0. What changed is everything the estimate says about
-- itself:
--
--   * Confidence was derived from the Fibonacci span, an integer 0..5. The
--     conformal correction is tuned to a mean of about two steps, so nearly
--     every story scored exactly 60% — a one-line typo fix and a
--     multi-region migration alike. It now comes from the raw interval
--     relative to the estimate, which varies continuously, with a penalty
--     for inputs that fell back to training medians.
--   * Drivers named one-hot columns the story was not in ("task type:
--     feature" on a bug fix) and omitted the values that moved the
--     estimate. They now carry their value and drop absent categories.
--   * The explanation narrated only what pushed an estimate up, leaving
--     every below-average story with no stated reason.
--
-- The bump is what makes any of that reach a user. The cache key is
-- FeatureHasher.hash(code, version, features) with a six-hour TTL, so
-- without a new version the same story keeps replaying the row a pre-fix
-- build wrote, and even "Re-estimate" returns the old numbers. Predictions
-- also record the version that answered them, so this keeps rows scored by
-- the old confidence formula distinguishable from rows scored by the new
-- one.
-- =====================================================================

UPDATE ai_models
SET version = '2.1.0',
    metadata = metadata || jsonb_build_object(
        'confidenceBasis', 'raw conformal interval width relative to the estimate, '
            || 'penalised for defaulted inputs and unrecognised wording',
        'driverBasis', 'exact tree SHAP (pred_contribs) over structural columns, '
            || 'absent one-hot categories excluded'),
    updated_at = now()
WHERE code = 'M1_STORY_POINTS';
