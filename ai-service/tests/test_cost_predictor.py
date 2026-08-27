"""Regression tests for the hybrid sprint cost forecast."""

from __future__ import annotations

from pathlib import Path

import pytest

from app.cost_predictor import CostPredictor


MODELS = Path(__file__).resolve().parent.parent / "models"
INITIAL_MODEL = MODELS / "SprintCost-initial_model"
MID_MODEL = MODELS / "SprintCost-mid_model"

pytestmark = pytest.mark.skipif(
    not INITIAL_MODEL.exists() or not MID_MODEL.exists(),
    reason="sprint cost bundles are not present",
)


@pytest.fixture(scope="module")
def initial_predictor() -> CostPredictor:
    return CostPredictor(INITIAL_MODEL, "initial")


@pytest.fixture(scope="module")
def mid_predictor() -> CostPredictor:
    return CostPredictor(MID_MODEL, "mid")


def cost_payload(**overrides):
    base = {
        "team_id": "TEAM-1",
        "total_tasks": 8,
        "team_size": 4,
        "planned_story_points": 32,
        "planned_effort_minutes": 4_800,
        "completed_story_points": 14,
        "total_effort_minutes": 2_100,
        "elapsed_fraction": 0.5,
        "sprint_number": 12,
        "historical_velocity_avg": 29,
        "developer_availability_rate": 0.9,
        "absence_days_total": 2,
        "overtime_hours_total": 3,
        "concurrent_assignments": 6,
        "skill_match_ratio": 0.8,
        "avg_seniority_level": 2.5,
        "blended_hourly_rate": 100,
        "actual_cost_so_far": 3_800,
        "currency": "USD",
    }
    base.update(overrides)
    return base


def test_initial_uses_planned_effort_baseline(initial_predictor):
    result = initial_predictor.predict(cost_payload())

    assert result["raw"]["prediction_method"] == "planning_baseline"
    assert result["raw"]["driver_method"] == "exact_formula"
    assert result["raw"]["predicted_effort_minutes"] == 4_800
    assert result["value"] == 8_000
    assert result["drivers"] == [
        {
            "feature": "planned_effort_minutes",
            "label": "Planned task effort",
            "contribution": 8_000,
            "direction": "INCREASE",
            "detail": "Exact formula: 80.0h at USD 100.00/h",
        }
    ]


def test_initial_forecast_changes_with_real_hourly_rate(initial_predictor):
    normal = initial_predictor.predict(cost_payload(blended_hourly_rate=100))
    doubled = initial_predictor.predict(cost_payload(blended_hourly_rate=200))

    assert doubled["value"] == pytest.approx(normal["value"] * 2)


def test_mid_forecast_adds_actual_cost_to_predicted_remaining_labor(mid_predictor):
    result = mid_predictor.predict(cost_payload())

    assert result["raw"]["prediction_method"] == "ml_remaining_effort"
    assert result["raw"]["driver_method"] == "reference_shapley"
    assert result["raw"]["predicted_effort_minutes"] > 0
    assert result["value"] == pytest.approx(
        result["raw"]["actual_cost_so_far"] + result["raw"]["future_labor_cost"],
        abs=0.02,
    )


def test_mid_cost_drivers_are_additive_and_local(mid_predictor):
    result = mid_predictor.predict(cost_payload())
    drivers = result["drivers"]

    assert sum(driver["contribution"] for driver in drivers) == pytest.approx(
        result["value"], abs=0.01
    )
    assert result["raw"]["driver_contribution_total"] == result["value"]
    assert drivers[0]["feature"] == "actual_cost_so_far"
    assert drivers[1]["feature"] == "model_reference"
    assert any(driver["feature"] == "skill_match_ratio" for driver in drivers)
    assert all(
        driver["direction"] == (
            "INCREASE" if driver["contribution"] >= 0 else "DECREASE"
        )
        for driver in drivers
    )
    assert all(
        " vs " in driver["detail"] and "remaining cost" in driver["detail"]
        for driver in drivers[2:]
    )


def test_mid_neutral_signals_collapse_to_reference_component(mid_predictor):
    result = mid_predictor.predict(
        cost_payload(
            completed_story_points=16,
            total_effort_minutes=2_400,
            historical_velocity_avg=32,
            developer_availability_rate=1,
            absence_days_total=0,
            overtime_hours_total=0,
            concurrent_assignments=4,
            skill_match_ratio=1,
            avg_seniority_level=2,
        )
    )

    assert [driver["feature"] for driver in result["drivers"]] == [
        "actual_cost_so_far",
        "model_reference",
    ]
    assert sum(driver["contribution"] for driver in result["drivers"]) == pytest.approx(
        result["value"], abs=0.01
    )


def test_mid_future_cost_changes_proportionally_with_rate(mid_predictor):
    normal = mid_predictor.predict(cost_payload(blended_hourly_rate=100))
    doubled = mid_predictor.predict(cost_payload(blended_hourly_rate=200))

    assert doubled["raw"]["future_labor_cost"] == pytest.approx(
        normal["raw"]["future_labor_cost"] * 2,
        abs=0.02,
    )
    assert doubled["value"] - 3_800 == pytest.approx(
        (normal["value"] - 3_800) * 2,
        abs=0.03,
    )


def test_interval_is_not_presented_as_probability_confidence(mid_predictor):
    result = mid_predictor.predict(cost_payload())

    assert result["confidence"] is None
    assert "heuristic" in result["raw"]["interval_basis"]
    assert "not a probability confidence interval" in result["explanation"]


def test_rejects_missing_or_zero_rate(initial_predictor):
    with pytest.raises(ValueError, match="blended_hourly_rate"):
        initial_predictor.predict(cost_payload(blended_hourly_rate=0))
