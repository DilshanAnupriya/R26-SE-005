"""Edge cases for the velocity model (M1_VELOCITY): new teams, wild histories, empty sprints.

Asked for at Progress Presentation 2. test_velocity.py checks that the sixteen
columns are built the way the notebook built them; this file asks what happens when
the sprint is not a typical one — a team with no history, a team that delivered
nothing, a team whose velocity swings from 5 to 60, a sprint most of the team is away
for, and requests that are malformed or impossible.

Every test expects one of four behaviours, and its docstring says which:

* a sensible prediction — non-negative, finite, inside its own range, and a response
  FastAPI can serialise;
* a wider or less confident answer, when the model knows less;
* a recorded fallback — an unusable value treated as missing and named in
  ``provenance``, rather than scored as though it were a measurement;
* a clean error — 422 for a malformed request, 503 when no model is loaded.

The IDs in brackets ([V-01] and so on) are the rows of docs/edge-case-testing-m1.md.
"""

from __future__ import annotations

import json
import math
from pathlib import Path

import numpy as np
import pytest

from app.velocity_features import TRAINING_MEDIANS, build_row
from app.velocity_predictor import (AVAILABILITY_TRAINING_FLOOR, HISTORY_MAX_SCALE,
                                    HISTORY_TRAINING_CEILING, HISTORY_TRAINING_FLOOR,
                                    VelocityPredictor)

BUNDLE = Path(__file__).resolve().parent.parent / "models" / "velocity_transfer_bundle.pkl"

pytestmark = pytest.mark.skipif(not BUNDLE.exists(), reason="velocity bundle not present")

SPREAD = "team's own sprint-to-sprint spread"

# Values a caller can send that are not a usable number. 1e300 is finite to Python but
# past float32, which is what XGBoost reads, so to the model it is infinity.
UNUSABLE_NUMBERS = [float("nan"), float("inf"), float("-inf"), 1e300, "1e999", "six", [6], {"n": 6}]
UNUSABLE_IDS = ["nan", "inf", "-inf", "past-float32", "inf-as-string", "word", "list", "dict"]


@pytest.fixture(scope="module")
def predictor() -> VelocityPredictor:
    return VelocityPredictor(BUNDLE)


def sprint(**overrides):
    """A fully described sprint for a steady team. ``None`` drops a field."""
    base = dict(team_size=6, sprint_duration_days=14, completion_rate=0.82,
                carryover_tasks=5, developer_availability=0.85, leave_days=2,
                planned_story_points=40, num_bugs=4, avg_experience_years=5,
                historical_velocities=[32, 35, 33, 34, 36])
    base.update(overrides)
    return {k: v for k, v in base.items() if v is not None}


def column(row, predictor, name):
    return row[0][predictor.features.index(name)]


def width(result):
    return result["upper_bound"] - result["lower_bound"]


def assert_well_formed(result):
    """What every answer must satisfy, however strange the sprint."""
    for key in ("value", "lower_bound", "upper_bound", "confidence"):
        assert math.isfinite(result[key]), key
    assert 0.0 <= result["lower_bound"] <= result["value"] <= result["upper_bound"]
    assert 0.0 <= result["confidence"] <= 1.0
    assert result["label"] in ("LOW", "MEDIUM", "HIGH")
    assert isinstance(result["explanation"], str) and result["explanation"]
    # FastAPI serialises with allow_nan=False, so a NaN anywhere would be a 500.
    json.dumps(result, allow_nan=False)


def same_answer(a, b):
    keys = ("value", "lower_bound", "upper_bound", "confidence")
    return all(a[key] == b[key] for key in keys)


# ----------------------------------------------------------------------- cold start

