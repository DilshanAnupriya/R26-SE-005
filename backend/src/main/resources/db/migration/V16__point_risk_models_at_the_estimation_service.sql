-- =====================================================================
-- V16 · Point the two risk models at the estimation service
--
-- Same arrangement as V13 (story points) and V15 (velocity): the
-- endpoint lives in ai_models so a model can be swapped without a
-- redeploy, and every prediction records the version that answered it.
-- The version bump to 2.0.0 also invalidates the six-hour prediction
-- cache, whose key is FeatureHasher.hash(code, version, features) —
-- without it, mock-era answers would keep being replayed.
--
-- Both are CatBoost pairs: a multiclass head over Low/Medium/High and a
-- binary head over the outcome flag. Two heads because "how bad" and
-- "how likely" are different questions, and a High the model is 55% sure
-- of is a different conversation from a High it is 95% sure of.
--
-- The metrics are the held-out numbers from training, on projects
-- neither model has seen — the split is by project, not by sprint,
-- because sprints from one project share a team and a codebase and
-- splitting them at random measures memorisation rather than
-- generalisation.
--
-- The two models are not equally good and the metadata says so.
-- Over-budget reaches 0.91 grouped CV AUC and is worth acting on.
-- Requirement change reaches 0.76, with a binary F1 of 0.46 and
-- three-class accuracy of 0.50 against a 0.33 base rate — it finds
-- signal, but it misses more high-change sprints than it catches. It is
-- recorded here rather than quietly rounded up, because the admin screen
-- reads this to describe the model and a model that oversells itself
-- there is worse than one with a known limit.
-- =====================================================================

UPDATE ai_models
SET endpoint_url = 'http://localhost:8000/predict/risk/over-budget',
    version = '2.0.0',
    metadata = metadata || jsonb_build_object(
        'outputUnit', 'probability',
        'target', 'sprint effort exceeding 1.20x planned',
        'modelType', 'CatBoostClassifier (multiclass + binary heads)',
        'testAuc', 0.896,
        'testAccuracy', 0.828,
        'testF1', 0.722,
        'multiclassAccuracy', 0.673,
        'groupedCvAuc', 0.912,
        'groupedCvStd', 0.024,
        'severityScale', jsonb_build_array('Low', 'Medium', 'High'),
        'trainedOn', 'sprint_metrics_realistic_train_ready (project-level split)',
        'excludesPostHocFeatures', true),
    updated_at = now()
WHERE code = 'M2_OVER_BUDGET';

UPDATE ai_models
SET endpoint_url = 'http://localhost:8000/predict/risk/requirement-change',
    version = '2.0.0',
    metadata = metadata || jsonb_build_object(
        'outputUnit', 'probability',
        'target', 'requirement volatility above the 85th percentile',
        'modelType', 'CatBoostClassifier (multiclass + binary heads)',
        'testAuc', 0.786,
        'testAccuracy', 0.664,
        'testF1', 0.459,
        'multiclassAccuracy', 0.499,
        'groupedCvAuc', 0.759,
        'groupedCvStd', 0.012,
        'severityScale', jsonb_build_array('Low', 'Medium', 'High'),
        'trainedOn', 'req_change_realistic_train_ready (project-level split)',
        'excludesPostHocFeatures', true,
        -- Said plainly because the number invites over-reading: this head
        -- misses more high-change sprints than it catches.
        'knownLimitation', 'binary F1 0.46 — treat a Low as weak evidence, not an all-clear'),
    updated_at = now()
WHERE code = 'M2_REQUIREMENT_CHANGE';
