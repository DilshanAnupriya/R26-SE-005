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
from math import factorial
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

MID_SHAPLEY_FEATURES = (
    "completed_story_points",
    "total_effort_minutes",
    "historical_velocity_avg",
    "developer_availability_rate",
    "absence_days_total",
    "overtime_hours_total",
    "concurrent_assignments",
    "skill_match_ratio",
    "avg_seniority_level",
)

MID_DRIVER_LABELS = {
    "completed_story_points": "Completion progress",
    "total_effort_minutes": "Effort burn",
    "historical_velocity_avg": "Historical velocity",
    "developer_availability_rate": "Developer availability",
    "absence_days_total": "Planned absence",
    "overtime_hours_total": "Overtime",
    "concurrent_assignments": "Concurrent workload",
    "skill_match_ratio": "Skill coverage",
    "avg_seniority_level": "Team seniority",
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
        hourly_rate = _scalar(payload.get("blended_hourly_rate"))
        if hourly_rate <= 0:
            raise ValueError("blended_hourly_rate must be greater than zero")
        currency = str(payload.get("currency") or "USD")
        actual_cost_so_far = max(0.0, _scalar(payload.get("actual_cost_so_far")))

        if self.stage == "initial":
            predicted_effort = max(0.0, row["planned_effort_minutes"])
            actual_cost_so_far = 0.0
            reference_effort = predicted_effort
            effort_influences: dict[str, float] = {}
        else:
            predicted_effort, reference_effort, effort_influences = (
                self._mid_sprint_shapley(row)
            )

        remaining_cost = predicted_effort / 60.0 * hourly_rate
        value = actual_cost_so_far + remaining_cost
        margin_rate = self._margin_rate(row, provenance)
        effort_margin = predicted_effort * margin_rate
        lower = actual_cost_so_far + max(0.0, predicted_effort - effort_margin) / 60.0 * hourly_rate
        upper = actual_cost_so_far + (predicted_effort + effort_margin) / 60.0 * hourly_rate
        drivers, driver_meta = self._cost_drivers(
            row=row,
            predicted_effort=predicted_effort,
            reference_effort=reference_effort,
            effort_influences=effort_influences,
            actual_cost_so_far=actual_cost_so_far,
            remaining_cost=remaining_cost,
            hourly_rate=hourly_rate,
            currency=currency,
        )
        provenance["driver_method"] = driver_meta["method"]

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
                "driver_method": driver_meta["method"],
                "driver_reference_effort_minutes": driver_meta["reference_effort_minutes"],
                "driver_contribution_total": driver_meta["contribution_total"],
                "driver_features": driver_meta["features"],
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
        row: dict[str, Any],
        predicted_effort: float,
        reference_effort: float,
        effort_influences: dict[str, float],
        actual_cost_so_far: float,
        remaining_cost: float,
        hourly_rate: float,
        currency: str,
    ) -> tuple[list[dict[str, Any]], dict[str, Any]]:
        if self.stage == "initial":
            contribution = round(remaining_cost, 2)
            drivers = [
                {
                    "feature": "planned_effort_minutes",
                    "label": "Planned task effort",
                    "contribution": contribution,
                    "direction": "INCREASE",
                    "detail": (
                        f"Exact formula: {predicted_effort / 60.0:,.1f}h at "
                        f"{currency} {hourly_rate:,.2f}/h"
                    ),
                }
            ]
            return drivers, {
                "method": "exact_formula",
                "reference_effort_minutes": round(reference_effort, 2),
                "contribution_total": contribution,
                "features": ["planned_effort_minutes", "blended_hourly_rate"],
            }

        actual_contribution = round(actual_cost_so_far, 2)
        reference_contribution = round(reference_effort / 60.0 * hourly_rate, 2)
        influence_drivers = []
        for feature, effort_contribution in effort_influences.items():
            cost_contribution = round(effort_contribution / 60.0 * hourly_rate, 2)
            if abs(cost_contribution) < 0.01:
                continue
            influence_drivers.append(
                {
                    "feature": feature,
                    "label": MID_DRIVER_LABELS.get(feature, DRIVER_LABELS.get(feature, feature)),
                    "contribution": cost_contribution,
                    "direction": "INCREASE" if cost_contribution >= 0 else "DECREASE",
                    "detail": self._influence_detail(
                        feature=feature,
                        actual=float(row[feature]),
                        reference=float(self._mid_reference_values(row)[feature]),
                        currency=currency,
                        contribution=cost_contribution,
                    ),
                }
            )

        influence_drivers.sort(key=lambda driver: abs(driver["contribution"]), reverse=True)
        target_total = round(actual_cost_so_far + remaining_cost, 2)
        explained_total = (
            actual_contribution
            + reference_contribution
            + sum(driver["contribution"] for driver in influence_drivers)
        )
        # Absorb only currency-rounding residue into the reference component so the
        # displayed additive drivers always reconcile to the displayed forecast.
        reference_contribution = round(
            reference_contribution + target_total - explained_total, 2
        )
        drivers = [
            {
                "feature": "actual_cost_so_far",
                "label": "Actual cost so far",
                "contribution": actual_contribution,
                "direction": "INCREASE",
                "detail": f"{currency} {actual_cost_so_far:,.2f} already incurred",
            },
            {
                "feature": "model_reference",
                "label": "Reference remaining effort",
                "contribution": reference_contribution,
                "direction": "INCREASE" if reference_contribution >= 0 else "DECREASE",
                "detail": (
                    f"{reference_effort / 60.0:,.1f}h with live human-aware "
                    "signals set to neutral references"
                ),
            },
            *influence_drivers,
        ]
        contribution_total = round(
            sum(driver["contribution"] for driver in drivers), 2
        )
        return drivers, {
            "method": "reference_shapley",
            "reference_effort_minutes": round(reference_effort, 2),
            "contribution_total": contribution_total,
            "features": list(MID_SHAPLEY_FEATURES),
        }

    def _mid_sprint_shapley(
        self, row: dict[str, Any]
    ) -> tuple[float, float, dict[str, float]]:
        """Exact Shapley decomposition around explicit neutral live-signal values.

        Non-explained planning fields remain at their observed values. The model is
        scored for all 2^9 coalitions in one batch, keeping this local explanation
        deterministic and additive in original remaining-effort minutes.
        """
        references = self._mid_reference_values(row)
        features = list(MID_SHAPLEY_FEATURES)
        coalition_rows: list[dict[str, Any]] = []

        for mask in range(1 << len(features)):
            coalition = row.copy()
            for index, feature in enumerate(features):
                coalition[feature] = (
                    row[feature] if mask & (1 << index) else references[feature]
                )
            coalition_rows.append(coalition)

        frame = pd.DataFrame(coalition_rows, columns=self.raw_features)
        predictions = np.maximum(
            np.asarray(self.pipeline.predict(frame), dtype=float), 0.0
        )
        feature_count = len(features)
        denominator = factorial(feature_count)
        contributions: dict[str, float] = {}

        for index, feature in enumerate(features):
            bit = 1 << index
            contribution = 0.0
            for mask in range(1 << feature_count):
                if mask & bit:
                    continue
                coalition_size = mask.bit_count()
                weight = (
                    factorial(coalition_size)
                    * factorial(feature_count - coalition_size - 1)
                    / denominator
                )
                contribution += weight * (
                    predictions[mask | bit] - predictions[mask]
                )
            contributions[feature] = float(contribution)

        return (
            float(predictions[-1]),
            float(predictions[0]),
            contributions,
        )

    def _mid_reference_values(self, row: dict[str, Any]) -> dict[str, float]:
        elapsed = min(max(float(row["elapsed_fraction"]), 0.0), 1.0)
        team_size = max(float(row["team_size"]), 1.0)
        return {
            "completed_story_points": float(row["planned_story_points"]) * elapsed,
            "total_effort_minutes": float(row["planned_effort_minutes"]) * elapsed,
            "historical_velocity_avg": float(row["planned_story_points"]),
            "developer_availability_rate": 1.0,
            "absence_days_total": 0.0,
            "overtime_hours_total": 0.0,
            "concurrent_assignments": team_size,
            "skill_match_ratio": 1.0,
            "avg_seniority_level": 2.0,
        }

    def _influence_detail(
        self,
        feature: str,
        actual: float,
        reference: float,
        currency: str,
        contribution: float,
    ) -> str:
        comparisons = {
            "completed_story_points": f"{actual:,.1f} points vs {reference:,.1f} expected by now",
            "total_effort_minutes": f"{actual / 60.0:,.1f}h logged vs {reference / 60.0:,.1f}h planned burn",
            "historical_velocity_avg": f"{actual:,.1f} historical points vs {reference:,.1f} planned",
            "developer_availability_rate": f"{actual * 100:,.0f}% available vs {reference * 100:,.0f}% reference",
            "absence_days_total": f"{actual:,.1f} absence days vs {reference:,.1f} reference",
            "overtime_hours_total": f"{actual:,.1f} overtime hours vs {reference:,.1f} reference",
            "concurrent_assignments": f"{actual:,.0f} assignments vs {reference:,.0f} balanced reference",
            "skill_match_ratio": f"{actual * 100:,.0f}% skill coverage vs {reference * 100:,.0f}% reference",
            "avg_seniority_level": f"{actual:,.1f} average level vs {reference:,.1f} reference",
        }
        effect = "increases" if contribution >= 0 else "decreases"
        return (
            f"{comparisons.get(feature, f'{actual:,.2f} vs {reference:,.2f} reference')}; "
            f"{effect} remaining cost by {currency} {abs(contribution):,.2f}"
        )

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
                "Its cost driver is an exact formula component rather than an ML attribution. "
            )
        else:
            sentence = (
                f"Mid-sprint final-cost forecast is {currency} {value:,.0f}: actual cost "
                f"so far plus {predicted_effort / 60.0:,.1f} ML-predicted remaining hours "
                f"priced at {currency} {hourly_rate:,.2f}/h. The driver breakdown uses "
                "reference-based Shapley values in original cost units, so its signed "
                "components reconcile to the forecast. "
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
