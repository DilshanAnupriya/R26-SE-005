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
    "sprint_number",
    "historical_velocity_avg",
    "developer_availability_rate",
    "concurrent_assignments",
    "skill_match_ratio",
    "avg_seniority_level",
]

MID_RAW_FEATURES = [
    "team_id",
    "total_tasks",
    "team_size",
    "planned_story_points",
    "completed_story_points",
    "total_effort_minutes",
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
    "completed_story_points": "completed story points",
    "total_effort_minutes": "effort logged so far",
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
    "completion_ratio": "completion ratio",
    "remaining_story_points": "remaining story points",
    "effort_per_task": "effort per task",
    "effort_per_developer": "effort per developer",
    "overtime_per_developer": "overtime per developer",
    "absence_rate_per_developer": "absence per developer",
    "effort_per_completed_point": "effort per completed point",
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

        frame["planned_points_per_task"] = planned_points / total_tasks
        frame["planned_points_per_developer"] = planned_points / team_size
        frame["historical_points_gap"] = planned_points - historical_velocity
        frame["availability_capacity"] = team_size * availability
        frame["concurrency_per_developer"] = concurrent / team_size
        frame["skill_capacity_index"] = frame["availability_capacity"] * skill_match

        if self.stage == "mid":
            completed = _number(frame.get("completed_story_points"))
            effort = _number(frame.get("total_effort_minutes"))
            overtime = _number(frame.get("overtime_hours_total"))
            absence = _number(frame.get("absence_days_total"))

            frame["completion_ratio"] = completed / _positive(planned_points, 1.0)
            frame["remaining_story_points"] = np.maximum(planned_points - completed, 0.0)
            frame["effort_per_task"] = effort / total_tasks
            frame["effort_per_developer"] = effort / team_size
            frame["overtime_per_developer"] = overtime / team_size
            frame["absence_rate_per_developer"] = absence / team_size
            frame["effort_per_completed_point"] = effort / _positive(completed, 1.0)

        return frame


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
        self.test_mape = float(self.metrics.get("test_metrics", {}).get("mape", 15.0))
        self.test_r2 = float(self.metrics.get("test_metrics", {}).get("r2", 0.0))

        log.info("Loaded %s cost model: %d raw features, MAPE %.2f, R2 %.3f",
                 stage, len(self.raw_features), self.test_mape, self.test_r2)

    def predict(self, payload: dict[str, Any]) -> dict[str, Any]:
        row, provenance = self._row(payload)
        frame = pd.DataFrame([row], columns=self.raw_features)

        value = max(0.0, float(self.pipeline.predict(frame)[0]))
        margin_rate = self._margin_rate(row, provenance)
        margin = value * margin_rate
        lower = max(0.0, value - margin)
        upper = value + margin

        engineered = StageFeatureEngineer(self.stage).transform(frame).iloc[0].to_dict()
        drivers = self._drivers(engineered)

        return {
            "value": round(value, 2),
            "lower_bound": round(lower, 2),
            "upper_bound": round(upper, 2),
            "confidence": self._confidence(margin_rate, provenance),
            "label": self.stage.upper(),
            "drivers": drivers,
            "explanation": self._explain(value, lower, upper, drivers, provenance),
            "raw": {
                "stage": self.stage,
                "test_mape": self.test_mape,
                "test_r2": self.test_r2,
                "margin_rate": round(margin_rate, 4),
            },
            "provenance": provenance,
        }

    def _row(self, payload: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
        defaults = {
            "team_id": "UNKNOWN",
            "total_tasks": 1,
            "team_size": 1,
            "planned_story_points": 0,
            "completed_story_points": 0,
            "total_effort_minutes": 0,
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
                raw = defaults[name]
            row[name] = _team_id(raw) if name == "team_id" else _scalar(raw, float(defaults[name]))

        provenance = {
            "stage": self.stage,
            "defaulted_to_service_default": defaulted,
            "model_test_mape": self.test_mape,
            "model_test_r2": self.test_r2,
        }
        return row, provenance

    def _drivers(self, engineered: dict[str, Any], limit: int = 5) -> list[dict[str, Any]]:
        try:
            regressor = self.pipeline.named_steps["model"].regressor_
            importances = regressor.feature_importances_
            feature_names = self.pipeline.named_steps["preprocessor"].get_feature_names_out()
        except Exception as error:  # noqa: BLE001
            log.warning("Could not compute %s cost drivers: %s", self.stage, error)
            return []

        ranked: list[tuple[str, float]] = []
        for name, importance in zip(feature_names, importances):
            plain = name.split("__", 1)[-1]
            if plain.startswith("team_id_"):
                plain = "team_id"
            ranked.append((plain, float(importance)))

        drivers = []
        seen: set[str] = set()
        for name, importance in sorted(ranked, key=lambda item: item[1], reverse=True):
            if len(drivers) >= limit:
                break
            if name in seen or importance <= 0:
                continue
            seen.add(name)
            value = engineered.get(name)
            detail = None
            if isinstance(value, (int, float, np.number)):
                detail = f"{float(value):.2f} in this sprint"
            elif value is not None:
                detail = str(value)
            drivers.append({
                "feature": name,
                "label": DRIVER_LABELS.get(name, name.replace("_", " ")),
                "contribution": round(importance, 4),
                "direction": "INCREASE",
                "detail": detail,
            })
        return drivers

    def _margin_rate(self, row: dict[str, Any], provenance: dict[str, Any]) -> float:
        base = max(self.test_mape / 100.0, 0.08)
        if self.stage == "initial":
            base = max(base, 0.22)
        else:
            base = max(base, 0.12)
            if row.get("total_effort_minutes", 0) <= 0:
                base += 0.08
            if row.get("completed_story_points", 0) <= 0:
                base += 0.05
        base += 0.02 * len(provenance.get("defaulted_to_service_default", []))
        return min(base, 0.60)

    def _confidence(self, margin_rate: float, provenance: dict[str, Any]) -> float:
        confidence = 1.0 - margin_rate
        confidence -= 0.02 * len(provenance.get("defaulted_to_service_default", []))
        return round(max(0.0, min(0.95, confidence)), 2)

    def _explain(self, value: float, lower: float, upper: float,
                 drivers: list[dict[str, Any]], provenance: dict[str, Any]) -> str:
        sentence = (
            f"{self.stage.replace('_', '-').title()} sprint cost forecast is "
            f"{value:,.0f}, typically between {lower:,.0f} and {upper:,.0f}. "
        )
        if drivers:
            sentence += "Most influenced by " + " and ".join(
                driver["label"].lower() for driver in drivers[:2]) + ". "
        missing = len(provenance.get("defaulted_to_service_default", []))
        if missing:
            sentence += (
                f"{missing} input{'s' if missing != 1 else ''} fell back to service "
                "defaults, so tighten the sprint plan before treating this as firm."
            )
        else:
            sentence += "All required cost-model inputs were supplied by SprintIQ."
        return sentence.strip()
