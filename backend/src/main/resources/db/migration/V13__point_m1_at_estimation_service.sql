-- =====================================================================
-- V13 · Point M1 at the estimation service
--
-- The endpoint lives in ai_models rather than in application.yml, which is
-- deliberate: swapping a model in must not need a redeploy, and every
-- prediction records which version answered it. This sets the local
-- default; other environments change it through the admin API
-- (PATCH /ai/admin/models/{code}) or their own data migration.
--
-- The version is bumped to 2.0.0 because the served model is not the one
-- the registry described. 1.0.0 was the mock's contract. Predictions store
-- the version that answered them, so leaving it would make every row from
-- here on indistinguishable from a mock-era one.
-- =====================================================================

UPDATE ai_models
SET endpoint_url = 'http://localhost:8000/predict/story-points',
    version = '2.0.0',
    -- The bundle's own held-out numbers, so the admin screen shows what this
    -- model actually scores rather than what someone hoped it would.
    metadata = metadata || jsonb_build_object(
        'outputUnit', 'points',
        'scale', 'fibonacci',
        'testMae', 1.94,
        'withinOneStepAccuracy', 0.89,
        'intervalCoverage', 0.70,
        'intervalMethod', 'LightGBM P20/P80 quantile heads with conformal (CQR) correction',
        'trainedOn', 'Agile_Estimation_Dataset_Realistic_v2 (n=3000)'),
    updated_at = now()
WHERE code = 'M1_STORY_POINTS';