class TestColdStart:
    def test_a_new_team_with_the_sprint_filled_in_is_flagged_not_trusted(self, predictor):
        """[V-01] No closed sprints yet, everything else known. Expected: a prediction
        from typical values, labelled HIGH with confidence at most 0.3. Before the fix
        the band was the model's miss on teams *with* history, widened by a quarter,
        and this case came out MEDIUM at 0.60 beside an explanation saying not to rely
        on it."""
        result = predictor.predict(sprint(historical_velocities=[]))
        assert_well_formed(result)
        provenance = result["provenance"]
        assert provenance["sprints_of_history"] == 0
        assert {"avg_velocity_last3", "avg_velocity_last5"} <= set(provenance["defaulted_to_training_median"])
        assert result["label"] == "HIGH"
        assert result["confidence"] <= 0.3
        assert "no completed sprints" in result["explanation"]

    def test_a_completely_empty_request_falls_back_to_a_typical_team(self, predictor):
        """[V-02] Expected: a prediction for a typical team, all eleven assumed inputs
        named, the band widened 12% for each, labelled HIGH."""
        result = predictor.predict({})
        assert_well_formed(result)
        assert len(result["provenance"]["defaulted_to_training_median"]) == 11
        assert result["raw"]["band"] == pytest.approx(
            predictor.zero_shot_mae * (1 + 0.12 * 11), abs=1e-3)
        assert result["label"] == "HIGH"
        assert result["confidence"] <= 0.3

    def test_averages_sent_without_a_list_count_as_history(self, predictor):
        """[V-03] Expected: both averages used, neither reported as defaulted. The five-
        sprint one used to be listed as defaulted even when it was supplied, which
        widened the band and miscounted the explanation."""
        payload = sprint(historical_velocities=None, avg_velocity_last3=40, avg_velocity_last5=38)
        row, provenance = build_row(payload, predictor.features)
        assert column(row, predictor, "avg_velocity_last5") == 38
        assert column(row, predictor, "velocity_delta") == pytest.approx(2.0)
        assert not {"avg_velocity_last3", "avg_velocity_last5"} & set(provenance["defaulted_to_training_median"])
        assert "no completed sprints" not in predictor.predict(payload)["explanation"]

    def test_a_three_sprint_average_alone_does_not_invent_a_trend(self, predictor):
        """[V-04] Expected: the five-sprint average taken to be the three-sprint one, so
        velocity_delta is 0 rather than the gap to a dataset median."""
        row, provenance = build_row(sprint(historical_velocities=None, avg_velocity_last3=40),
                                    predictor.features)
        assert column(row, predictor, "avg_velocity_last5") == 40
        assert column(row, predictor, "velocity_delta") == pytest.approx(0.0)
        assert "avg_velocity_last5" in provenance["derived_by_the_service"]
        assert "avg_velocity_last5" not in provenance["defaulted_to_training_median"]

    def test_one_sprint_of_history_is_called_thin(self, predictor):
        """[V-05] Expected: a prediction that says its averages rest on one sprint, in
        the singular."""
        result = predictor.predict(sprint(historical_velocities=[28]))
        assert_well_formed(result)
        assert result["provenance"]["sprints_of_history"] == 1
        assert "Only 1 completed sprint of history" in result["explanation"]


# --------------------------------------------------------- zero and volatile history

class TestZeroAndVolatileHistory:
    def test_a_team_that_delivered_nothing_is_predicted_low_and_never_negative(self, predictor):
        """[V-06] Three sprints of zero. Expected: a non-negative prediction below a
        steady team's. (The model alone never went below about 10 here; V-28 is the
        fix that brings it to the team's own record.)"""
        zero = predictor.predict(sprint(historical_velocities=[0, 0, 0]))
        steady = predictor.predict(sprint())
        assert_well_formed(zero)
        assert zero["value"] < steady["value"]

    def test_an_erratic_team_gets_a_range_as_wide_as_its_swings(self, predictor):
        """[V-07] 5, 60, 3, 58, 4. Expected: a band at least as wide as the team's own
        sprint-to-sprint spread, and a HIGH label. The model has no feature for
        volatility, so before the fix this team got the same ±3.3 as a steady one."""
        erratic = predictor.predict(sprint(historical_velocities=[5, 60, 3, 58, 4]))
        steady = predictor.predict(sprint())
        assert_well_formed(erratic)
        assert erratic["provenance"]["velocity_spread_last5"] == pytest.approx(30.14, abs=0.01)
        assert erratic["provenance"]["band_basis"] == SPREAD
        assert width(erratic) > 3 * width(steady)
        assert erratic["label"] == "HIGH"
        assert "vary by about 30 points" in erratic["explanation"]

    def test_a_steady_team_keeps_the_models_own_band(self, predictor):
        """[V-08] Expected: when the team's spread is smaller than the model's typical
        miss, the band is the model's, unchanged."""
        steady = predictor.predict(sprint())
        assert steady["provenance"]["band_basis"] == "model error"
        assert steady["raw"]["band"] == pytest.approx(predictor.zero_shot_mae, abs=1e-3)

    def test_a_long_history_uses_only_the_recent_sprints(self, predictor):
        """[V-09] A hundred sprints, the last five much faster. Expected: the averages and
        the spread come from the recent sprints, and all hundred are counted."""
        result = predictor.predict(sprint(historical_velocities=[10] * 95 + [40] * 5))
        assert_well_formed(result)
        assert result["provenance"]["sprints_of_history"] == 100
        assert result["provenance"]["velocity_spread_last5"] == 0
        assert result["provenance"]["band_basis"] == "model error"


