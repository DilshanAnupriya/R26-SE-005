-- =====================================================================
-- V15 · Point the velocity model at the estimation service
--
-- Same arrangement as V13 for story points: the endpoint lives in
-- ai_models rather than in application.yml, so swapping a model in does
-- not need a redeploy and every prediction records which version
-- answered it.
--
-- The version goes to 2.0.0 for the same reason it did for M1 — 1.0.0
-- was the mock's contract, and predictions store the version that
-- produced them. Leaving it would make every row from here on
-- indistinguishable from a mock-era one, and the cache key is
-- FeatureHasher.hash(code, version, features), so the bump is also what
-- stops six hours of mock answers being replayed.
--
-- The metrics are the bundle's own held-out numbers, including the
-- unflattering one: transfer_advantage is negative. Training only on the
-- target domain (MAE 2.59) beats fine-tuning from the base model
-- (2.92) once 322 sprints of that domain exist. Transfer earns its keep
-- at the other end of the curve, where five sprints already improve on
-- zero-shot (3.31 → 3.28) and no in-domain model could be trained at
-- all. Recorded rather than omitted because the admin screen reads this
-- to describe the model, and a model that oversells itself there is
-- worse than one with a known limit.
-- =====================================================================

UPDATE ai_models
SET endpoint_url = 'http://localhost:8000/predict/velocity',
    version = '2.0.0',
    metadata = metadata || jsonb_build_object(
        'outputUnit', 'story points per sprint',
        'target', 'next sprint velocity',
        'zeroShotMae', 3.31,
        'zeroShotR2', 0.79,
        'fineTunedMae', 2.92,
        'inDomainOnlyMae', 2.59,
        'transferAdvantage', -0.33,
        'intervalMethod', 'point estimate banded by the model''s held-out MAE, '
            || 'widened for inputs that fell back to training medians',
        'fineTunedDomains', jsonb_build_array('Logistics'),
        'knownDomains', jsonb_build_array('Finance', 'E-commerce', 'Healthcare',
                                          'Education', 'Logistics'),
        'trainedOn', 'Sprint_Velocity_Dataset_v3 (n=3000, 5 domains)'),
    updated_at = now()
WHERE code = 'M1_VELOCITY';
