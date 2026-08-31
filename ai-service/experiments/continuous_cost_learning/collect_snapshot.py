#!/usr/bin/env python3
"""Append one validated sprint snapshot to an offline CSV store."""

from __future__ import annotations

import argparse
import json
from datetime import UTC, datetime
from pathlib import Path

from common import (
    NUMERIC_FIELDS,
    REQUIRED_STAGE_VALUES,
    blank_snapshot,
    load_snapshots,
    write_snapshots,
)


ROOT = Path(__file__).resolve().parent


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--store", type=Path, default=ROOT / "data" / "real_sprint_snapshots.csv")
    parser.add_argument("--sprint-id", required=True)
    parser.add_argument("--team-id", required=True)
    parser.add_argument("--stage", choices=["initial", "mid", "final"], required=True)
    parser.add_argument("--snapshot-at", default=datetime.now(UTC).isoformat())
    parser.add_argument("--data-source", choices=["real", "simulated_demo"], default="real")
    parser.add_argument("--currency", default="USD")
    parser.add_argument(
        "--values-json",
        required=True,
        help="JSON object containing the numeric values required by the selected stage.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    values = json.loads(args.values_json)
    if not isinstance(values, dict):
        raise ValueError("--values-json must contain a JSON object.")

    unknown = sorted(set(values) - set(NUMERIC_FIELDS))
    if unknown:
        raise ValueError("Unknown numeric fields: " + ", ".join(unknown))
    missing = [field for field in REQUIRED_STAGE_VALUES[args.stage] if field not in values]
    if missing:
        raise ValueError(f"{args.stage} snapshot is missing: " + ", ".join(missing))

    snapshot_at = datetime.fromisoformat(args.snapshot_at.replace("Z", "+00:00"))
    if snapshot_at.tzinfo is None:
        snapshot_at = snapshot_at.replace(tzinfo=UTC)

    row = blank_snapshot()
    row.update(
        {
            "sprint_id": args.sprint_id,
            "team_id": args.team_id,
            "snapshot_stage": args.stage,
            "snapshot_at": snapshot_at.astimezone(UTC).isoformat(),
            "data_source": args.data_source,
            "currency": args.currency.upper(),
        }
    )
    for field, value in values.items():
        row[field] = float(value)

    store = args.store.expanduser().resolve()
    if store.exists():
        existing = load_snapshots(store)
        duplicate = existing.loc[
            (existing["sprint_id"] == args.sprint_id)
            & (existing["snapshot_stage"] == args.stage)
            & (existing["snapshot_at"] == snapshot_at.astimezone(UTC))
        ]
        if not duplicate.empty:
            raise ValueError("An identical sprint/stage/timestamp snapshot already exists.")

    write_snapshots(store, [row])
    print(f"Saved {args.stage} snapshot for {args.sprint_id} to {store}")
    print("This offline prototype does not update the SprintIQ production database or models.")


if __name__ == "__main__":
    main()