# --------------------------------------------------------------------- heavy absence

class TestHeavyAbsence:
    def test_most_of_the_team_away_lowers_the_forecast_in_proportion(self, predictor):
        """[V-10] 5% availability — the lowest the backend sends. Expected: the forecast
        scaled by availability against the training floor (55%), said in the
        explanation, labelled HIGH. Before the fix the trees could not see absence
        below 55%, so 95% of the team away scored like 45% away."""
        full = predictor.predict(sprint(developer_availability=1.0, leave_days=0))
        away = predictor.predict(sprint(developer_availability=0.05, leave_days=80))
        assert_well_formed(away)

        scaled = away["provenance"]["scaled_for_low_availability"]
        assert scaled["factor"] == pytest.approx(0.05 / AVAILABILITY_TRAINING_FLOOR, abs=1e-3)
        assert away["value"] == pytest.approx(scaled["model_point"] * scaled["factor"], abs=0.1)
        assert away["value"] < 0.5 * full["value"]
        assert away["label"] == "HIGH"
        assert "scaled down" in away["explanation"]

    def test_at_the_training_floor_nothing_is_rescaled(self, predictor):
        """[V-11] Expected: availability inside the training range is the model's call
        alone, so the scaling meets the model exactly where the model stops knowing."""
        result = predictor.predict(sprint(developer_availability=AVAILABILITY_TRAINING_FLOOR))
        assert "scaled_for_low_availability" not in result["provenance"]

    def test_a_sprint_nobody_is_available_for_predicts_zero(self, predictor):
        """[V-12] Everyone away. Expected: 0 points, confidence 0, HIGH, and the workload
        read as fully overloaded rather than as a typical load."""
        row, _ = build_row(sprint(developer_availability=0), predictor.features)
        assert column(row, predictor, "workload_ratio") == 1.5

        result = predictor.predict(sprint(developer_availability=0))
        assert_well_formed(result)
        assert result["value"] == 0.0
        assert result["lower_bound"] == 0.0
        assert result["confidence"] == 0.0
        assert result["label"] == "HIGH"


# -------------------------------------------------------------------- extreme values

class TestExtremeValues:
    def test_values_far_past_the_training_range_plateau(self, predictor):
        """[V-13] Expected: tree models do not extrapolate, so 500 years of experience
        and 50,000, or 10,000 planned points and a million, give the same answer."""
        large = predictor.predict(sprint(avg_experience_years=500, planned_story_points=10_000))
        larger = predictor.predict(sprint(avg_experience_years=50_000, planned_story_points=1_000_000))
        assert_well_formed(larger)
        assert same_answer(large, larger)

    def test_every_input_at_an_absurd_extreme_still_answers(self, predictor):
        """[V-14] Expected: a sensible, finite, non-negative prediction — not a crash."""
        result = predictor.predict(dict(
            team_size=10_000, sprint_duration_days=365, completion_rate=50,
            carryover_tasks=10**6, developer_availability=1.0, leave_days=10**5,
            planned_story_points=10**7, num_bugs=10**6, avg_experience_years=300,
            historical_velocities=[10**6] * 5))
        assert_well_formed(result)


# --------------------------------------------- history outside the training range

