# Offline Continuous Cost-Learning Prototype

This experiment demonstrates how SprintIQ could learn from newly completed sprints without being
connected to the production backend or replacing a deployed model.

It is **continuous supervised learning**, not reinforcement learning. Initial and mid-sprint
snapshots are input features; the final recorded effort is the label learned after sprint closure.

## Safety Boundary

- No backend, database, API, or frontend integration is used.
- No production model can be overwritten by these scripts.
- Simulated records are permanently labelled `data_source=simulated_demo`.
- A candidate trained on simulated data can only receive `DEMO_ONLY_NOT_DEPLOYABLE`.
- Real-data candidates still require a human deployment review.

## Files

- `collect_snapshot.py`: validates and appends one initial, mid, or final snapshot.
- `generate_sample_data.py`: creates deterministic demonstration data using seed 42.
- `retrain_candidate.py`: trains an initial or mid-sprint candidate using earlier sprints and tests
  it on later unseen sprints.
- `evaluate_candidate.py`: compares candidate and baseline metrics against the promotion policy.
- `model_registry.json`: records the transparent policy and current production reference metrics.
- `common.py`: shared snapshot schema, assembly, and regression metrics.

## Run the Demonstration

From this directory:

```bash
../../.venv/bin/python generate_sample_data.py

../../.venv/bin/python retrain_candidate.py --stage initial
../../.venv/bin/python evaluate_candidate.py \
  --evaluation runs/initial/evaluation.json

../../.venv/bin/python retrain_candidate.py --stage mid
../../.venv/bin/python evaluate_candidate.py \
  --evaluation runs/mid/evaluation.json
```

Each training run displays and saves MAE, RMSE, MAPE, and R2 for both the candidate and a same-data
baseline. Evidence is written to `runs/initial` and `runs/mid`.

## Collect a Real Snapshot

The collector accepts structured JSON values and writes only to an offline CSV. Example initial
snapshot:

```bash
../../.venv/bin/python collect_snapshot.py \
  --store data/real_sprint_snapshots.csv \
  --sprint-id SPRINT-101 --team-id TEAM-A --stage initial \
  --snapshot-at 2026-09-01T09:00:00Z \
  --values-json '{
    "planned_effort_minutes": 4800,
    "planned_story_points": 40,
    "team_size": 5,
    "historical_velocity_avg": 36,
    "developer_availability_rate": 0.92,
    "absence_days_total": 1,
    "concurrent_assignments": 1.4,
    "skill_match_ratio": 0.88,
    "avg_seniority_level": 2.4,
    "blended_hourly_rate": 78
  }'
```

Mid-sprint snapshot:

```bash
../../.venv/bin/python collect_snapshot.py \
  --store data/real_sprint_snapshots.csv \
  --sprint-id SPRINT-101 --team-id TEAM-A --stage mid \
  --snapshot-at 2026-09-08T09:00:00Z \
  --values-json '{
    "elapsed_fraction": 0.5,
    "completed_story_points": 18,
    "logged_effort_minutes": 2520,
    "overtime_hours_total": 3
  }'
```

Final snapshot after closing the sprint:

```bash
../../.venv/bin/python collect_snapshot.py \
  --store data/real_sprint_snapshots.csv \
  --sprint-id SPRINT-101 --team-id TEAM-A --stage final \
  --snapshot-at 2026-09-15T09:00:00Z \
  --values-json '{
    "final_effort_minutes": 5100,
    "final_cost": 6812
  }'
```

At least 30 complete initial-to-final examples are required before the prototype will train. The
real research validation should include more sprints from multiple teams and keep later sprints as
the untouched test set.

## Panel Explanation

> This offline prototype demonstrates the future learning cycle: collect initial and mid-sprint
> conditions, attach the final effort label after closure, train a candidate on earlier sprints,
> evaluate it on later unseen sprints, compare it with a baseline, and require a manual promotion
> decision. The demonstration data is explicitly simulated, so it cannot be presented as real-world
> model validation.
