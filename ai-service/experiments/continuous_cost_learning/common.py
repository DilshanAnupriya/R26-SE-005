"""Shared schema and evaluation helpers for the offline learning prototype."""

from __future__ import annotations

import csv
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import mean_absolute_error, r2_score, root_mean_squared_error


SNAPSHOT_FIELDS = [
    "sprint_id",
    "team_id",
    "snapshot_stage",
    "snapshot_at",
    "data_source",
    "planned_effort_minutes",
    "planned_story_points",
    "team_size",
    "historical_velocity_avg",
    "developer_availability_rate",
    "absence_days_total",
    "concurrent_assignments",
    "skill_match_ratio",
    "avg_seniority_level",
    "blended_hourly_rate",
    "elapsed_fraction",
    "completed_story_points",
    "logged_effort_minutes",
    "overtime_hours_total",
    "final_effort_minutes",
    "final_cost",
    "currency",
]

NUMERIC_FIELDS = [
    field
    for field in SNAPSHOT_FIELDS
    if field
    not in {"sprint_id", "team_id", "snapshot_stage", "snapshot_at", "data_source", "currency"}
]

INITIAL_FEATURES = [
    "planned_effort_minutes",
    "planned_story_points",
    "team_size",
    "historical_velocity_avg",
    "developer_availability_rate",
    "absence_days_total",
    "concurrent_assignments",
    "skill_match_ratio",
    "avg_seniority_level",
]

MID_FEATURES = INITIAL_FEATURES + [
    "elapsed_fraction",
    "completed_story_points",
    "logged_effort_minutes",
    "overtime_hours_total",
]

REQUIRED_STAGE_VALUES = {
    "initial": INITIAL_FEATURES + ["blended_hourly_rate"],
    "mid": [
        "elapsed_fraction",
        "completed_story_points",
        "logged_effort_minutes",
        "overtime_hours_total",
    ],
    "final": ["final_effort_minutes", "final_cost"],
}


def blank_snapshot() -> dict[str, str | float]:
    return {field: "" for field in SNAPSHOT_FIELDS}


def write_snapshots(path: Path, rows: list[dict[str, object]], replace: bool = False) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    mode = "w" if replace or not path.exists() else "a"
    write_header = mode == "w" or path.stat().st_size == 0
    with path.open(mode, newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=SNAPSHOT_FIELDS, extrasaction="raise")
        if write_header:
            writer.writeheader()
        writer.writerows(rows)


def load_snapshots(path: Path) -> pd.DataFrame:
    if not path.exists():
        raise FileNotFoundError(f"Snapshot store not found: {path}")
    frame = pd.read_csv(path)
    missing = [field for field in SNAPSHOT_FIELDS if field not in frame.columns]
    if missing:
        raise ValueError("Snapshot store is missing columns: " + ", ".join(missing))
    for field in NUMERIC_FIELDS:
        frame[field] = pd.to_numeric(frame[field], errors="coerce")
    frame["snapshot_at"] = pd.to_datetime(
        frame["snapshot_at"],
        utc=True,
        errors="raise",
        format="ISO8601",
    )
    return frame.sort_values(["snapshot_at", "sprint_id", "snapshot_stage"]).reset_index(drop=True)


def assemble_completed_sprints(frame: pd.DataFrame, stage: str) -> pd.DataFrame:
    if stage not in {"initial", "mid"}:
        raise ValueError("Stage must be 'initial' or 'mid'.")

    completed: list[dict[str, object]] = []
    for sprint_id, sprint_rows in frame.groupby("sprint_id", sort=False):
        initial_rows = sprint_rows.loc[sprint_rows["snapshot_stage"] == "initial"]
        mid_rows = sprint_rows.loc[sprint_rows["snapshot_stage"] == "mid"]
        final_rows = sprint_rows.loc[sprint_rows["snapshot_stage"] == "final"]
        if initial_rows.empty or final_rows.empty or (stage == "mid" and mid_rows.empty):
            continue

        initial = initial_rows.iloc[0]
        final = final_rows.iloc[-1]
        snapshot = initial.copy()
        if stage == "mid":
            mid = mid_rows.iloc[-1]
            for field in MID_FEATURES:
                if pd.notna(mid[field]):
                    snapshot[field] = mid[field]

        final_effort = float(final["final_effort_minutes"])
        if not np.isfinite(final_effort) or final_effort <= 0:
            continue

        sources = set(sprint_rows["data_source"].dropna().astype(str))
        source = "real" if sources == {"real"} else "simulated_demo"
        row = {
            "sprint_id": sprint_id,
            "team_id": initial["team_id"],
            "final_snapshot_at": final["snapshot_at"],
            "data_source": source,
            "final_effort_minutes": final_effort,
            "final_cost": float(final["final_cost"]),
        }
        features = INITIAL_FEATURES if stage == "initial" else MID_FEATURES
        row.update({field: float(snapshot[field]) for field in features})
        if stage == "mid":
            row["remaining_effort_minutes"] = max(
                final_effort - float(snapshot["logged_effort_minutes"]),
                0.0,
            )
        completed.append(row)

    result = pd.DataFrame(completed)
    if result.empty:
        return result
    return result.sort_values(["final_snapshot_at", "sprint_id"]).reset_index(drop=True)


def safe_mape(actual: np.ndarray, predicted: np.ndarray) -> float:
    denominator = np.clip(np.abs(np.asarray(actual, dtype=float)), 1.0, None)
    return float(np.mean(np.abs((np.asarray(actual) - np.asarray(predicted)) / denominator)) * 100)


def regression_metrics(actual: np.ndarray, predicted: np.ndarray) -> dict[str, float]:
    return {
        "mae": float(mean_absolute_error(actual, predicted)),
        "rmse": float(root_mean_squared_error(actual, predicted)),
        "mape": safe_mape(actual, predicted),
        "r2": float(r2_score(actual, predicted)),
    }
