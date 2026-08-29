-- The model's own number, before it was snapped to the Fibonacci scale.
--
-- The panel can already show this for a prediction made in the current session, but a
-- stored suggestion had nowhere to keep it — so the same story showed "a low 8" right
-- after estimating and nothing at all after a reload. Two different readings of one
-- unchanged story is worse than never showing it.
--
-- Nullable throughout: models that do not snap have no raw value distinct from their
-- result, and every suggestion recorded before this column existed genuinely has none.
ALTER TABLE stories
    ADD COLUMN ai_raw_points NUMERIC(6, 2);

COMMENT ON COLUMN stories.ai_raw_points IS
    'Pre-snap model output for ai_story_points. Null when the model does not snap.';
