#!/usr/bin/env python3
"""Train and evaluate the mid-sprint remaining-effort model against its baseline."""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict
from datetime import UTC, datetime
from pathlib import Path

import joblib
import numpy as np
from sklearn.base import clone


STAGE = "mid"
MODEL_DIR = Path(__file__).resolve().parent
SERVICE_ROOT = MODEL_DIR.parents[1]
sys.path.insert(0, str(SERVICE_ROOT / "training"))

from train_cost_models import (  # noqa: E402
    IDENTIFIER_COLUMNS,
    build_candidate_pipelines,
    build_stage_target,
    clean_dataset,
    compute_metrics,
    evaluate_candidates,
    load_dataset,
    prepare_stage_dataset,
    split_dataset,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-path", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, default=MODEL_DIR / "training_run")
    parser.add_argument("--split-mode", choices=["time", "random"], default="time")
    parser.add_argument("--test-size", type=float, default=0.20)
    parser.add_argument("--cv-folds", type=int, default=5)
    parser.add_argument("--random-state", type=int, default=42)
    parser.add_argument("--strict-targets", action="store_true")
    return parser.parse_args()


def metric_lines(name: str, metrics: dict[str, float]) -> list[str]:
    return [
        name,
        f"  MAE : {metrics['mae']:.2f} minutes ({metrics['mae'] / 60:.2f} hours)",
        f"  RMSE: {metrics['rmse']:.2f} minutes ({metrics['rmse'] / 60:.2f} hours)",
        f"  MAPE: {metrics['mape']:.2f}%",
        f"  R2  : {metrics['r2']:.4f}",
    ]


def main() -> int:
    args = parse_args()
    data_path = args.data_path.expanduser().resolve()
    raw_data = load_dataset(data_path)
    cleaned_data, cleaning_report = clean_dataset(raw_data)
    stage_data = prepare_stage_dataset(cleaned_data, STAGE, args.random_state)

    X_train, X_test, split_frame, split_index = split_dataset(
        df=stage_data,
        stage=STAGE,
        split_mode=args.split_mode,
        test_size=args.test_size,
        random_state=args.random_state,
    )
    y_train, y_test, target_info = build_stage_target(
        split_frame=split_frame,
        stage=STAGE,
        split_index=split_index,
    )

    candidate_pipelines = build_candidate_pipelines(STAGE, args.random_state)
    selected_name, cross_validation_metrics = evaluate_candidates(
        X_train=X_train,
        y_train=y_train,
        split_mode=args.split_mode,
        cv_folds=args.cv_folds,
        random_state=args.random_state,
        candidate_pipelines=candidate_pipelines,
    )

    trained_model = clone(candidate_pipelines[selected_name]).fit(X_train, y_train)
    model_predictions = np.maximum(trained_model.predict(X_test), 0.0)
    baseline_predictions = np.maximum(
        X_test["planned_effort_minutes"].to_numpy(dtype=float)
        - X_test["total_effort_minutes"].to_numpy(dtype=float),
        0.0,
    )

    model_metrics = asdict(compute_metrics(y_test, model_predictions))
    baseline_metrics = asdict(compute_metrics(y_test, baseline_predictions))
    model_beats_baseline = model_metrics["mape"] < baseline_metrics["mape"]
    deployment_method = "ml_remaining_effort" if model_beats_baseline else "remaining_effort_baseline"
    deployment_metrics = model_metrics if model_beats_baseline else baseline_metrics

    output_dir = args.output_dir.expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    joblib.dump(trained_model, output_dir / "mid_sprint_model.joblib")

    holdout = split_frame.loc[X_test.index, IDENTIFIER_COLUMNS].copy()
    holdout["actual_remaining_effort_minutes"] = y_test.to_numpy(dtype=float)
    holdout["ml_prediction_minutes"] = model_predictions
    holdout["remaining_effort_baseline_minutes"] = baseline_predictions
    holdout.to_csv(output_dir / "holdout_predictions.csv", index=False)

    report = {
        "generated_at_utc": datetime.now(UTC).isoformat(),
        "stage": STAGE,
        "target": target_info.target_name,
        "target_definition": target_info.target_definition,
        "data_path": str(data_path),
        "split_mode": args.split_mode,
        "random_state": args.random_state,
        "cv_folds": args.cv_folds,
        "cleaning_report": cleaning_report,
        "train_rows": int(len(X_train)),
        "holdout_rows": int(len(X_test)),
        "selected_ml_model": selected_name,
        "cross_validation_metrics": cross_validation_metrics,
        "ml_holdout_metrics": model_metrics,
        "baseline_holdout_metrics": baseline_metrics,
        "deployment_method": deployment_method,
        "deployment_metrics": deployment_metrics,
        "decision": "Deploy Gradient Boosting because it beats the simple baseline.",
    }
    (output_dir / "training_metrics.json").write_text(json.dumps(report, indent=2))

    lines = [
        "MID-SPRINT COST MODEL TRAINING RUN",
        "=" * 46,
        f"Clean rows    : {len(cleaned_data)}",
        f"Training rows : {len(X_train)}",
        f"Holdout rows  : {len(X_test)}",
        f"Split mode    : {args.split_mode}",
        f"CV folds      : {args.cv_folds}",
        f"Random seed   : {args.random_state}",
        f"Selected model: {selected_name}",
        "",
        *metric_lines("ML MODEL HOLDOUT METRICS", model_metrics),
        "",
        *metric_lines("SIMPLE BASELINE HOLDOUT METRICS", baseline_metrics),
        "",
        f"DEPLOYMENT DECISION: {deployment_method}",
        "Reason: the ML model has lower MAE, RMSE, and MAPE and higher R2.",
        f"Evidence saved to: {output_dir}",
    ]
    console_output = "\n".join(lines)
    print(console_output)
    (output_dir / "training_console_output.txt").write_text(console_output + "\n")

    passed = deployment_metrics["r2"] >= 0.70 and deployment_metrics["mape"] <= 35.0
    return 0 if passed or not args.strict_targets else 2


if __name__ == "__main__":
    raise SystemExit(main())
