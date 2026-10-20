"""Metamorphic tests for both M1 models: no right answer needed, only the right direction.

A story's "true" size is a matter of opinion, and so is a sprint's velocity before it
happens, so most of what a model gets asked has no answer key to test against. What
can be tested is how the answer must *move* when the question changes in a known way:

* a monotonic relation — more of something that makes work bigger must not make the
  estimate smaller (more components, more integrations, more history, more people
  present), and more of something that makes it smaller must not make it bigger
  (more leave, more open defects);
* an invariance — a change that does not alter the work must not alter the answer
  (capital letters, punctuation, whitespace, which field a sentence is in, fields the
  model does not read, history older than the window it uses).

The relations are checked on what a planner sees: the estimate, its range, its
confidence and its label. The IDs in brackets ([MR-01] and so on) are the rows of
docs/edge-case-testing-m1.md.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from app.features import TRAINING_MAXIMUMS
from app.predictor import StoryPointPredictor
from app.velocity_predictor import AVAILABILITY_TRAINING_FLOOR, VelocityPredictor

MODELS = Path(__file__).resolve().parent.parent / "models"
STORY_BUNDLE = MODELS / "model_xgb_lgbm_uncertainty.pkl"
VELOCITY_BUNDLE = MODELS / "velocity_transfer_bundle.pkl"

pytestmark = pytest.mark.skipif(not (STORY_BUNDLE.exists() and VELOCITY_BUNDLE.exists()),
                                reason="model bundles not present")

VISIBLE = ("value", "lower_bound", "upper_bound", "confidence", "label")


@pytest.fixture(scope="module")
def points() -> StoryPointPredictor:
    return StoryPointPredictor(STORY_BUNDLE)


@pytest.fixture(scope="module")
def velocity() -> VelocityPredictor:
    return VelocityPredictor(VELOCITY_BUNDLE)


def visible(result):
    return {key: result[key] for key in VISIBLE}


# ------------------------------------------------------------------ story points

TEAM = dict(team_size=5, sprint_duration_days=14, domain="finance", tech_stack="springboot",
            team_velocity_avg=22, recent_completion_rate=0.8, similar_task_count=8,
            task_age_days=5)

# Three sizes of story, each tried with three team experience levels: the relations
# have to hold across the space, not at one convenient point in it.
STORIES = {
    "typo bug": dict(title="Fix typo in footer", task_type="bug",
                     has_integration=0, has_security=0, has_ui_complexity=0),
    "csv export": dict(title="Add CSV export to the reports page",
                       description="Let a manager download the filtered report as a CSV file.",
                       task_type="feature", has_integration=0, has_security=0, has_ui_complexity=1),
    "migration": dict(title="Migrate the billing platform to a multi-region datastore",
                      description="Zero downtime, dual-write, backfill, reconciliation, "
                                  "compliance audit.",
                      task_type="feature", has_integration=1, has_security=1, has_ui_complexity=0),
}
LEVELS = ("junior", "mid", "senior")
CASES = [(name, level) for name in STORIES for level in LEVELS]
CASE_IDS = [f"{name}-{level}" for name, level in CASES]


def story(name, level, **overrides):
    return {**TEAM, **STORIES[name], "dev_experience_level": level,
            "num_components": 3, "external_apis": 1, **overrides}


def never_decreases(values):
    return all(later >= earlier for earlier, later in zip(values, values[1:]))


class TestStorySizeRelations:
    @pytest.mark.parametrize("name, level", CASES, ids=CASE_IDS)
    def test_more_components_never_lower_the_estimate(self, points, name, level):
        """[MR-01] Components 1 to 20. Inside the training range the model's own answer
        rises; past it the guard takes over at 13 — so the whole run never falls."""
        values = [points.predict(story(name, level, num_components=n))["value"]
                  for n in range(1, 21)]
        assert never_decreases(values), values

    @pytest.mark.parametrize("name, level", CASES, ids=CASE_IDS)
    def test_more_external_apis_never_lower_the_estimate(self, points, name, level):
        """[MR-02] External APIs 0 to 12. This relation found a defect: XGBoost read 7 or
        more as none, so the estimate fell back to the zero-API value at 7."""
        values = [points.predict(story(name, level, external_apis=n))["value"]
                  for n in range(0, 13)]
        assert never_decreases(values), values

    @pytest.mark.parametrize("name, level", CASES, ids=CASE_IDS)
    def test_inside_the_training_range_the_model_itself_barely_dips(self, points, name, level):
        """[MR-03] The model's own number, before snapping, inside the training range.
        Tree ensembles are not constrained to be monotonic, and this one is not quite:
        going from 2 to 3 external APIs lowers it by up to 0.1 of a point. Snapping
        hides that (MR-02); this pins it at a tenth so a larger regression shows."""
        for field in ("num_components", "external_apis"):
            start = 1 if field == "num_components" else 0
            raw = [points.predict(story(name, level, **{field: n}))["raw"]["point"]
                   for n in range(start, int(TRAINING_MAXIMUMS[field]) + 1)]
            dips = [earlier - later for earlier, later in zip(raw, raw[1:])]
            assert max(dips) <= 0.15, (field, raw)

    @pytest.mark.parametrize("name, level", CASES, ids=CASE_IDS)
    @pytest.mark.parametrize("flag", ["has_integration", "has_security", "has_ui_complexity"])
    def test_switching_on_a_kind_of_work_never_lowers_the_estimate(self, points, name, level, flag):
        """[MR-04] Integration, security or UI work added to a story never makes the model
        think it smaller — on the raw number, not only after snapping."""
        off = points.predict(story(name, level, **{flag: 0}))["raw"]["point"]
        on = points.predict(story(name, level, **{flag: 1}))["raw"]["point"]
        assert on >= off


class TestStoryTypeRelations:
    @pytest.mark.parametrize("name, level", CASES, ids=CASE_IDS)
    def test_a_story_typed_as_story_never_shrinks_as_it_grows(self, points, name, level):
        """[MR-15] SprintIQ's Story type, 1 to 12 components, no dependency. Crossing the
        small-change line (3 components) switches the reading from enhancement to feature;
        expected: that step goes up, never down."""
        values = []
        for n in range(1, 13):
            payload = {k: v for k, v in story(name, level, num_components=n, external_apis=0).items()
                       if k != "task_type"}
            values.append(points.predict({**payload, "story_type": "STORY"})["value"])
        assert never_decreases(values), values


class TestStoryInvariance:
    BASES = ["csv export", "migration", "typo bug"]

    @pytest.mark.parametrize("name", BASES)
    @pytest.mark.parametrize("change", ["capitals", "punctuation", "whitespace",
                                        "unread fields", "story type", "moved text"])
    def test_a_change_that_is_not_to_the_work_changes_nothing(self, points, name, change):
        """[MR-05] Same story, said differently. Whitespace found a defect: the length
        feature counted formatting, so pasting the same story with tabs and line breaks
        moved its range from 3-8 to 5-13."""
        base = story(name, "mid")
        title = base["title"]
        description = base.get("description") or "Nothing else to add."
        base["description"] = description

        if change == "capitals":
            other = {**base, "title": title.upper()}
        elif change == "punctuation":
            other = {**base, "title": title + "."}
        elif change == "whitespace":
            other = {**base, "title": "  " + "\t".join(title.split()) + " \n",
                     "description": "\n\n" + "    ".join(description.split()) + "\r\n"}
        elif change == "unread fields":
            # What the backend sends for its mock client and its records: the model
            # never reads these, and they must not move it.
            other = {**base, "priority": "HIGH", "label_count": 3, "word_count": 99,
                     "has_epic": True, "project_median_points": 8, "comparable_count": 4}
        elif change == "story type":
            # SprintIQ's STORY/BUG vocabulary against the dataset's feature/bug.
            other = {**{k: v for k, v in base.items() if k != "task_type"},
                     "story_type": "BUG" if base["task_type"] == "bug" else "STORY"}
        else:
            other = {**{k: v for k, v in base.items() if k != "description"},
                     "acceptance_criteria": description}

        assert visible(points.predict(other)) == visible(points.predict(base))

    def test_the_same_story_twice_gets_the_same_answer(self, points):
        """[MR-06] Determinism: no randomness at prediction time."""
        payload = story("migration", "mid")
        assert points.predict(payload) == points.predict(payload)


# ---------------------------------------------------------------------- velocity

SPRINT = dict(team_size=6, sprint_duration_days=14, completion_rate=0.82, carryover_tasks=5,
              developer_availability=0.85, leave_days=2, planned_story_points=40, num_bugs=4,
              avg_experience_years=5)
HISTORIES = {"slow": [20, 22, 21, 23, 22], "steady": [32, 35, 33, 34, 36],
             "fast": [45, 48, 46, 47, 49]}


def sprint(history, **overrides):
    return {**SPRINT, "historical_velocities": HISTORIES[history], **overrides}


class TestVelocityRelations:
    @pytest.mark.parametrize("history", list(HISTORIES))
    def test_a_team_that_delivered_more_is_never_predicted_less(self, velocity, history):
        """[MR-07] Every past sprint raised by 0, 5, 10, 15 and 20 points."""
        values = [velocity.predict({**SPRINT, "historical_velocities":
                                    [v + shift for v in HISTORIES[history]]})["value"]
                  for shift in range(0, 21, 5)]
        assert never_decreases(values), values

    @pytest.mark.parametrize("history", list(HISTORIES))
    def test_more_of_the_team_present_never_lowers_the_forecast(self, velocity, history):
        """[MR-08] Availability from 10% to 100%, across the training floor of 55% where
        the service's own scaling hands over to the model."""
        values = [velocity.predict(sprint(history, developer_availability=a))["value"]
                  for a in (0.1, 0.2, 0.4, 0.55, 0.6, 0.7, 0.8, 0.9, 1.0)]
        assert never_decreases(values), values

    @pytest.mark.parametrize("history", list(HISTORIES))
    @pytest.mark.parametrize("field, steps", [("leave_days", (0, 1, 2, 3, 5, 8, 12)),
                                              ("num_bugs", (0, 2, 4, 6, 9, 12)),
                                              ("carryover_tasks", (0, 3, 6, 9, 12))])
    def test_more_drag_never_raises_the_forecast(self, velocity, history, field, steps):
        """[MR-09] More leave, more open defects or more work carried over never makes
        the model expect more."""
        values = [velocity.predict(sprint(history, **{field: n}))["value"] for n in steps]
        assert all(later <= earlier for earlier, later in zip(values, values[1:])), values

    @pytest.mark.parametrize("history", list(HISTORIES))
    def test_sprints_older_than_the_window_change_nothing_but_the_count(self, velocity, history):
        """[MR-10] The model reads the last three and five sprints. Three wild sprints
        before those must not move the forecast, its range or its confidence."""
        plain = velocity.predict(sprint(history))
        padded = velocity.predict({**SPRINT, "historical_velocities": [5, 80, 2] + HISTORIES[history]})
        assert visible(padded) == visible(plain)
        assert padded["provenance"]["sprints_of_history"] == plain["provenance"]["sprints_of_history"] + 3

    @pytest.mark.parametrize("history", list(HISTORIES))
    @pytest.mark.parametrize("availability", [0.05, 0.2, 0.4])
    def test_below_the_floor_the_forecast_scales_with_who_is_there(self, velocity, history,
                                                                   availability):
        """[MR-11] Below the training floor the forecast is the model's answer times the
        share of the floor's capacity still present. Not exactly half for half the
        people: effective capacity is an input of its own and still moves the model's
        answer a little, which is the model doing its job."""
        assert availability < AVAILABILITY_TRAINING_FLOOR
        result = velocity.predict(sprint(history, developer_availability=availability))
        scaled = result["provenance"]["scaled_for_low_availability"]
        assert scaled["factor"] == pytest.approx(availability / AVAILABILITY_TRAINING_FLOOR, abs=1e-3)
        assert result["value"] == pytest.approx(scaled["model_point"] * scaled["factor"], abs=0.1)

    def test_the_same_sprint_twice_gets_the_same_answer(self, velocity):
        """[MR-12] Determinism."""
        payload = sprint("steady", domain="logistics")
        assert velocity.predict(payload) == velocity.predict(payload)

    @pytest.mark.parametrize("history", [[1, 2, 3], [2, 3, 4], [3, 3, 3]])
    def test_below_the_history_range_twice_the_record_is_twice_the_forecast(self, velocity, history):
        """[MR-13] Two slow teams, one with exactly double the other's sprints, both below
        the 13 points the trees can tell apart. Expected: double the forecast. Before,
        both got the same number — the model's answer for a 13-point team."""
        single = velocity.predict({**SPRINT, "historical_velocities": history})
        double = velocity.predict({**SPRINT, "historical_velocities": [2 * v for v in history]})
        assert double["value"] == pytest.approx(2 * single["value"], abs=0.2)

    def test_a_team_that_delivered_more_is_never_forecast_less_across_the_whole_range(self, velocity):
        """[MR-14] One team's average from 0 to 100 points, across both edges of the
        training range. Expected: the forecast never falls."""
        values = [velocity.predict({**SPRINT, "historical_velocities": [v, v, v]})["value"]
                  for v in (0, 2, 5, 9, 12, 13, 14, 20, 30, 40, 50, 54, 55, 60, 75, 100)]
        assert never_decreases(values), values
