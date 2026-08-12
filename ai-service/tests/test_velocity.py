"""The velocity model: feature construction, domain routing, and what it claims.

The riskiest part of this integration is silent: four of the sixteen columns are
engineered from the other twelve, and a formula that drifts from the notebook's
still produces a plausible number. Most of these tests exist to make that drift
loud.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest

from app.velocity_features import TRAINING_MEDIANS, build_row, normalise_domain
from app.velocity_predictor import VelocityPredictor

BUNDLE = Path(__file__).resolve().parent.parent / "models" / "velocity_transfer_bundle.pkl"

pytestmark = pytest.mark.skipif(not BUNDLE.exists(), reason="velocity bundle not present")


@pytest.fixture(scope="module")
def predictor() -> VelocityPredictor:
    return VelocityPredictor(BUNDLE)


def sprint(**overrides):
    base = dict(team_size=6, sprint_duration_days=14, completion_rate=0.82,
                carryover_tasks=5, developer_availability=0.85, leave_days=2,
                planned_story_points=40, num_bugs=4, avg_experience_years=5,
                historical_velocities=[32, 35, 33, 34, 36])
    base.update(overrides)
    return {k: v for k, v in base.items() if v is not None}


class TestFeatureRow:
    def test_builds_every_column_the_model_expects(self, predictor):
        row, _ = build_row(sprint(), predictor.features)
        assert row.shape == (1, 16)
        assert not np.isnan(row).any()

    def test_engineered_columns_match_the_notebook_formulas(self, predictor):
        """These four are derived, not supplied. If they drift from the training
        code the model still answers, confidently and wrongly."""
        row, _ = build_row(sprint(), predictor.features)
        col = {name: row[0][i] for i, name in enumerate(predictor.features)}

        # velocity_delta = last3 - last5
        assert col["velocity_delta"] == pytest.approx(
            col["avg_velocity_last3"] - col["avg_velocity_last5"], abs=0.01)
        # effective_capacity = team_size * availability * duration/14
        assert col["effective_capacity"] == pytest.approx(6 * 0.85 * 1.0, abs=0.01)
        # stress_score = carryover*0.4 + bugs*0.3 + leave*0.3
        assert col["stress_score"] == pytest.approx(5 * 0.4 + 4 * 0.3 + 2 * 0.3, abs=0.01)
        # velocity_per_dev = last3 / team_size
        assert col["velocity_per_dev"] == pytest.approx(col["avg_velocity_last3"] / 6, abs=0.01)

    def test_averages_come_from_real_history_when_there_is_any(self, predictor):
        row, provenance = build_row(sprint(historical_velocities=[10, 20, 30]),
                                    predictor.features)
        col = {name: row[0][i] for i, name in enumerate(predictor.features)}
        assert col["avg_velocity_last3"] == pytest.approx(20.0)
        assert provenance["sprints_of_history"] == 3
        assert "avg_velocity_last3" not in provenance["defaulted_to_training_median"]

    def test_uses_only_the_last_three_and_five_sprints(self, predictor):
        row, _ = build_row(sprint(historical_velocities=[1, 1, 1, 1, 1, 1, 30, 30, 30]),
                           predictor.features)
        col = {name: row[0][i] for i, name in enumerate(predictor.features)}
        assert col["avg_velocity_last3"] == pytest.approx(30.0)
        assert col["avg_velocity_last5"] == pytest.approx((1 + 1 + 30 + 30 + 30) / 5)

    def test_a_short_history_does_not_borrow_a_median_for_the_five_average(self, predictor):
        """Substituting the dataset median for avg_velocity_last5 would invent a
        trend: velocity_delta is last3 - last5, so a fast team with two sprints
        would look like it was accelerating away from a number it never had."""
        row, _ = build_row(sprint(historical_velocities=[50, 52]), predictor.features)
        col = {name: row[0][i] for i, name in enumerate(predictor.features)}
        assert col["avg_velocity_last5"] == pytest.approx(col["avg_velocity_last3"])
        assert col["velocity_delta"] == pytest.approx(0.0, abs=0.01)

    def test_missing_inputs_fall_back_to_medians_not_zero(self, predictor):
        """Zero is a real value here — team_size=0 or availability=0 would tell the
        model something catastrophic rather than something unknown."""
        row, provenance = build_row({"historical_velocities": [30]}, predictor.features)
        col = {name: row[0][i] for i, name in enumerate(predictor.features)}
        assert col["team_size"] == TRAINING_MEDIANS["team_size"]
        assert col["developer_availability"] == TRAINING_MEDIANS["developer_availability"]
        assert "team_size" in provenance["defaulted_to_training_median"]

    def test_maps_an_experience_band_to_years(self, predictor):
        row, _ = build_row(sprint(avg_experience_years=None, dev_experience_level="junior"),
                           predictor.features)
        col = {name: row[0][i] for i, name in enumerate(predictor.features)}
        assert col["avg_experience_years"] == 1.5


class TestDomainRouting:
    def test_recognises_sprintiqs_lowercase_spelling(self):
        assert normalise_domain("ecommerce") == "E-commerce"
        assert normalise_domain("Finance") == "Finance"
        assert normalise_domain("  logistics ") == "Logistics"

    def test_an_unknown_domain_is_not_guessed_at(self):
        """Falling back to the base model is correct zero-shot behaviour. Snapping
        to the nearest known domain would route a healthcare team through a model
        fine-tuned on logistics without saying so."""
        assert normalise_domain("manufacturing") is None
        assert normalise_domain(None) is None

    def test_a_fine_tuned_domain_is_routed_to_its_own_model(self, predictor):
        result = predictor.predict(sprint(domain="logistics"))
        assert "fine-tuned" in result["provenance"]["routed_to"]

    def test_a_domain_without_a_fine_tune_is_marked_zero_shot(self, predictor):
        result = predictor.predict(sprint(domain="finance"))
        assert "zero-shot" in result["provenance"]["routed_to"]
        # It may well mention fine-tuning — it says none has happened for this
        # domain yet. What it must never do is claim a tuned model answered.
        assert "Scored by the model fine-tuned" not in result["explanation"]
        assert "universal model" in result["explanation"]

    def test_the_two_routes_do_not_return_the_same_number(self, predictor):
        """Otherwise the routing is decorative."""
        tuned = predictor.predict(sprint(domain="logistics"))["value"]
        zero = predictor.predict(sprint(domain="finance"))["value"]
        assert tuned != zero


class TestPrediction:
    def test_history_drives_the_prediction(self, predictor):
        fast = predictor.predict(sprint(historical_velocities=[45, 48, 46]))["value"]
        slow = predictor.predict(sprint(historical_velocities=[14, 15, 13]))["value"]
        assert fast > slow

    def test_losing_half_the_team_to_leave_lowers_it(self, predictor):
        full = predictor.predict(sprint())["value"]
        depleted = predictor.predict(sprint(developer_availability=0.5, leave_days=8))["value"]
        assert depleted < full

    def test_never_predicts_a_negative_velocity(self, predictor):
        result = predictor.predict(sprint(historical_velocities=[0, 0, 0],
                                          developer_availability=0.55, leave_days=5))
        assert result["value"] >= 0
        assert result["lower_bound"] >= 0

    def test_a_team_with_no_history_is_reported_as_unsupported(self, predictor):
        result = predictor.predict(sprint(historical_velocities=None))
        assert result["provenance"]["sprints_of_history"] == 0
        assert result["confidence"] < predictor.predict(sprint())["confidence"]
        assert "no completed sprints" in result["explanation"]

    def test_the_band_widens_when_inputs_are_missing(self, predictor):
        complete = predictor.predict(sprint())
        sparse = predictor.predict({"historical_velocities": [32, 35, 33]})
        width = lambda r: r["upper_bound"] - r["lower_bound"]
        assert width(sparse) > width(complete)

    def test_drivers_speak_the_backends_direction_vocabulary(self, predictor):
        for driver in predictor.predict(sprint())["drivers"]:
            assert driver["direction"] in ("INCREASE", "DECREASE")
            # The value is carried in the label, so "why 27?" can be checked.
            assert "(" in driver["feature"]

    def test_explanation_states_which_model_answered(self, predictor):
        assert "universal model" in predictor.predict(sprint(domain="finance"))["explanation"]
        assert "fine-tuned on Logistics" in predictor.predict(sprint(domain="logistics"))["explanation"]
