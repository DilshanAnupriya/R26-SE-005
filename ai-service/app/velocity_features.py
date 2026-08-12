"""Turns SprintIQ's sprint feature map into the 16 columns the velocity model wants.

Four of those sixteen — ``velocity_delta``, ``effective_capacity``,
``stress_score``, ``velocity_per_dev`` — are not inputs at all. The notebook
derives them from the other twelve before fitting, with exact formulas. They are
recomputed here rather than accepted from the caller, so the row the model scores
is always internally consistent: a caller that sent a stale ``velocity_per_dev``
alongside a fresh ``team_size`` would otherwise get a confident answer to a
question nobody asked.

Everything a caller omits falls back to the training median rather than to zero,
for the same reason it does in the story-point model: zero is a real value here.
``avg_velocity_last3 = 0`` says this team has never delivered anything, and the
model will price the next sprint accordingly.
"""

from __future__ import annotations

from typing import Any

import numpy as np

# Medians from Sprint_Velocity_Dataset_v3.csv (n=3000), the set the bundle was
# fitted on. An omitted field then reads as an ordinary team rather than an
# extreme one.
TRAINING_MEDIANS: dict[str, float] = {
    "avg_velocity_last3": 33.9,
    "avg_velocity_last5": 34.1,
    "team_size": 6,
    "sprint_duration_days": 14,
    "completion_rate": 0.812,
    "carryover_tasks": 6,
    "developer_availability": 0.77,
    "leave_days": 3,
    "planned_story_points": 36,
    "workload_ratio": 1.008,
    "num_bugs": 5,
    "avg_experience_years": 4.4,
}

# The five domains the base model saw. SprintIQ stores these lowercase and
# without punctuation (see the story-point model's KNOWN_DOMAINS), so they are
# normalised on the way in rather than compared literally.
KNOWN_DOMAINS = ("Finance", "E-commerce", "Healthcare", "Education", "Logistics")
_DOMAIN_LOOKUP = {d.lower().replace("-", "").replace(" ", ""): d for d in KNOWN_DOMAINS}

# SprintIQ records experience as a band; the model was trained on years. These are
# the midpoints of the bands the project form offers, not precise claims.
EXPERIENCE_YEARS = {"junior": 1.5, "mid": 4.0, "senior": 7.0}


def normalise_domain(value: Any) -> str | None:
    """The model's spelling of a domain, or None when it never saw one.

    None matters: ``DomainAdapter.predict`` falls back to the base model for an
    unknown domain, which is the correct zero-shot behaviour. Guessing at the
    closest known domain instead would route a healthcare team through a model
    fine-tuned on logistics and never say so.
    """
    if value is None:
        return None
    return _DOMAIN_LOOKUP.get(str(value).strip().lower().replace("-", "").replace(" ", ""))


def _number(source: dict[str, Any], key: str) -> tuple[float, bool]:
    """Returns the value and whether it fell back to the training median."""
    value = source.get(key)
    if value is None or value == "":
        return float(TRAINING_MEDIANS[key]), True
    try:
        return float(value), False
    except (TypeError, ValueError):
        return float(TRAINING_MEDIANS[key]), True


def _mean_of_last(values: Any, count: int) -> float | None:
    """Mean of the last ``count`` completed sprints, or None if there are none.

    Deliberately tolerant of a short history: a team with two sprints behind it
    still has a meaningful three-sprint average, and refusing to compute one until
    the third sprint closes would leave every new project unpredictable for a
    month and a half.
    """
    if not isinstance(values, (list, tuple)) or not values:
        return None
    numbers: list[float] = []
    for value in values:
        try:
            numbers.append(float(value))
        except (TypeError, ValueError):
            continue
    if not numbers:
        return None
    window = numbers[-count:]
    return sum(window) / len(window)


def build_row(payload: dict[str, Any], feature_names: list[str]) -> tuple[np.ndarray, dict]:
    """Builds the model's row, plus a note of what had to be assumed.

    Returns ``(row, provenance)``. The provenance is what stops a number built
    mostly from dataset medians being read as a measurement of this team.
    """
    row: dict[str, float] = {}
    defaulted: list[str] = []
    derived: list[str] = []

    # ---- History ------------------------------------------------------------
    # The strongest two features by a wide margin. SprintIQ keeps the closed
    # sprints' velocities, so these are computed from real history when there is
    # any, and only then fall back.
    history = payload.get("historical_velocities")
    last3 = _mean_of_last(history, 3)
    last5 = _mean_of_last(history, 5)

    if last3 is None:
        last3, was_defaulted = _number(payload, "avg_velocity_last3")
        if was_defaulted:
            defaulted.append("avg_velocity_last3")
    if last5 is None:
        # With fewer than five closed sprints the five-sprint average is the
        # three-sprint one; substituting a dataset median here would invent a
        # trend the team does not have.
        last5 = last3 if history else _number(payload, "avg_velocity_last5")[0]
        if not history:
            defaulted.append("avg_velocity_last5")

    row["avg_velocity_last3"] = last3
    row["avg_velocity_last5"] = last5

    # ---- Plain inputs -------------------------------------------------------
    for key in ("team_size", "sprint_duration_days", "completion_rate", "carryover_tasks",
                "developer_availability", "leave_days", "planned_story_points",
                "num_bugs", "avg_experience_years"):
        value, was_defaulted = _number(payload, key)
        row[key] = value
        if was_defaulted:
            defaulted.append(key)

    # Experience as a band, when that is what the project recorded.
    if "avg_experience_years" in defaulted:
        band = str(payload.get("dev_experience_level") or "").strip().lower()
        if band in EXPERIENCE_YEARS:
            row["avg_experience_years"] = EXPERIENCE_YEARS[band]
            defaulted.remove("avg_experience_years")
            derived.append("avg_experience_years")

    # ---- workload_ratio -----------------------------------------------------
    # An input in the dataset, but it is planned points over capacity, and both of
    # those are known here. Recomputing keeps it consistent with the row it sits
    # in; the dataset clips it to [0.5, 1.5] and so does this.
    capacity = row["team_size"] * row["developer_availability"] * (row["sprint_duration_days"] / 14) * 8
    if capacity > 0:
        row["workload_ratio"] = round(min(1.5, max(0.5, row["planned_story_points"] / capacity)), 3)
        derived.append("workload_ratio")
    else:
        row["workload_ratio"], was_defaulted = _number(payload, "workload_ratio")
        if was_defaulted:
            defaulted.append("workload_ratio")

    # ---- The four engineered columns ---------------------------------------
    # Formulas copied from the notebook's feature-engineering cell. Any drift
    # between these and the training code is silent: the model would still score
    # the row and still return a plausible number.
    row["velocity_delta"] = round(row["avg_velocity_last3"] - row["avg_velocity_last5"], 2)
    row["effective_capacity"] = round(
        row["team_size"] * row["developer_availability"] * (row["sprint_duration_days"] / 14), 3)
    row["stress_score"] = round(
        row["carryover_tasks"] * 0.4 + row["num_bugs"] * 0.3 + row["leave_days"] * 0.3, 3)
    row["velocity_per_dev"] = round(
        row["avg_velocity_last3"] / max(row["team_size"], 1), 3)
    derived.extend(["velocity_delta", "effective_capacity", "stress_score", "velocity_per_dev"])

    matrix = np.array([[row[name] for name in feature_names]], dtype=float)
    provenance = {
        "defaulted_to_training_median": defaulted,
        "derived_by_the_service": derived,
        "sprints_of_history": len(history) if isinstance(history, (list, tuple)) else 0,
    }
    return matrix, provenance
