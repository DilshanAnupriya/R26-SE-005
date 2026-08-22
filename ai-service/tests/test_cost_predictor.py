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
    assert result["raw"]["predicted_effort_minutes"] == 4_800
    assert result["value"] == 8_000


def test_initial_forecast_changes_with_real_hourly_rate(initial_predictor):
    normal = initial_predictor.predict(cost_payload(blended_hourly_rate=100))
    doubled = initial_predictor.predict(cost_payload(blended_hourly_rate=200))

    assert doubled["value"] == pytest.approx(normal["value"] * 2)


def test_mid_forecast_adds_actual_cost_to_predicted_remaining_labor(mid_predictor):
    result = mid_predictor.predict(cost_payload())

    assert result["raw"]["prediction_method"] == "ml_remaining_effort"
    assert result["raw"]["predicted_effort_minutes"] > 0
    assert result["value"] == pytest.approx(
        result["raw"]["actual_cost_so_far"] + result["raw"]["future_labor_cost"],
        abs=0.02,
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
