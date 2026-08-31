#!/usr/bin/env python3
"""Apply a transparent manual-review gate to an offline candidate evaluation."""

from __future__ import annotations

import argparse
import json
from datetime import UTC, datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parent


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evaluation", type=Path, required=True)
    parser.add_argument("--registry", type=Path, default=ROOT / "model_registry.json")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    evaluation_path = args.evaluation.expanduser().resolve()
    registry_path = args.registry.expanduser().resolve()
    evaluation = json.loads(evaluation_path.read_text())
    registry = json.loads(registry_path.read_text())
    policy = registry["promotion_policy"]
    candidate = evaluation["candidate_holdout_metrics"]
    baseline = evaluation["baseline_holdout_metrics"]
    sources = set(evaluation["data_sources"])

    checks = {
        "enough_completed_sprints": evaluation["completed_sprints"]
        >= policy["min_completed_sprints"],
        "enough_holdout_sprints": evaluation["holdout_sprints"] >= policy["min_holdout_sprints"],
        "candidate_mape_below_limit": candidate["mape"] <= policy["max_mape"],
        "candidate_r2_above_limit": candidate["r2"] >= policy["min_r2"],
        "candidate_beats_baseline_mape": candidate["mape"] < baseline["mape"],
        "candidate_beats_baseline_rmse": candidate["rmse"] < baseline["rmse"],
        "real_data_only": sources == {"real"},
    }
    technical_checks = {key: value for key, value in checks.items() if key != "real_data_only"}
    technical_pass = all(technical_checks.values())

    if technical_pass and checks["real_data_only"]:
        status = "APPROVED_FOR_MANUAL_REVIEW"
        reason = "All technical and real-data checks passed; a human may review deployment."
    elif technical_pass:
        status = "DEMO_ONLY_NOT_DEPLOYABLE"
        reason = "Technical checks passed, but simulated data can never approve production deployment."
    else:
        status = "REJECTED"
        reason = "One or more technical promotion checks failed."

    decision = {
        "evaluated_at_utc": datetime.now(UTC).isoformat(),
        "stage": evaluation["stage"],
        "status": status,
        "reason": reason,
        "checks": checks,
        "candidate_metrics": candidate,
        "baseline_metrics": baseline,
        "automatic_deployment_performed": False,
        "production_model_modified": False,
    }
    output = evaluation_path.parent / "promotion_decision.json"
    output.write_text(json.dumps(decision, indent=2))

    print(f"Candidate stage : {evaluation['stage']}")
    print(f"Decision        : {status}")
    for check, passed in checks.items():
        print(f"  {'PASS' if passed else 'FAIL'}  {check}")
    print(f"Reason          : {reason}")
    print("Production model: unchanged")
    print(f"Decision saved  : {output}")
    return 0 if status != "REJECTED" else 2


if __name__ == "__main__":
    raise SystemExit(main())
