#!/usr/bin/env python3
"""Train an offline initial or mid-sprint candidate from completed snapshots."""

from __future__ import annotations

import argparse
import json
from datetime import UTC, datetime
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.base import clone
from sklearn.ensemble import GradientBoostingRegressor, RandomForestRegressor
from sklearn.impute import SimpleImputer
from sklearn.metrics import make_scorer
from sklearn.model_selection import TimeSeriesSplit, cross_validate
from sklearn.pipeline import Pipeline

from common import (
    INITIAL_FEATURES,
    MID_FEATURES,
    assemble_completed_sprints,
    load_snapshots,
    regression_metrics,
    safe_mape,
)


ROOT = Path(__file__).resolve().parent


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", choices=["initial", "mid"], required=True)
    parser.add_argument(
        "--snapshots",
        type=Path,
        default=ROOT / "data" / "sample_sprint_snapshots.csv",
    )
    parser.add_argument("--output-root", type=Path, default=ROOT / "runs")
    parser.add_argument("--test-size", type=float, default=0.20)
    parser.add_argument("--cv-folds", type=int, default=5)
    parser.add_argument("--min-completed-sprints", type=int, default=30)
    parser.add_argument("--random-state", type=int, default=42)
    return parser.parse_args()


def candidate_models(random_state: int) -> dict[str, Pipeline]:
    return {
        "gradient_boosting": Pipeline(
            [
                ("imputer", SimpleImputer(strategy="median")),
                (
                    "model",
                    GradientBoostingRegressor(
                        n_estimators=180,
                        learning_rate=0.04,
                        max_depth=2,
                        loss="huber",
                        random_state=random_state,
                    ),
                ),
            ]
        ),
        "random_forest": Pipeline(
            [
                ("imputer", SimpleImputer(strategy="median")),
                (
                    "model",
                    RandomForestRegressor(
                        n_estimators=250,
                        min_samples_leaf=3,
                        max_features=0.8,
                        n_jobs=1,
                        random_state=random_state,
                    ),
                ),
            ]
        ),
    }


def cross_validation_scores(
    X_train: pd.DataFrame,
    y_train: pd.Series,
    models: dict[str, Pipeline],
    folds: int,
) -> dict[str, dict[str, float]]:
    cv = TimeSeriesSplit(n_splits=folds)
    mape_scorer = make_scorer(safe_mape, greater_is_better=False)
    results: dict[str, dict[str, float]] = {}
    for name, model in models.items():
        scores = cross_validate(
            model,
            X_train,
            y_train,
            cv=cv,
            scoring={
                "mae": "neg_mean_absolute_error",
                "rmse": "neg_root_mean_squared_error",
                "mape": mape_scorer,
                "r2": "r2",
            },
            n_jobs=1,
        )
        results[name] = {
            "mae": float(-scores["test_mae"].mean()),
            "rmse": float(-scores["test_rmse"].mean()),
            "mape": float(-scores["test_mape"].mean()),
            "r2": float(scores["test_r2"].mean()),
        }
    return results


def metric_lines(label: str, metrics: dict[str, float]) -> list[str]:
    return [
        label,
        f"  MAE : {metrics['mae']:.2f} minutes ({metrics['mae'] / 60:.2f} hours)",
        f"  RMSE: {metrics['rmse']:.2f} minutes ({metrics['rmse'] / 60:.2f} hours)",
        f"  MAPE: {metrics['mape']:.2f}%",
        f"  R2  : {metrics['r2']:.4f}",
    ]


