"""Sprint cost models: initial planning and mid-sprint reforecast.

The bundles were trained with a small custom ``StageFeatureEngineer`` in the
notebook. Pickle stores the class path, not the class code, so the service has to
provide the same class before loading the joblib files. The transformer below is
kept deliberately small and mirrors the feature lists shipped beside the models.
"""

from __future__ import annotations

import json
import logging
import sys
from pathlib import Path
from typing import Any

import joblib
import numpy as np
import pandas as pd
from sklearn.base import BaseEstimator, TransformerMixin

log = logging.getLogger(__name__)


INITIAL_RAW_FEATURES = [
    "team_id",
    "total_tasks",
    "team_size",
    "planned_story_points",
    "planned_effort_minutes",
    "sprint_number",
    "historical_velocity_avg",
    "developer_availability_rate",
    "absence_days_total",
    "concurrent_assignments",
    "skill_match_ratio",
    "avg_seniority_level",
]

MID_RAW_FEATURES = [
    "team_id",
    "total_tasks",
    "team_size",
    "planned_story_points",
    "planned_effort_minutes",
    "completed_story_points",
    "total_effort_minutes",
    "elapsed_fraction",
    "sprint_number",
    "historical_velocity_avg",
    "developer_availability_rate",
    "absence_days_total",
    "overtime_hours_total",
    "concurrent_assignments",
    "skill_match_ratio",
    "avg_seniority_level",
]


DRIVER_LABELS = {
    "team_id": "team pattern",
    "total_tasks": "tasks in sprint",
    "team_size": "team size",
    "planned_story_points": "planned story points",
    "planned_effort_minutes": "planned task effort",
    "completed_story_points": "completed story points",
    "total_effort_minutes": "effort logged so far",
    "remaining_effort_minutes": "predicted remaining effort",
    "actual_cost_so_far": "actual cost so far",
    "blended_hourly_rate": "blended hourly rate",
    "elapsed_fraction": "elapsed sprint fraction",
    "sprint_number": "sprint number",
    "historical_velocity_avg": "historical velocity",
    "developer_availability_rate": "developer availability",
    "absence_days_total": "planned absence",
    "overtime_hours_total": "overtime",
    "concurrent_assignments": "concurrent assignments",
    "skill_match_ratio": "skill coverage",
    "avg_seniority_level": "average seniority",
    "planned_points_per_task": "points per task",
    "planned_points_per_developer": "points per developer",
    "historical_points_gap": "planned points above history",
    "availability_capacity": "available team capacity",
    "concurrency_per_developer": "assignments per developer",
    "skill_capacity_index": "skill-adjusted capacity",
    "planned_effort_per_task": "planned effort per task",
    "planned_effort_per_developer": "planned effort per developer",
    "completion_ratio": "completion ratio",
    "remaining_story_points": "remaining story points",
    "effort_per_task": "effort per task",
    "effort_per_developer": "effort per developer",
    "overtime_per_developer": "overtime per developer",
    "absence_rate_per_developer": "absence per developer",
    "effort_per_completed_point": "effort per completed point",
    "effort_burn_rate": "effort burn rate",
}


class StageFeatureEngineer(BaseEstimator, TransformerMixin):
    """Adds the engineered columns expected by the cost model pipelines."""

    def __init__(self, stage: str = "initial"):
        self.stage = stage

    def fit(self, X, y=None):  # noqa: N803 - sklearn uses X
        return self

    def transform(self, X):  # noqa: N803 - sklearn uses X
        frame = pd.DataFrame(X).copy()

        total_tasks = _positive(frame.get("total_tasks"), 1.0)
        team_size = _positive(frame.get("team_size"), 1.0)
        planned_points = _number(frame.get("planned_story_points"))
        historical_velocity = _number(frame.get("historical_velocity_avg"))
        availability = _number(frame.get("developer_availability_rate"))
        concurrent = _number(frame.get("concurrent_assignments"))
        skill_match = _number(frame.get("skill_match_ratio"))
        seniority = _number(frame.get("avg_seniority_level"))
        planned_effort = _number(frame.get("planned_effort_minutes"))

        frame["planned_points_per_task"] = planned_points / total_tasks
        frame["planned_points_per_developer"] = planned_points / team_size
        frame["historical_points_gap"] = planned_points - historical_velocity
        frame["availability_capacity"] = team_size * availability
        frame["concurrency_per_developer"] = concurrent / team_size
        frame["skill_capacity_index"] = skill_match * seniority * team_size
        frame["planned_effort_per_task"] = planned_effort / total_tasks
        frame["planned_effort_per_developer"] = planned_effort / team_size

        if self.stage == "mid":
            completed = _number(frame.get("completed_story_points"))
            effort = _number(frame.get("total_effort_minutes"))
            overtime = _number(frame.get("overtime_hours_total"))
            absence = _number(frame.get("absence_days_total"))
            elapsed = _number(frame.get("elapsed_fraction"))

            frame["completion_ratio"] = completed / _positive(planned_points, 1.0)
            frame["remaining_story_points"] = np.maximum(planned_points - completed, 0.0)
            frame["effort_per_task"] = effort / total_tasks
            frame["effort_per_developer"] = effort / team_size
            frame["overtime_per_developer"] = overtime / team_size
            frame["absence_rate_per_developer"] = absence / team_size
            frame["effort_per_completed_point"] = effort / _positive(completed, 1.0)
            frame["effort_burn_rate"] = effort / _positive(elapsed, 1.0)

        return frame.replace([np.inf, -np.inf], np.nan)


