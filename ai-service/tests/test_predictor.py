"""What the estimate says about itself: confidence, drivers and the explanation.

These are regression tests for a class of bug that hides in plain sight — the
service kept answering, nothing errored, and every story came back with the same
60% confidence and a driver list that named categories the story did not belong
to. Nothing short of comparing two different stories catches that, so that is
what these do.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from app.predictor import StoryPointPredictor

MODEL = Path(__file__).resolve().parent.parent / "models" / "model_xgb_lgbm_uncertainty.pkl"

pytestmark = pytest.mark.skipif(not MODEL.exists(), reason="model bundle not present")


@pytest.fixture(scope="module")
def predictor() -> StoryPointPredictor:
    return StoryPointPredictor(MODEL)


def project_context(**overrides):
    """A fully specified story, so nothing falls back to a training median."""
    base = {
        "team_size": 5, "sprint_duration_days": 14, "domain": "finance",
        "tech_stack": "springboot", "dev_experience_level": "mid",
        "team_velocity_avg": 22, "recent_completion_rate": 0.8,
        "similar_task_count": 8, "task_age_days": 5,
        "has_integration": 0, "has_security": 0, "has_ui_complexity": 0,
    }
    base.update(overrides)
    return base


TINY = project_context(title="Fix typo in footer", task_type="bug",
                       num_components=1, external_apis=0)
HUGE = project_context(
    title="Migrate the billing platform to a multi-region datastore",
    description="Zero downtime, dual-write, backfill, reconciliation, compliance audit.",
    task_type="feature", num_components=12, external_apis=5,
    has_integration=1, has_security=1)


class TestConfidence:
    def test_two_very_different_stories_do_not_report_the_same_confidence(self, predictor):
        """The bug this file exists for.

        Confidence was 1 - span/5 over the *snapped* Fibonacci interval. That is an
        integer 0..5, and the conformal correction is tuned to a mean of about two
        steps, so essentially every story scored exactly 60% — a one-line typo fix
        and a multi-region migration included.
        """
        tiny = predictor.predict(TINY)["confidence"]
        huge = predictor.predict(HUGE)["confidence"]
        assert tiny != huge, f"both stories reported {tiny}"

    def test_stays_within_zero_and_one(self, predictor):
        for payload in (TINY, HUGE, {}):
            confidence = predictor.predict(payload)["confidence"]
            assert 0.0 <= confidence <= 1.0

    def test_a_story_the_model_cannot_read_scores_low(self, predictor):
        """Unrecognised wording plus defaulted inputs means the interval describes
        the training set, not this story. It must not present as precise."""
        blind = predictor.predict({"title": "SCIM user provisioning"})
        assert blind["provenance"]["text_terms_recognised"] == 0
        assert blind["confidence"] <= 0.3

    def test_supplying_the_project_context_beats_leaving_it_out(self, predictor):
        """Same story, once bare and once with the team described."""
        bare = predictor.predict({"title": "Add CSV export to the reports page",
                                  "num_components": 3})
        full = predictor.predict(project_context(
            title="Add CSV export to the reports page", num_components=3,
            external_apis=0))
        assert full["confidence"] > bare["confidence"]


class TestDrivers:
    def test_never_names_a_category_the_story_is_not_in(self, predictor):
        """A bug was being explained as "task type: feature", which is SHAP
        faithfully reporting the absence of a category and the UI reading it as a
        property of the story."""
        features = [d["feature"] for d in predictor.predict(TINY)["drivers"]]
        assert not any(f.startswith("task type: feature") for f in features)

    def test_names_the_category_the_story_is_in(self, predictor):
        features = [d["feature"] for d in predictor.predict(TINY)["drivers"]]
        assert any("bug" in f for f in features)

    def test_carries_the_value_that_moved_the_estimate(self, predictor):
        """"components touched" alone does not say whether it was 2 or 12 — which is
        the entire reason it moved the number."""
        drivers = predictor.predict(HUGE)["drivers"]
        components = next(d for d in drivers if d["feature"].startswith("components touched"))
        assert components["feature"] == "components touched (12)"

    def test_does_not_list_one_reason_twice(self, predictor):
        """word_count and char_count both label as "description length"."""
        features = [d["feature"].split(" (")[0] for d in predictor.predict(HUGE)["drivers"]]
        assert len(features) == len(set(features))

    def test_speaks_the_direction_vocabulary_the_backend_expects(self, predictor):
        for driver in predictor.predict(HUGE)["drivers"]:
            assert driver["direction"] in ("INCREASE", "DECREASE")

    def test_a_bigger_story_is_pushed_up_by_its_size(self, predictor):
        drivers = {d["feature"].split(" (")[0]: d for d in predictor.predict(HUGE)["drivers"]}
        assert drivers["components touched"]["direction"] == "INCREASE"
        assert drivers["external APIs"]["direction"] == "INCREASE"


class TestExplanation:
    def test_explains_a_small_story_too(self, predictor):
        """Only increases were narrated, so every below-average story — exactly where
        a planner is most likely to disagree — got no reason at all."""
        assert "Held down by" in predictor.predict(TINY)["explanation"]

    def test_explains_a_large_story(self, predictor):
        assert "Pushed up by" in predictor.predict(HUGE)["explanation"]

    def test_says_when_it_could_not_read_the_story(self, predictor):
        explanation = predictor.predict({"title": "SCIM user provisioning"})["explanation"]
        assert "wording" in explanation

    def test_counts_a_single_missing_input_in_the_singular(self, predictor):
        explanation = predictor.predict(project_context(
            title="Add CSV export", num_components=3, external_apis=0,
            task_type=None))["explanation"]
        assert "1 inputs" not in explanation
        assert "input were" not in explanation
