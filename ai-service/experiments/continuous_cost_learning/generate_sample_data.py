#!/usr/bin/env python3
"""Generate deterministic, clearly labelled demonstration sprint snapshots."""

from __future__ import annotations

import argparse
import random
from datetime import UTC, datetime, timedelta
from pathlib import Path

from common import blank_snapshot, write_snapshots


ROOT = Path(__file__).resolve().parent


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "data" / "sample_sprint_snapshots.csv")
    parser.add_argument("--sprints", type=int, default=120)
    parser.add_argument("--seed", type=int, default=42)
    return parser.parse_args()


def make_row(
    sprint_id: str,
    team_id: str,
    stage: str,
    snapshot_at: datetime,
    currency: str = "USD",
    **values: float,
) -> dict[str, object]:
    row = blank_snapshot()
    row.update(
        {
            "sprint_id": sprint_id,
            "team_id": team_id,
            "snapshot_stage": stage,
            "snapshot_at": snapshot_at.isoformat(),
            "data_source": "simulated_demo",
            "currency": currency,
        }
    )
    row.update(values)
    return row


def main() -> None:
    args = parse_args()
    if args.sprints < 30:
        raise ValueError("Generate at least 30 demo sprints for a meaningful holdout demonstration.")

    rng = random.Random(args.seed)
    start = datetime(2025, 1, 6, 9, tzinfo=UTC)
    rows: list[dict[str, object]] = []

    for index in range(args.sprints):
        sprint_id = f"DEMO-SPRINT-{index + 1:03d}"
        team_id = f"TEAM-{(index % 8) + 1}"
        initial_at = start + timedelta(days=index * 14)
        duration_days = 14

        team_size = rng.randint(3, 8)
        planned_points = rng.randint(18, 75)
        planned_effort = planned_points * rng.uniform(65, 125)
        velocity = planned_points * rng.uniform(0.78, 1.12)
        availability = rng.uniform(0.72, 1.0)
        absence_days = rng.uniform(0, 4.5)
        concurrent = rng.uniform(1.0, 3.2)
        skill_match = rng.uniform(0.62, 1.0)
        seniority = rng.uniform(1.3, 3.8)
        hourly_rate = rng.uniform(48, 115)

        effort_multiplier = (
            0.76
            + 0.55 * (1 - availability)
            + 0.045 * absence_days
            + 0.10 * (concurrent - 1)
            + 0.38 * (1 - skill_match)
            - 0.035 * (seniority - 2.2)
        )
        final_effort = max(240.0, planned_effort * effort_multiplier * rng.uniform(0.95, 1.05))

        elapsed = rng.uniform(0.38, 0.64)
        burn_fraction = min(0.90, max(0.12, elapsed * rng.uniform(0.78, 1.16)))
        completion_fraction = min(
            0.92,
            max(0.05, elapsed * (0.72 + 0.45 * skill_match) * rng.uniform(0.88, 1.08)),
        )
        logged_effort = final_effort * burn_fraction
        completed_points = planned_points * completion_fraction
        overtime_hours = max(0.0, (effort_multiplier - 0.95) * team_size * rng.uniform(4, 9))
        regular_hours = max(final_effort / 60 - overtime_hours, 0.0)
        final_cost = regular_hours * hourly_rate + overtime_hours * hourly_rate * 1.5

        rows.append(
            make_row(
                sprint_id,
                team_id,
                "initial",
                initial_at,
                planned_effort_minutes=round(planned_effort, 2),
                planned_story_points=planned_points,
                team_size=team_size,
                historical_velocity_avg=round(velocity, 2),
                developer_availability_rate=round(availability, 4),
                absence_days_total=round(absence_days, 2),
                concurrent_assignments=round(concurrent, 2),
                skill_match_ratio=round(skill_match, 4),
                avg_seniority_level=round(seniority, 2),
                blended_hourly_rate=round(hourly_rate, 2),
            )
        )
        rows.append(
            make_row(
                sprint_id,
                team_id,
                "mid",
                initial_at + timedelta(days=duration_days * elapsed),
                elapsed_fraction=round(elapsed, 4),
                completed_story_points=round(completed_points, 2),
                logged_effort_minutes=round(logged_effort, 2),
                overtime_hours_total=round(overtime_hours * burn_fraction, 2),
            )
        )
        rows.append(
            make_row(
                sprint_id,
                team_id,
                "final",
                initial_at + timedelta(days=duration_days),
                final_effort_minutes=round(final_effort, 2),
                final_cost=round(final_cost, 2),
            )
        )

    output = args.output.expanduser().resolve()
    write_snapshots(output, rows, replace=True)
    print(f"Generated {args.sprints} simulated demo sprints ({len(rows)} snapshots) at {output}")
    print("Every record is labelled data_source=simulated_demo and is not production evidence.")


if __name__ == "__main__":
    main()