class TestHistoryOutsideTheTrainingRange:
    """A team slower or faster than any in the training data. The trees read it as the
    edge they knew — 13 or 54 points — so the forecast follows the team's own record
    from there instead, in proportion."""

    def test_the_history_range_is_the_bundles_own(self, predictor):
        """[V-27] Expected: the limits are the lowest and highest split the base model
        makes on the three-sprint average, not numbers someone picked."""
        splits = predictor.base_model.get_booster().trees_to_dataframe()
        index = predictor.features.index("avg_velocity_last3")
        rows = splits[splits.Feature.isin([f"f{index}", "avg_velocity_last3"])]
        assert round(rows.Split.min(), 1) == HISTORY_TRAINING_FLOOR
        assert round(rows.Split.max(), 1) == HISTORY_TRAINING_CEILING

    def test_a_team_that_delivered_nothing_is_forecast_nothing(self, predictor):
        """[V-28] Three sprints of zero. Expected: 0, said why. Before, about 10: the
        model read an average of 0 as its lowest known 13."""
        result = predictor.predict(sprint(historical_velocities=[0, 0, 0]))
        assert_well_formed(result)
        assert result["value"] == 0.0
        assert result["label"] == "HIGH"
        assert "below the lowest the model was trained on" in result["explanation"]

    @pytest.mark.parametrize("history", [[3, 4, 5], [6, 7, 6], [10, 11, 12]])
    def test_a_slow_team_is_scaled_from_the_edge(self, predictor, history):
        """[V-29] Expected: the model's answer at the edge, times the team's average
        against that edge — so a team averaging 4 gets about 4/13 of it."""
        result = predictor.predict(sprint(historical_velocities=history))
        scaled = result["provenance"]["scaled_for_history_range"]
        average = sum(history[-3:]) / len(history[-3:])
        assert scaled["factor"] == pytest.approx(average / HISTORY_TRAINING_FLOOR, abs=1e-3)
        assert result["value"] == pytest.approx(scaled["model_point"] * scaled["factor"], abs=0.1)

    def test_a_fast_team_is_not_flattened_at_the_ceiling(self, predictor):
        """[V-30] A team averaging about 69. Expected: above what the model alone says,
        in proportion to the team's record. Before, about 45 — the model's answer for
        the fastest team it knew, whatever this team had actually done."""
        result = predictor.predict(sprint(historical_velocities=[65, 68, 67, 69, 70]))
        scaled = result["provenance"]["scaled_for_history_range"]
        assert result["value"] > scaled["model_point"]
        assert scaled["factor"] == pytest.approx(((67 + 69 + 70) / 3) / HISTORY_TRAINING_CEILING, abs=1e-3)
        assert not scaled["capped"]
        assert "above the highest the model was trained on" in result["explanation"]

    def test_an_implausible_history_is_capped_and_flagged(self, predictor):
        """[V-31] A history of a million points a sprint. Expected: scaled up by at most
        double, labelled HIGH, confidence at most 0.3, and the explanation asks for
        the history to be checked — not a forecast of 840,000 points."""
        result = predictor.predict(sprint(historical_velocities=[10**6] * 5))
        scaled = result["provenance"]["scaled_for_history_range"]
        assert scaled["capped"]
        assert scaled["factor"] == HISTORY_MAX_SCALE
        assert result["label"] == "HIGH"
        assert result["confidence"] <= 0.3
        assert "worth checking" in result["explanation"]

    @pytest.mark.parametrize("history", [[13, 13, 13], [32, 35, 33, 34, 36], [50, 52, 54]])
    def test_inside_the_range_the_model_answers_alone(self, predictor, history):
        """[V-32] Expected: no scaling for a team the model knows — its own answer stands."""
        result = predictor.predict(sprint(historical_velocities=history))
        assert "scaled_for_history_range" not in result["provenance"]

    def test_a_new_team_is_not_scaled(self, predictor):
        """[V-33] Expected: a team with no history is given the training median, which is
        inside the range, so the cold-start rules apply and the scaling does not."""
        result = predictor.predict(sprint(historical_velocities=[]))
        assert "scaled_for_history_range" not in result["provenance"]


# ----------------------------------------------------------------- malformed input

