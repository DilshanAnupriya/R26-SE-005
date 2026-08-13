"""Maps SprintIQ's sprint and project data onto the two CatBoost risk models.

Both models were trained only on what is knowable at sprint planning or mid-sprint.
The datasets also carry post-hoc columns — ``actual_hours``,
``effort_deviation_pct``, ``requirement_volatility_score`` — that are only known
once the sprint closes and that *define* the labels. Nothing here may send those,
and nothing here computes an approximation of them either: a feature that leaks
the answer produces a model that scores beautifully and forecasts nothing.

Several inputs are things the story-point model already collects per story. The
requirement-change model wants the average story length, the average acceptance
criteria per story, and how many stories have none — all of which SprintIQ derives
from the same story text M1 reads. Deriving them once, in the backend, and sending
them to both models is the point: two models disagreeing because they were shown
different versions of the same story is a bug nobody would ever find.
"""

from __future__ import annotations

import re
from typing import Any

# Medians from the training CSVs (sprint_metrics_realistic_train_ready.csv and
# req_change_realistic_train_ready.csv). Used when a caller omits a field, so an
# unknown reads as an ordinary sprint rather than an extreme one.
OVERBUDGET_MEDIANS: dict[str, float] = {
    "team_size": 7,
    "base_velocity": 22,
    "complexity": 2.26,
    "hours_per_point": 6.5,
    "sprint_capacity_hours": 201.7,
    "planned_hours": 174.7,
    "committed_story_points": 8.9,
    "velocity_rolling_prev": 8.72,
    "velocity_trend": -0.2,
    "prev_carry_over_points": 1,
    "prev_carry_over_rate": 0.099,
    "prev_rework_score": 5.9,
    "prev_blocked_tasks": 5,
    "prev_reopened_tasks": 5,
    "fatigue": 0.436,
    "scope_added": 3,
}

REQUIREMENT_CHANGE_MEDIANS: dict[str, float] = {
    "team_seniority": 0.604,
    "has_dedicated_BA": 0,
    "requirements_doc_quality": 0.58,
    "n_stakeholders": 5,
    "baseline_story_count": 14,
    "avg_story_length_words": 30.1,
    "avg_acceptance_criteria_per_story": 3.54,
    "ambiguity_marker_count": 5,
    "stories_without_AC": 2,
    "stakeholder_meetings_held": 2,
    "prev_change_requests_count": 8,
    "prev_acceptance_criteria_changes": 5,
    "prev_story_change_ratio": 0.125,
    "prev_high_risk_flag": 0,
    "prev_requirement_volatility_score": 0.24,
}

# The categorical levels each model was trained on. CatBoost handles an unseen
# category without erroring, but it has no learned statistics for it, so an unknown
# is normalised to the closest trained level rather than passed through raw.
TEAM_TYPES = ("stable", "slow_stable", "fast_unstable", "high_rework")
PROJECT_DOMAINS = ("data_platform", "ecommerce", "enterprise_saas", "fintech",
                   "healthcare", "iot", "mobile_app")
PROJECT_PHASES = ("discovery", "growth", "maturity")

# SprintIQ's project domains (the vocabulary the story-point model uses) onto the
# requirement-change model's. Only the ones that genuinely correspond; anything
# else falls through to enterprise_saas, the modal category, and is recorded as an
# assumption rather than presented as this project's domain.
DOMAIN_ALIASES = {
    "finance": "fintech", "fintech": "fintech",
    "ecommerce": "ecommerce", "e-commerce": "ecommerce", "retail": "ecommerce",
    "healthcare": "healthcare", "health": "healthcare",
    "education": "enterprise_saas", "logistics": "enterprise_saas",
    "data_platform": "data_platform", "data": "data_platform",
    "iot": "iot", "mobile_app": "mobile_app", "mobile": "mobile_app",
    "enterprise_saas": "enterprise_saas", "saas": "enterprise_saas",
}

# A project's status is the closest thing SprintIQ records to a delivery phase.
STATUS_TO_PHASE = {
    "PLANNING": "discovery",
    "ACTIVE": "growth",
    "ON_HOLD": "growth",
    "COMPLETED": "maturity",
    "ARCHIVED": "maturity",
}

# The experience bands the project form offers, on the 0..1 scale the model wants.
SENIORITY = {"junior": 0.3, "mid": 0.6, "senior": 0.85}

# The words that make a requirement negotiable. Counted over the same story text
# the story-point model reads, so "ambiguous" means one thing across both models.
AMBIGUITY_MARKERS = re.compile(
    r"\b(should|maybe|might|possibly|tbd|tbc|approximately|roughly|etc|"
    r"as needed|if required|to be (decided|confirmed|determined))\b|\?", re.I)


