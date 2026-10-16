"""The two CatBoost risk models: feature mapping, routing and honesty.

The single most important test here is the leakage one. Both datasets carry
post-hoc columns that define the labels — actual_hours and effort_deviation_pct
for over-budget, requirement_volatility_score for requirement change. A model
trained or scored on those looks excellent and forecasts nothing, and nothing
about the output would reveal it.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from app.risk_features import (OVERBUDGET_MEDIANS, REQUIREMENT_CHANGE_MEDIANS,
                               build_overbudget_row, build_requirement_change_row,
                               count_ambiguity_markers, team_type_for)
from app.risk_predictor import RiskPredictor

MODELS = Path(__file__).resolve().parent.parent / "models"
OVERBUDGET = MODELS / "overbudget_model_bundle.pkl"
REQCHANGE = MODELS / "reqchange_model_bundle.pkl"

pytestmark = pytest.mark.skipif(
    not (OVERBUDGET.exists() and REQCHANGE.exists()),
    reason="risk bundles not present — run training/train_risk_models.py")

POSTHOC_COLUMNS = {
    "actual_hours", "effort_deviation_pct", "completed_story_points",
    "carry_over_points", "carry_over_rate", "completion_ratio", "rework_score",
    "blocked_tasks", "reopened_tasks", "remaining_work", "updated_story_count",
    "story_change_ratio", "acceptance_criteria_changes", "change_requests_count",
    "comments_on_stories", "requirement_volatility_score",
}


@pytest.fixture(scope="module")
def over_budget() -> RiskPredictor:
    return RiskPredictor(OVERBUDGET, "over_budget")


@pytest.fixture(scope="module")
def requirement_change() -> RiskPredictor:
    return RiskPredictor(REQCHANGE, "requirement_change")


def healthy_sprint(**overrides):
    base = dict(team_type="stable", team_size=6, base_velocity=30, complexity=2.0,
                hours_per_point=6.5, sprint_capacity_hours=200, planned_hours=150,
                committed_story_points=22, velocity_rolling_prev=28, velocity_trend=0.5,
                prev_carry_over_points=1, prev_carry_over_rate=0.05, prev_rework_score=2,
                prev_blocked_tasks=1, prev_reopened_tasks=1, fatigue=0.2, scope_added=0)
    base.update(overrides)
    return base


def well_specified(**overrides):
    base = dict(project_domain="fintech", project_phase="maturity", team_seniority=0.85,
                has_dedicated_BA=1, requirements_doc_quality=0.9, n_stakeholders=3,
                baseline_story_count=12, avg_story_length_words=45,
                avg_acceptance_criteria_per_story=5, ambiguity_marker_count=0,
                stories_without_AC=0, stakeholder_meetings_held=4,
                prev_change_requests_count=1, prev_acceptance_criteria_changes=0,
                prev_story_change_ratio=0.02, prev_high_risk_flag=0,
                prev_requirement_volatility_score=0.05)
    base.update(overrides)
    return base


class TestNoLeakage:
    def test_neither_model_reads_a_post_hoc_column(self, over_budget, requirement_change):
        """The columns that define the labels are only known once the sprint closes.
        A model scored on them would be reporting the past as a forecast."""
        for model in (over_budget, requirement_change):
            leaked = set(model.features) & POSTHOC_COLUMNS
            assert not leaked, f"{model.kind} reads post-hoc columns: {leaked}"

    def test_a_post_hoc_value_in_the_payload_is_ignored(self, over_budget):
        """Even if a caller sends one, it must not reach the model."""
        clean = over_budget.predict(healthy_sprint())
        polluted = over_budget.predict(healthy_sprint(actual_hours=900,
                                                      effort_deviation_pct=2.5))
        assert clean["probability"] == polluted["probability"]


class TestOverBudget:
    def test_a_healthy_sprint_scores_low(self, over_budget):
        result = over_budget.predict(healthy_sprint())
        assert result["risk_level"] == "Low"
        assert result["probability"] < 0.3

    def test_rework_carryover_and_fatigue_raise_it(self, over_budget):
        troubled = over_budget.predict(healthy_sprint(
            team_type="high_rework", planned_hours=260, committed_story_points=45,
            prev_carry_over_points=12, prev_carry_over_rate=0.45, prev_rework_score=14,
            prev_blocked_tasks=9, prev_reopened_tasks=11, fatigue=0.9, scope_added=15,
            velocity_trend=-6))
        assert troubled["probability"] > over_budget.predict(healthy_sprint())["probability"]
        assert troubled["risk_level"] in ("Medium", "High")

    def test_severity_speaks_the_backends_vocabulary(self, over_budget):
        assert over_budget.predict(healthy_sprint())["label"] in ("LOW", "MEDIUM", "HIGH")

    def test_class_probabilities_sum_to_one(self, over_budget):
        probabilities = over_budget.predict(healthy_sprint())["class_probabilities"]
        assert set(probabilities) == {"Low", "Medium", "High"}
        assert sum(probabilities.values()) == pytest.approx(1.0, abs=0.01)


class TestRequirementChange:
    def test_a_well_specified_sprint_scores_low(self, requirement_change):
        result = requirement_change.predict(well_specified())
        assert result["risk_level"] == "Low"

    def test_vague_stories_in_discovery_raise_it(self, requirement_change):
        vague = requirement_change.predict(well_specified(
            project_phase="discovery", has_dedicated_BA=0, requirements_doc_quality=0.15,
            n_stakeholders=12, ambiguity_marker_count=30, stories_without_AC=10,
            avg_acceptance_criteria_per_story=0.3, prev_change_requests_count=25,
            prev_story_change_ratio=0.6, prev_high_risk_flag=1,
            prev_requirement_volatility_score=0.85))
        assert vague["probability"] > requirement_change.predict(well_specified())["probability"]

    def test_maps_sprintiqs_domain_vocabulary(self, requirement_change):
        """SprintIQ stores 'finance'; this model was trained on 'fintech'."""
        row, provenance = build_requirement_change_row(
            {"domain": "finance"}, requirement_change.features)
        assert row["project_domain"] == "fintech"
        assert "project_domain" not in provenance["assumed_from_related_field"]

    def test_an_unmapped_domain_is_recorded_as_an_assumption(self, requirement_change):
        row, provenance = build_requirement_change_row(
            {"domain": "aerospace"}, requirement_change.features)
        assert row["project_domain"] == "enterprise_saas"
        assert "project_domain" in provenance["assumed_from_related_field"]

    def test_derives_phase_from_project_status(self, requirement_change):
        row, _ = build_requirement_change_row(
            {"project_status": "PLANNING"}, requirement_change.features)
        assert row["project_phase"] == "discovery"


class TestFeatureMapping:
    def test_missing_inputs_fall_back_to_medians_not_zero(self, over_budget):
        row, provenance = build_overbudget_row({}, over_budget.features)
        assert row["team_size"] == OVERBUDGET_MEDIANS["team_size"]
        assert "team_size" in provenance["defaulted_to_training_median"]

    def test_requirement_change_defaults_likewise(self, requirement_change):
        row, provenance = build_requirement_change_row({}, requirement_change.features)
        assert row["n_stakeholders"] == REQUIREMENT_CHANGE_MEDIANS["n_stakeholders"]
        assert "n_stakeholders" in provenance["defaulted_to_training_median"]

    def test_team_type_is_inferred_and_flagged_as_such(self):
        """'high_rework' is a strong signal to the model; presenting an inference as
        an observation is how a forecast becomes a fabrication."""
        archetype, guessed = team_type_for({"prev_rework_score": 12})
        assert archetype == "high_rework"
        assert guessed is True

    def test_a_supplied_team_type_is_not_overridden(self):
        archetype, guessed = team_type_for({"team_type": "stable", "prev_rework_score": 12})
        assert (archetype, guessed) == ("stable", False)

    def test_instability_is_judged_against_the_teams_own_baseline(self):
        """A swing of 5 points means something different to a team delivering 8 than
        to one delivering 40."""
        assert team_type_for({"velocity_trend": 5, "velocity_rolling_prev": 8})[0] == "fast_unstable"
        assert team_type_for({"velocity_trend": 5, "velocity_rolling_prev": 40})[0] == "stable"


class TestAmbiguityMarkers:
    def test_counts_the_words_that_make_a_requirement_negotiable(self):
        assert count_ambiguity_markers("The system should maybe export TBD data") >= 3

    def test_counts_question_marks(self):
        assert count_ambiguity_markers("Do we need pagination here?") >= 1

    def test_a_precise_story_scores_zero(self):
        assert count_ambiguity_markers(
            "The export button downloads a CSV of the current filtered rows.") == 0

    def test_tolerates_no_text(self):
        assert count_ambiguity_markers(None) == 0


class TestHonesty:
    def test_says_when_the_two_top_classes_are_close(self, requirement_change):
        """A 0.40/0.39 split is a model that cannot tell them apart, and reporting
        that as a confident verdict is the most misleading thing available."""
        result = requirement_change.predict(well_specified())
        ordered = sorted(result["class_probabilities"].values(), reverse=True)
        if ordered[0] - ordered[1] < 0.15:
            assert "leaning rather than a verdict" in result["explanation"]

    def test_confidence_falls_when_inputs_are_missing(self, over_budget):
        complete = over_budget.predict(healthy_sprint())["confidence"]
        sparse = over_budget.predict({"team_type": "stable"})["confidence"]
        assert sparse < complete

    def test_says_when_the_team_pattern_was_inferred(self, over_budget):
        result = over_budget.predict(healthy_sprint(team_type=None, prev_rework_score=12))
        assert "inferred" in result["explanation"]

    def test_drivers_carry_their_values(self, over_budget):
        for driver in over_budget.predict(healthy_sprint())["drivers"]:
            # The machine name alone, with the reading beside it rather than baked
            # into it — a consumer keying on the driver must not have to parse prose.
            assert driver["feature"] in over_budget.features
            assert "(" not in driver["feature"]
            assert driver["label"]
            assert driver["detail"] not in (None, "")
            assert driver["direction"] in ("INCREASE", "DECREASE")


def risky_sprint(**overrides):
    return healthy_sprint(**{**dict(
        team_size=5, base_velocity=18, complexity=3.2, hours_per_point=7.5,
        sprint_capacity_hours=160, planned_hours=260, committed_story_points=34,
        velocity_rolling_prev=16, velocity_trend=-1.5, prev_carry_over_points=6,
        prev_carry_over_rate=0.3, prev_rework_score=9, prev_blocked_tasks=6,
        prev_reopened_tasks=5, fatigue=0.7, scope_added=6), **overrides})


class TestShapFixes:
    """Every reading that raised over-budget risk carries a fix measured from SHAP.

    The fix is a measurement, not text: a target value the model itself was re-run at,
    and the probability it returned. These pin that it stays one.
    """

    def test_every_raising_reading_has_a_fix(self, over_budget):
        drivers = over_budget.predict(risky_sprint())["drivers"]
        raising = [d for d in drivers if d["direction"] == "INCREASE"]
        assert raising
        for driver in raising:
            assert driver["fix"]["method"] == "shap"
            assert driver["fix"]["summary"].startswith("SHAP:")

    def test_lowering_readings_carry_no_fix(self, over_budget):
        for driver in over_budget.predict(risky_sprint())["drivers"]:
            if driver["direction"] == "DECREASE":
                assert "fix" not in driver

    def test_a_direct_fix_is_one_the_model_confirms(self, over_budget):
        """Re-scoring the sprint at the fix's target gives the probability it quotes,
        and the reading's own SHAP value there is no longer positive."""
        drivers = over_budget.predict(risky_sprint())["drivers"]
        fix = next(d["fix"] for d in drivers if d["feature"] == "complexity")
        assert fix["kind"] == "direct" and fix["complete"]
        assert fix["shap_after"] <= 0

        moved = over_budget.predict(risky_sprint(complexity=fix["move"]["to"]))
        assert moved["probability"] == pytest.approx(fix["probability_after"], abs=1e-3)
        assert fix["probability_after"] < fix["probability_now"]

    def test_history_is_offset_rather_than_moved(self, over_budget):
        """Last sprint's rework cannot be undone, so its fix moves something that can
        be, and takes at least the rework's own SHAP value off the log-odds."""
        drivers = over_budget.predict(risky_sprint())["drivers"]
        fix = next(d["fix"] for d in drivers if d["feature"] == "prev_rework_score")
        assert fix["kind"] == "offset"
        assert fix["move"]["feature"] != "prev_rework_score"
        if fix["complete"]:
            assert fix["removed"] >= fix["shap_now"]

    def test_planned_hours_is_fixed_through_the_commitment(self, over_budget):
        drivers = over_budget.predict(risky_sprint())["drivers"]
        fix = next((d["fix"] for d in drivers if d["feature"] == "planned_hours"), None)
        if fix is not None and fix["kind"] == "direct":
            assert fix["move"]["feature"] == "committed_story_points"

    def test_an_unsupplied_reading_is_not_given_a_target(self, over_budget):
        """A reading filled with the training median is not this sprint's figure, and
        a fix that told a team to move a number nobody recorded would be invented."""
        drivers = over_budget.predict({"committed_story_points": 30, "complexity": 3})["drivers"]
        for driver in drivers:
            fix = driver.get("fix")
            if fix and driver["feature"] not in ("committed_story_points", "complexity"):
                assert fix["kind"] == "unsupplied"
                assert fix["move"] is None

    def test_the_explanation_is_read_off_shap(self, over_budget):
        """The sentence on the tab names each reason with its SHAP push, and its remedy
        is the measured fix that lowers the model's probability the most."""
        result = over_budget.predict(risky_sprint())
        top = next(d for d in result["drivers"] if d["direction"] == "INCREASE")
        assert "SHAP: raised most by" in result["explanation"]
        assert f"{top['label']} ({top['contribution']:+.2f})" in result["explanation"]

        fixes = [d["fix"] for d in result["drivers"]
                 if d.get("fix") and d["fix"].get("probability_after") is not None]
        best = min(fixes, key=lambda fix: fix["probability_after"])
        assert "Biggest fix from SHAP" in result["explanation"]
        assert f"to {best['probability_after'] * 100:.0f}%" in result["explanation"]

    def test_the_requirement_change_model_is_untouched(self, requirement_change):
        for driver in requirement_change.predict(well_specified())["drivers"]:
            assert "fix" not in driver