class TestMalformedInput:
    @pytest.mark.parametrize("bad", UNUSABLE_NUMBERS, ids=UNUSABLE_IDS)
    def test_an_unusable_number_is_treated_as_missing_and_named(self, predictor, bad):
        """[V-15] Expected: fallback to the training median, recorded as rejected."""
        row, provenance = build_row(sprint(team_size=bad), predictor.features)
        assert column(row, predictor, "team_size") == TRAINING_MEDIANS["team_size"]
        assert "team_size" in provenance["defaulted_to_training_median"]
        assert "team_size" in provenance["rejected_as_invalid"]
        assert np.isfinite(row).all()

    def test_an_infinite_number_scores_exactly_like_an_omitted_one(self, predictor):
        """[V-16] Expected: the same answer as leaving the field out."""
        infinite = predictor.predict(sprint(team_size=float("inf")))
        assert_well_formed(infinite)
        assert same_answer(infinite, predictor.predict(sprint(team_size=None)))

    @pytest.mark.parametrize("field, value", [
        ("team_size", 0), ("team_size", -3), ("sprint_duration_days", 0),
        ("developer_availability", -0.2), ("leave_days", -5), ("planned_story_points", -10),
        ("num_bugs", -1), ("completion_rate", -0.3), ("carryover_tasks", -2),
    ])
    def test_an_impossible_value_is_not_scored_as_a_measurement(self, predictor, field, value):
        """[V-17] Expected: fallback, recorded as rejected."""
        row, provenance = build_row(sprint(**{field: value}), predictor.features)
        assert column(row, predictor, field) == TRAINING_MEDIANS[field]
        assert field in provenance["rejected_as_invalid"]

    def test_junk_entries_in_the_history_are_dropped_and_named(self, predictor):
        """[V-18] Expected: only real velocities count — a word, a null, NaN, a negative
        and infinity are dropped — and the history is reported as partly rejected."""
        history = [30, "abc", None, float("nan"), -5, float("inf"), 34]
        row, provenance = build_row(sprint(historical_velocities=history), predictor.features)
        assert provenance["sprints_of_history"] == 2
        assert column(row, predictor, "avg_velocity_last3") == pytest.approx(32.0)
        assert "historical_velocities" in provenance["rejected_as_invalid"]

    def test_a_history_of_nothing_but_junk_is_a_cold_start(self, predictor):
        """[V-19] Expected: treated as no history at all. It used to count two "sprints"
        of history, so the cold-start warnings never fired."""
        result = predictor.predict(sprint(historical_velocities=["n/a", None]))
        assert_well_formed(result)
        assert result["provenance"]["sprints_of_history"] == 0
        assert result["label"] == "HIGH"
        assert result["confidence"] <= 0.3
        assert "no completed sprints" in result["explanation"]

    def test_a_history_that_is_not_a_list_is_rejected(self, predictor):
        """[V-20] Expected: a comma-separated string is not guessed at; it is rejected
        and the team treated as having no history."""
        row, provenance = build_row(sprint(historical_velocities="30,32,34"), predictor.features)
        assert provenance["sprints_of_history"] == 0
        assert "historical_velocities" in provenance["rejected_as_invalid"]

    def test_an_unknown_domain_goes_to_the_universal_model(self, predictor):
        """[V-21] Expected: zero-shot routing to the base model, said plainly — not a
        guess at the nearest fine-tuned domain."""
        result = predictor.predict(sprint(domain="manufacturing"))
        assert_well_formed(result)
        assert result["provenance"]["domain"] is None
        assert "zero-shot" in result["provenance"]["routed_to"]
        assert "universal model" in result["explanation"]

    def test_an_odd_spelling_of_a_known_domain_still_routes(self, predictor):
        """[V-22] Expected: case and padding do not lose the fine-tuned model."""
        result = predictor.predict(sprint(domain="  LOGISTICS "))
        assert "fine-tuned" in result["provenance"]["routed_to"]

    def test_an_unknown_experience_band_falls_back_to_the_median(self, predictor):
        """[V-23] Expected: fallback to the median years, recorded as defaulted."""
        row, provenance = build_row(
            sprint(avg_experience_years=None, dev_experience_level="expert"), predictor.features)
        assert column(row, predictor, "avg_experience_years") == TRAINING_MEDIANS["avg_experience_years"]
        assert "avg_experience_years" in provenance["defaulted_to_training_median"]


# ------------------------------------------------------------------------- over HTTP

@pytest.fixture
def service(predictor, monkeypatch):
    """The real endpoint, with this file's model in place of the startup loader."""
    main = pytest.importorskip("app.main")
    pytest.importorskip("httpx")
    from fastapi.testclient import TestClient

    monkeypatch.setattr(main, "velocity", predictor)
    return main, TestClient(main.app)


class TestOverHttp:
    def test_an_infinite_number_in_the_json_body_is_answered_not_crashed(self, service):
        """[V-24] JSON's 1e999 parses to infinity in Python. Expected: 200, a body that
        serialises, and the field named as rejected."""
        _, client = service
        response = client.post(
            "/predict/velocity",
            content='{"features": {"team_size": 1e999, "historical_velocities": [30, 32, 31]}}',
            headers={"Content-Type": "application/json"})
        assert response.status_code == 200, response.text
        assert "team_size" in response.json()["provenance"]["rejected_as_invalid"]

    @pytest.mark.parametrize("body", [{"features": "not a dict"}, {"features": [1, 2]},
                                      {"features": None}], ids=["string", "list", "null"])
    def test_a_request_that_is_not_shaped_like_one_is_a_422(self, service, body):
        """[V-25] Expected: a clean validation error, not a 500."""
        _, client = service
        assert client.post("/predict/velocity", json=body).status_code == 422

    def test_no_model_loaded_is_a_503(self, service, monkeypatch):
        """[V-26] Expected: 503, which the backend turns into "no prediction" rather than
        a made-up number."""
        main, client = service
        monkeypatch.setattr(main, "velocity", None)
        assert client.post("/predict/velocity", json={"features": {}}).status_code == 503