def main() -> None:
    args = parse_args()
    snapshots_path = args.snapshots.expanduser().resolve()
    snapshots = load_snapshots(snapshots_path)
    completed = assemble_completed_sprints(snapshots, args.stage)
    if len(completed) < args.min_completed_sprints:
        raise ValueError(
            f"Only {len(completed)} completed {args.stage} examples are available; "
            f"at least {args.min_completed_sprints} are required."
        )

    features = INITIAL_FEATURES if args.stage == "initial" else MID_FEATURES
    target = "final_effort_minutes" if args.stage == "initial" else "remaining_effort_minutes"
    holdout_rows = max(1, int(len(completed) * args.test_size))
    split_index = len(completed) - holdout_rows
    if split_index <= args.cv_folds:
        raise ValueError("Not enough training rows for the requested time-series cross-validation.")

    X_train = completed.loc[: split_index - 1, features].copy()
    X_test = completed.loc[split_index:, features].copy()
    y_train = completed.loc[: split_index - 1, target].copy()
    y_test = completed.loc[split_index:, target].copy()

    models = candidate_models(args.random_state)
    cv_metrics = cross_validation_scores(X_train, y_train, models, args.cv_folds)
    selected_name = min(
        cv_metrics,
        key=lambda name: (cv_metrics[name]["mape"], cv_metrics[name]["rmse"]),
    )
    candidate = clone(models[selected_name]).fit(X_train, y_train)
    candidate_predictions = np.maximum(candidate.predict(X_test), 0.0)

    if args.stage == "initial":
        baseline_predictions = X_test["planned_effort_minutes"].to_numpy(dtype=float)
    else:
        baseline_predictions = np.maximum(
            X_test["planned_effort_minutes"].to_numpy(dtype=float)
            - X_test["logged_effort_minutes"].to_numpy(dtype=float),
            0.0,
        )

    candidate_metrics = regression_metrics(y_test.to_numpy(), candidate_predictions)
    baseline_metrics = regression_metrics(y_test.to_numpy(), baseline_predictions)
    output_dir = args.output_root.expanduser().resolve() / args.stage
    output_dir.mkdir(parents=True, exist_ok=True)

    joblib.dump(candidate, output_dir / "candidate_model.joblib")
    predictions = completed.loc[split_index:, ["sprint_id", "team_id", "final_snapshot_at", "data_source"]].copy()
    predictions["actual_effort_minutes"] = y_test.to_numpy(dtype=float)
    predictions["candidate_prediction_minutes"] = candidate_predictions
    predictions["baseline_prediction_minutes"] = baseline_predictions
    predictions.to_csv(output_dir / "holdout_predictions.csv", index=False)

    report = {
        "generated_at_utc": datetime.now(UTC).isoformat(),
        "prototype_scope": "offline_only_no_production_integration",
        "stage": args.stage,
        "target": target,
        "snapshot_store": str(snapshots_path),
        "data_sources": sorted(completed["data_source"].unique().tolist()),
        "completed_sprints": int(len(completed)),
        "training_sprints": int(len(X_train)),
        "holdout_sprints": int(len(X_test)),
        "split_method": "time_ordered_80_20",
        "cv_method": f"TimeSeriesSplit({args.cv_folds})",
        "random_state": args.random_state,
        "features": features,
        "selected_candidate": selected_name,
        "cross_validation_metrics": cv_metrics,
        "candidate_holdout_metrics": candidate_metrics,
        "baseline_holdout_metrics": baseline_metrics,
        "candidate_beats_baseline": bool(
            candidate_metrics["mape"] < baseline_metrics["mape"]
            and candidate_metrics["rmse"] < baseline_metrics["rmse"]
        ),
        "deployment_status": "not_evaluated_run_evaluate_candidate.py",
    }
    (output_dir / "evaluation.json").write_text(json.dumps(report, indent=2))

    lines = [
        f"CONTINUOUS-LEARNING PROTOTYPE: {args.stage.upper()} CANDIDATE",
        "=" * 62,
        "Scope          : offline only; no production model is modified",
        f"Data sources   : {', '.join(report['data_sources'])}",
        f"Completed      : {len(completed)} sprints",
        f"Training       : {len(X_train)} earlier sprints",
        f"Holdout        : {len(X_test)} later unseen sprints",
        f"Cross-validation: {args.cv_folds}-fold TimeSeriesSplit",
        f"Selected model : {selected_name}",
        "",
        *metric_lines("CANDIDATE HOLDOUT METRICS", candidate_metrics),
        "",
        *metric_lines("SAME-DATA BASELINE METRICS", baseline_metrics),
        "",
        "Next step: run evaluate_candidate.py. This script never deploys a model.",
        f"Evidence saved to: {output_dir}",
    ]
    console_output = "\n".join(lines)
    print(console_output)
    (output_dir / "training_console_output.txt").write_text(console_output + "\n")


if __name__ == "__main__":
    main()
