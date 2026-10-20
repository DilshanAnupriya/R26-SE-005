-- =====================================================================
-- V36 · Bump M1 story points after changing how a small Story is read
--
-- The weights are unchanged. What changed is how SprintIQ's Story (and Task)
-- type reaches the model. The dataset splits work into "feature" (a new
-- capability) and "enhancement" (a change to an existing one); every Story
-- was sent as a feature, and features in the dataset run 5 to 13 points, so
-- a tooltip on an existing button came back as 8.
--
-- A Story is now read as an enhancement when everything measurable says it is
-- a small change — at most the dataset's median 3 components, no external
-- dependency, no security work, a write-up of at most 40 words — and stays a
-- feature otherwise, so large stories size exactly as before. "Export" and
-- "import" in a story's wording also no longer count as integration work.
--
-- The bump is what makes this reach a user: the prediction cache is keyed on
-- FeatureHasher.hash(code, version, features) with a six-hour TTL, so without
-- it the same Story keeps replaying the 8 an earlier build wrote.
-- =====================================================================

UPDATE ai_models
SET version = '2.3.0',
    metadata = metadata || jsonb_build_object(
        'storyTypeReading', 'Story and Task read as an enhancement when at most 3 components, '
            || 'no external dependency, no security work and at most 40 words; otherwise a feature'),
    updated_at = now()
WHERE code = 'M1_STORY_POINTS';