def count_ambiguity_markers(text: str | None) -> int:
    """How many hedges a story's text contains.

    Exposed rather than inlined because the backend counts these over the whole
    sprint's stories and this is the definition both sides must agree on.
    """
    return len(AMBIGUITY_MARKERS.findall(text or ""))


def _normalise(value: Any, known: tuple[str, ...], aliases: dict[str, str] | None = None) -> str | None:
    if value is None:
        return None
    key = str(value).strip().lower().replace(" ", "_").replace("-", "_")
    if aliases and key in aliases:
        return aliases[key]
    return key if key in known else None


def team_type_for(payload: dict[str, Any]) -> tuple[str, bool]:
    """Which of the four team archetypes this sprint's history looks like.

    SprintIQ has no such field, so it is inferred from behaviour the app does
    record: how much gets reopened, and how steady the velocity has been. Returns
    the archetype and whether it had to be guessed, because "high_rework" is a
    strong signal to the model and presenting an inference as an observation is
    how a forecast becomes a fabrication.
    """
    supplied = _normalise(payload.get("team_type"), TEAM_TYPES)
    if supplied:
        return supplied, False

    rework = float(payload.get("prev_rework_score") or 0)
    trend = abs(float(payload.get("velocity_trend") or 0))
    baseline = max(float(payload.get("velocity_rolling_prev") or 0), 1.0)

    if rework >= 8:
        return "high_rework", True
    # A swing of more than a quarter of the team's own baseline is instability,
    # not noise — and the same absolute swing means different things to a team
    # delivering 8 points and one delivering 40.
    if trend > 0.25 * baseline:
        return "fast_unstable", True
    if baseline < OVERBUDGET_MEDIANS["velocity_rolling_prev"]:
        return "slow_stable", True
    return "stable", True


def build_overbudget_row(payload: dict[str, Any], features: list[str]) -> tuple[dict[str, Any], dict]:
    """Builds the over-budget model's record, plus what had to be assumed."""
    row: dict[str, Any] = {}
    defaulted: list[str] = []

    archetype, guessed = team_type_for(payload)
    row["team_type"] = archetype

    for name in features:
        if name == "team_type":
            continue
        value = payload.get(name)
        if value is None or value == "":
            row[name] = OVERBUDGET_MEDIANS.get(name)
            defaulted.append(name)
        else:
            try:
                row[name] = float(value)
            except (TypeError, ValueError):
                row[name] = OVERBUDGET_MEDIANS.get(name)
                defaulted.append(name)

    provenance = {
        "defaulted_to_training_median": defaulted,
        "team_type": archetype,
        "team_type_inferred": guessed,
    }
    return row, provenance


def build_requirement_change_row(payload: dict[str, Any],
                                 features: list[str]) -> tuple[dict[str, Any], dict]:
    """Builds the requirement-change model's record, plus what had to be assumed."""
    row: dict[str, Any] = {}
    defaulted: list[str] = []
    assumed: list[str] = []

    domain = _normalise(payload.get("project_domain") or payload.get("domain"),
                        PROJECT_DOMAINS, DOMAIN_ALIASES)
    if domain is None:
        domain = "enterprise_saas"
        assumed.append("project_domain")
    row["project_domain"] = domain

    phase = _normalise(payload.get("project_phase"), PROJECT_PHASES)
    if phase is None:
        phase = STATUS_TO_PHASE.get(str(payload.get("project_status") or "").upper())
        if phase is not None:
            assumed.append("project_phase")
    if phase is None:
        phase = "growth"
        assumed.append("project_phase")
    row["project_phase"] = phase

    # Seniority as a band, when that is what the project recorded.
    seniority = payload.get("team_seniority")
    if seniority is None:
        band = str(payload.get("dev_experience_level") or "").strip().lower()
        if band in SENIORITY:
            seniority = SENIORITY[band]
            assumed.append("team_seniority")

    for name in features:
        if name in ("project_domain", "project_phase"):
            continue
        value = seniority if name == "team_seniority" else payload.get(name)
        if value is None or value == "":
            row[name] = REQUIREMENT_CHANGE_MEDIANS.get(name)
            defaulted.append(name)
        else:
            try:
                row[name] = float(value)
            except (TypeError, ValueError):
                row[name] = REQUIREMENT_CHANGE_MEDIANS.get(name)
                defaulted.append(name)

    provenance = {
        "defaulted_to_training_median": defaulted,
        "assumed_from_related_field": assumed,
        "project_domain": domain,
        "project_phase": phase,
    }
    return row, provenance
