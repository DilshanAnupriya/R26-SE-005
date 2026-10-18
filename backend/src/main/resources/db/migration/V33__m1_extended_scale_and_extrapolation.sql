-- =====================================================================
-- V33 · Bump both M1 models after widening what they can answer
--
-- Neither model's weights changed. What changed is what the service does at
-- and past the edges of the data each was trained on:
--
--   * Story points are reported on the Planning Poker deck, 0.5 to 100, not
--     1 to 13. Inside the training data's size signals (at most 7 components
--     and 6 external APIs) the model answers as before. Past them a story is
--     extrapolated between the model's own trend continued and the biggest
--     story it knows scaled in proportion, labelled HIGH, and said to be
--     extrapolated. Before, every such story came back as 13 at most — and a
--     story with 7 or more external APIs was scored as if it had none.
--   * Story text is measured with its whitespace collapsed, so the same
--     story pasted with tabs or line breaks no longer gets a different range.
--   * Velocity for a team whose recent average is outside 13-54 points, the
--     range the trees can tell apart, follows the team's own record in
--     proportion (upwards at most double). Before, a team that delivered
--     nothing was forecast about 10, and a team averaging 69 about 45.
--
-- The bump is what makes any of that reach a user: the prediction cache is
-- keyed on FeatureHasher.hash(code, version, features) with a six-hour TTL,
-- so without it a story keeps replaying the capped answer an earlier build
-- wrote. Predictions also record the version that answered them.
-- =====================================================================

UPDATE ai_models
SET version = '2.2.0',
    metadata = metadata || jsonb_build_object(
        'scale', '0.5, 1, 2, 3, 5, 8, 13, 20, 40, 100',
        'beyondTrainingRange', 'more than 7 components or 6 external APIs: extrapolated '
            || 'between the model''s own trend and proportional scaling, labelled HIGH'),
    updated_at = now()
WHERE code = 'M1_STORY_POINTS';

UPDATE ai_models
SET version = '2.1.0',
    metadata = metadata || jsonb_build_object(
        'historyRange', 'three-sprint average outside 13-54: forecast scaled by the '
            || 'team''s average against that edge, upwards at most double'),
    updated_at = now()
WHERE code = 'M1_VELOCITY';