# The bundles were pickled from a notebook where the class lived in __main__.
sys.modules["__main__"].StageFeatureEngineer = StageFeatureEngineer  # type: ignore[attr-defined]


def _number(value: Any) -> pd.Series:
    if value is None:
        return pd.Series([0.0])
    return pd.to_numeric(value, errors="coerce").fillna(0.0).astype(float)


def _positive(value: Any, fallback: float) -> pd.Series:
    series = _number(value)
    return series.where(series > 0, fallback)


def _scalar(value: Any, fallback: float = 0.0) -> float:
    if value is None or value == "":
        return fallback
    try:
        numeric = float(value)
    except (TypeError, ValueError):
        return fallback
    if not np.isfinite(numeric):
        return fallback
    return numeric


def _team_id(value: Any) -> str:
    text = str(value or "UNKNOWN").strip()
    return text or "UNKNOWN"


class CostPredictor:
    """Loads one sprint cost bundle and scores one sprint at a time."""

    def __init__(self, model_dir: Path, stage: str):
        self.stage = stage
        self.model_dir = model_dir
        self.pipeline = joblib.load(model_dir / "model.joblib")
        self.contract = json.loads((model_dir / "feature_list.json").read_text())
        self.metrics = json.loads((model_dir / "metrics.json").read_text())
        self.raw_features: list[str] = list(self.contract["raw_features"])
        self.all_features: list[str] = list(self.contract["all_model_features"])
        self.deployment_method = str(
            self.metrics.get(
                "deployment_method",
                "planning_baseline" if stage == "initial" else "ml_remaining_effort",
            )
        )
        deployment_metrics = self.metrics.get(
            "deployment_metrics", self.metrics.get("test_metrics", {})
        )
        self.test_mape = float(deployment_metrics.get("mape", 30.0))
        self.test_r2 = float(deployment_metrics.get("r2", 0.0))

        log.info(
            "Loaded %s cost predictor: method=%s, %d raw features, MAPE %.2f, R2 %.3f",
            stage, self.deployment_method, len(self.raw_features), self.test_mape, self.test_r2,
        )

    def predict(self, payload: dict[str, Any]) -> dict[str, Any]:
        row, provenance = self._row(payload)
        frame = pd.DataFrame([row], columns=self.raw_features)
        hourly_rate = _scalar(payload.get("blended_hourly_rate"))
        if hourly_rate <= 0:
            raise ValueError("blended_hourly_rate must be greater than zero")
        currency = str(payload.get("currency") or "USD")
        actual_cost_so_far = max(0.0, _scalar(payload.get("actual_cost_so_far")))

        if self.stage == "initial":
            predicted_effort = max(0.0, row["planned_effort_minutes"])
            actual_cost_so_far = 0.0
        else:
            predicted_effort = max(0.0, float(self.pipeline.predict(frame)[0]))

        remaining_cost = predicted_effort / 60.0 * hourly_rate
        value = actual_cost_so_far + remaining_cost
        margin_rate = self._margin_rate(row, provenance)
        effort_margin = predicted_effort * margin_rate
        lower = actual_cost_so_far + max(0.0, predicted_effort - effort_margin) / 60.0 * hourly_rate
        upper = actual_cost_so_far + (predicted_effort + effort_margin) / 60.0 * hourly_rate
        drivers = self._cost_drivers(
            predicted_effort=predicted_effort,
            actual_cost_so_far=actual_cost_so_far,
            remaining_cost=remaining_cost,
            hourly_rate=hourly_rate,
            currency=currency,
        )

        return {
            "value": round(value, 2),
            "lower_bound": round(lower, 2),
            "upper_bound": round(upper, 2),
            "confidence": None,
            "label": self.stage.upper(),
            "drivers": drivers,
            "explanation": self._explain(
                value, lower, upper, predicted_effort, hourly_rate, currency, provenance
            ),
            "raw": {
                "stage": self.stage,
                "prediction_method": self.deployment_method,
                "predicted_effort_minutes": round(predicted_effort, 2),
                "actual_cost_so_far": round(actual_cost_so_far, 2),
                "future_labor_cost": round(remaining_cost, 2),
                "blended_hourly_rate": round(hourly_rate, 2),
                "currency": currency,
                "test_mape": self.test_mape,
                "test_r2": self.test_r2,
                "margin_rate": round(margin_rate, 4),
                "interval_basis": "deployment holdout MAPE; heuristic range, not calibrated probability",
            },
            "provenance": provenance,
        }

    def _row(self, payload: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
        defaults = {
            "team_id": "UNKNOWN",
            "total_tasks": 1,
            "team_size": 1,
            "planned_story_points": 0,
            "planned_effort_minutes": 0,
            "completed_story_points": 0,
            "total_effort_minutes": 0,
            "elapsed_fraction": 0,
            "sprint_number": 1,
            "historical_velocity_avg": 0,
            "developer_availability_rate": 1.0,
            "absence_days_total": 0,
            "overtime_hours_total": 0,
            "concurrent_assignments": 0,
            "skill_match_ratio": 1.0,
            "avg_seniority_level": 2.0,
        }

        defaulted: list[str] = []
        row: dict[str, Any] = {}
        for name in self.raw_features:
            raw = payload.get(name)
            if raw is None or raw == "":
                defaulted.append(name)
                raw = defaults.get(name, 0)
            default = defaults.get(name, 0)
            row[name] = _team_id(raw) if name == "team_id" else _scalar(raw, float(default))

        provenance = {
            "stage": self.stage,
            "prediction_method": self.deployment_method,
            "defaulted_to_service_default": defaulted,
            "model_test_mape": self.test_mape,
            "model_test_r2": self.test_r2,
        }
        return row, provenance

    def _cost_drivers(
        self,
        predicted_effort: float,
        actual_cost_so_far: float,
        remaining_cost: float,
        hourly_rate: float,
        currency: str,
    ) -> list[dict[str, Any]]:
        effort_feature = (
            "planned_effort_minutes"
            if self.stage == "initial"
            else "remaining_effort_minutes"
        )
        effort_label = (
            "Planned task effort"
            if self.stage == "initial"
            else "Predicted remaining effort"
        )
        drivers = [
            {
                "feature": effort_feature,
                "label": effort_label,
                "contribution": round(remaining_cost, 2),
                "direction": "INCREASE",
                "detail": (
                    f"{predicted_effort / 60.0:,.1f}h at "
                    f"{currency} {hourly_rate:,.2f}/h"
                ),
            }
        ]
        if self.stage == "mid":
            drivers.insert(
                0,
                {
                    "feature": "actual_cost_so_far",
                    "label": "Actual cost so far",
                    "contribution": round(actual_cost_so_far, 2),
                    "direction": "INCREASE",
                    "detail": f"{currency} {actual_cost_so_far:,.2f} already incurred",
                },
            )
        return drivers

    def _margin_rate(self, row: dict[str, Any], provenance: dict[str, Any]) -> float:
        base = max(self.test_mape / 100.0, 0.10)
        if self.stage == "mid" and row.get("total_effort_minutes", 0) <= 0:
            base += 0.08
        if self.stage == "mid" and row.get("completed_story_points", 0) <= 0:
            base += 0.05
        base += 0.02 * len(provenance.get("defaulted_to_service_default", []))
        return min(base, 0.60)

    def _explain(
        self,
        value: float,
        lower: float,
        upper: float,
        predicted_effort: float,
        hourly_rate: float,
        currency: str,
        provenance: dict[str, Any],
    ) -> str:
        if self.stage == "initial":
            sentence = (
                f"Initial forecast {currency} {value:,.0f} uses "
                f"{predicted_effort / 60.0:,.1f} planned task hours at the project's "
                f"{currency} {hourly_rate:,.2f}/h blended rate. The planning baseline "
                "is deployed because it beat the initial ML candidate on the holdout. "
            )
        else:
            sentence = (
                f"Mid-sprint final-cost forecast is {currency} {value:,.0f}: actual cost "
                f"so far plus {predicted_effort / 60.0:,.1f} ML-predicted remaining hours "
                f"priced at {currency} {hourly_rate:,.2f}/h. "
            )
        sentence += (
            f"The {currency} {lower:,.0f}-{upper:,.0f} range uses holdout percentage "
            "error as a heuristic and is not a probability confidence interval. "
        )
        missing = len(provenance.get("defaulted_to_service_default", []))
        if missing:
            sentence += (
                f"{missing} input{'s' if missing != 1 else ''} fell back to service "
                "defaults, so treat this forecast cautiously."
            )
        else:
            sentence += "All required effort-model inputs were supplied by SprintIQ."
        return sentence.strip()
