#!/usr/bin/env python3
"""Evaluate sprint-effort models against transparent planning baselines."""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict
from pathlib import Path

import joblib
import numpy as np
import pandas as pd

from train_cost_models import (
    DEFAULT_DATA_PATH,
    DEFAULT_OUTPUT_DIR,
    IDENTIFIER_COLUMNS,
    STAGE_CONFIG,
    StageFeatureEngineer,
    build_stage_target,
    clean_dataset,
    compute_metrics,
    load_dataset,
    prepare_stage_dataset,
    split_dataset,
)


# Support joblib bundles saved when the training script ran as __main__.
sys.modules["__main__"].StageFeatureEngineer = StageFeatureEngineer


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Evaluate saved effort models on an untouched holdout and compare a baseline."
    )
    parser.add_argument("--data-path", type=Path, default=DEFAULT_DATA_PATH)
    parser.add_argument("--artifacts-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument(
        "--report-dir", type=Path, default=DEFAULT_OUTPUT_DIR / "test_reports"
    )
    parser.add_argument("--stage", choices=["initial", "mid", "both"], default="both")
    parser.add_argument("--split-mode", choices=["time", "random"], default="time")
    parser.add_argument("--test-size", type=float, default=0.2)
    parser.add_argument("--random-state", type=int, default=42)
    parser.add_argument("--sample-size", type=int, default=25)
    return parser.parse_args()


def baseline_for(stage: str, X_test: pd.DataFrame) -> np.ndarray:
    if stage == "initial":
        return X_test["planned_effort_minutes"].to_numpy(dtype=float)
    return np.maximum(
        X_test["planned_effort_minutes"].to_numpy(dtype=float)
        - X_test["total_effort_minutes"].to_numpy(dtype=float),
        0.0,
    )


def evaluate_stage(
    source_df: pd.DataFrame,
    artifacts_dir: Path,
    report_dir: Path,
    stage: str,
    split_mode: str,
    test_size: float,
    random_state: int,
    sample_size: int,
) -> dict[str, object]:
    stage_dir = artifacts_dir / f"{stage}_model"
    integrated_stage_dir = artifacts_dir / f"SprintCost-{stage}_model"
    if not stage_dir.exists() and integrated_stage_dir.exists():
        stage_dir = integrated_stage_dir
    model_path = stage_dir / "model.joblib"
    metrics_path = stage_dir / "metrics.json"
    if not model_path.exists():
        raise FileNotFoundError(f"Saved model not found: {model_path}")

    staged_df = prepare_stage_dataset(source_df, stage, random_state)
    X_train, X_test, split_frame, split_index = split_dataset(
        df=staged_df,
        stage=stage,
        split_mode=split_mode,
        test_size=test_size,
        random_state=random_state,
    )
    y_train, y_test, target_info = build_stage_target(
        split_frame=split_frame,
        stage=stage,
        split_index=split_index,
    )
    del X_train, y_train

    model = joblib.load(model_path)
    model_predictions = np.maximum(model.predict(X_test), 0.0)
    baseline_predictions = baseline_for(stage, X_test)
    model_metrics = compute_metrics(y_test, model_predictions)
    baseline_metrics = compute_metrics(y_test, baseline_predictions)
    saved_metrics = json.loads(metrics_path.read_text()) if metrics_path.exists() else {}
    deployment_method = saved_metrics.get(
        "deployment_method",
        "ml_remaining_effort" if model_metrics.mape < baseline_metrics.mape else "planning_baseline",
    )

    sample_frame = split_frame.loc[
        X_test.index, IDENTIFIER_COLUMNS + STAGE_CONFIG[stage]["raw_features"]
    ].copy()
    sample_frame = sample_frame.loc[:, ~sample_frame.columns.duplicated()].copy()
    sample_frame["target_name"] = target_info.target_name
    sample_frame["actual_effort_minutes"] = y_test.values
    sample_frame["model_effort_minutes"] = model_predictions
    sample_frame["baseline_effort_minutes"] = baseline_predictions
    sample_frame["model_absolute_error_minutes"] = np.abs(y_test.values - model_predictions)
    sample_frame["baseline_absolute_error_minutes"] = np.abs(
        y_test.values - baseline_predictions
    )
    sample_frame = sample_frame.sort_values("sprint_number").head(sample_size).reset_index(drop=True)

    report_dir.mkdir(parents=True, exist_ok=True)
    sample_path = report_dir / f"{stage}_holdout_sample.csv"
    sample_frame.to_csv(sample_path, index=False)

    return {
        "stage": stage,
        "model_path": str(model_path),
        "split_mode": split_mode,
        "holdout_rows": int(len(X_test)),
        "target_name": target_info.target_name,
        "target_definition": target_info.target_definition,
        "model_metrics": asdict(model_metrics),
        "baseline_metrics": asdict(baseline_metrics),
        "model_beats_baseline_mape": bool(model_metrics.mape < baseline_metrics.mape),
        "deployment_method": deployment_method,
        "holdout_sample_path": str(sample_path),
        "cost_conversion_note": (
            "Production converts effort to cost with the project's actual blended hourly rate. "
            "The training CSV does not provide a trustworthy independent rate column, so this "
            "report evaluates effort rather than manufacturing a cost target."
        ),
        "snapshot_limitation": (
            "The mid-sprint holdout uses generated partial snapshots because the source dataset "
            "contains completed-sprint aggregates rather than timestamped observations."
            if stage == "mid"
            else None
        ),
    }


def main() -> None:
    args = parse_args()
    raw_df = load_dataset(args.data_path)
    df, cleaning_report = clean_dataset(raw_df)
    stages = ["initial", "mid"] if args.stage == "both" else [args.stage]

    reports = [
        evaluate_stage(
            source_df=df,
            artifacts_dir=args.artifacts_dir,
            report_dir=args.report_dir,
            stage=stage,
            split_mode=args.split_mode,
            test_size=args.test_size,
            random_state=args.random_state,
            sample_size=args.sample_size,
        )
        for stage in stages
    ]

    for report in reports:
        model = report["model_metrics"]
        baseline = report["baseline_metrics"]
        print(
            f"[{report['stage']}] target={report['target_name']} "
            f"model_MAPE={model['mape']:.2f}% baseline_MAPE={baseline['mape']:.2f}% "
            f"model_R^2={model['r2']:.4f} baseline_R^2={baseline['r2']:.4f} "
            f"deploy={report['deployment_method']}"
        )

    summary = {
        "data_path": str(args.data_path),
        "artifacts_dir": str(args.artifacts_dir),
        "split_mode": args.split_mode,
        "test_size": args.test_size,
        "random_state": args.random_state,
        "cleaning_report": cleaning_report,
        "stage_reports": reports,
    }
    args.report_dir.mkdir(parents=True, exist_ok=True)
    summary_path = args.report_dir / "holdout_evaluation.json"
    summary_path.write_text(json.dumps(summary, indent=2))
    print(f"Saved evaluation summary to: {summary_path.resolve()}")


if __name__ == "__main__":
    try:
        main()
    except (FileNotFoundError, ValueError) as exc:
        raise SystemExit(f"Error: {exc}") from exc
    except KeyboardInterrupt:
        raise SystemExit("\nCancelled by user.") from None
