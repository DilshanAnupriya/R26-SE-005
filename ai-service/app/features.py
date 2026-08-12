"""Turns SprintIQ's feature map into the matrix the model was trained on.

The model takes 184 columns in a fixed order: 150 TF-IDF columns first, then the
34 structural ones. That order comes from the notebook's
``hstack([X_text, X_struct])`` and is not negotiable — feeding the two halves the
other way round produces confident nonsense rather than an error, which is the
worst failure mode available here.

Everything the caller does not supply falls back to the training median rather
than to zero. Zero is a real value in this feature space: ``team_velocity_avg=0``
tells the model the team has never delivered anything, and it will price the
story accordingly. A missing value should mean "typical", not "catastrophic".
"""

from __future__ import annotations

import re
from typing import Any

import numpy as np
from scipy.sparse import csr_matrix, hstack

# Medians from Agile_Estimation_Dataset_Realistic_v2.xlsx (n=3000). Used when the
# caller omits a field, so an unknown reads as an ordinary project rather than an
# extreme one.
TRAINING_MEDIANS: dict[str, float] = {
    "num_components": 3,
    "external_apis": 0,
    "has_integration": 0,
    "has_security": 0,
    "has_ui_complexity": 0,
    "word_count": 8,
    "char_count": 57,
    "team_size": 6,
    "sprint_duration_days": 14,
    "similar_task_count": 10,
    "team_velocity_avg": 22,
    "recent_completion_rate": 0.84,
    "task_age_days": 6,
}

# The categorical levels the model was trained on. Anything outside these lists
# gets no one-hot set at all — which is correct: an unseen tech stack should not
# be silently filed under whichever one happens to sort first.
KNOWN_TASK_TYPES = ("bug", "enhancement", "feature")
KNOWN_DOMAINS = ("ecommerce", "education", "finance", "healthcare", "logistics")
KNOWN_TECH_STACKS = (
    "angular", "django", "dotnet", "fastapi", "flask",
    "nodejs", "rails", "react", "springboot", "vue",
)
KNOWN_EXPERIENCE = ("junior", "mid", "senior")

# SprintIQ's story types do not match the dataset's three. Mapping rather than
# passing through, because an unmapped value silently becomes "no task type set"
# and the model loses one of its stronger signals.
STORY_TYPE_TO_TASK_TYPE = {
    "BUG": "bug",
    "STORY": "feature",
    "TASK": "feature",
    "SPIKE": "enhancement",
    "CHORE": "enhancement",
    "TECH_DEBT": "enhancement",
    "ENHANCEMENT": "enhancement",
}

# Signals the app cannot know without asking, inferred from the story text when
# it has not been supplied. Deliberately conservative: these are keyword probes,
# not understanding, and the response says when a value was inferred this way.
_INTEGRATION_HINTS = re.compile(
    r"\b(integrat\w*|webhook|sync|import|export|third[- ]party|api|sso|oauth)\b", re.I)
_SECURITY_HINTS = re.compile(
    r"\b(auth\w*|security|encrypt\w*|permission|rbac|gdpr|pci|compliance|audit|token)\b", re.I)
_UI_HINTS = re.compile(
    r"\b(ui|ux|screen|page|form|dashboard|layout|responsive|modal|chart|design)\b", re.I)


def _number(source: dict[str, Any], key: str) -> float:
    value = source.get(key)
    if value is None or value == "":
        return float(TRAINING_MEDIANS[key])
    try:
        return float(value)
    except (TypeError, ValueError):
        return float(TRAINING_MEDIANS[key])


def _flag(source: dict[str, Any], key: str, text: str, probe: re.Pattern[str]) -> tuple[float, bool]:
    """Returns the flag and whether it had to be guessed from the text."""
    value = source.get(key)
    if value is None or value == "":
        return (1.0 if probe.search(text) else 0.0), True
    if isinstance(value, bool):
        return float(value), False
    try:
        return (1.0 if float(value) else 0.0), False
    except (TypeError, ValueError):
        return 0.0, False


def _category(value: Any, known: tuple[str, ...]) -> str | None:
    if value is None:
        return None
    normalised = str(value).strip().lower().replace(" ", "").replace("-", "").replace(".", "")
    return normalised if normalised in known else None


def build_row(payload: dict[str, Any], struct_columns: list[str]) -> tuple[str, np.ndarray, dict]:
    """Builds the text and the structural row, plus a note of what was inferred.

    Returns ``(text, struct_row, provenance)`` where provenance records which
    fields fell back to a median or a keyword guess — the caller surfaces that so
    a number built mostly from defaults is not read as a measurement.
    """
    text = " ".join(
        str(payload.get(key) or "").strip()
        for key in ("title", "description", "acceptance_criteria")
    ).strip()

    inferred: list[str] = []
    defaulted: list[str] = []

    # Length features are computed here rather than trusted from the caller, so
    # they always describe the text the model actually scored.
    words = len(text.split()) if text else 0
    row: dict[str, float] = {column: 0.0 for column in struct_columns}
    row["word_count"] = float(words)
    row["char_count"] = float(len(text))

    for key in ("num_components", "external_apis", "team_size", "sprint_duration_days",
                "similar_task_count", "team_velocity_avg", "recent_completion_rate",
                "task_age_days"):
        if payload.get(key) in (None, ""):
            defaulted.append(key)
        row[key] = _number(payload, key)

    for key, probe in (("has_integration", _INTEGRATION_HINTS),
                       ("has_security", _SECURITY_HINTS),
                       ("has_ui_complexity", _UI_HINTS)):
        row[key], guessed = _flag(payload, key, text, probe)
        if guessed:
            inferred.append(key)

    task_type = _category(
        STORY_TYPE_TO_TASK_TYPE.get(str(payload.get("story_type") or "").upper(),
                                    payload.get("task_type")),
        KNOWN_TASK_TYPES)
    domain = _category(payload.get("domain"), KNOWN_DOMAINS)
    tech_stack = _category(payload.get("tech_stack"), KNOWN_TECH_STACKS)
    experience = _category(payload.get("dev_experience_level"), KNOWN_EXPERIENCE)

    for prefix, value in (("task_type", task_type), ("domain", domain),
                          ("tech_stack", tech_stack), ("dev_experience_level", experience)):
        if value is None:
            defaulted.append(prefix)
            continue
        column = f"{prefix}_{value}"
        if column in row:
            row[column] = 1.0

    struct_row = np.array([[row[column] for column in struct_columns]], dtype=float)
    provenance = {
        "inferred_from_text": inferred,
        "defaulted_to_training_median": defaulted,
        "text_length_words": words,
    }
    return text, struct_row, provenance


def to_matrix(text: str, struct_row: np.ndarray, tfidf) -> csr_matrix:
    """TF-IDF first, then structural — the order the model was fitted with."""
    return hstack([tfidf.transform([text]), csr_matrix(struct_row)]).tocsr()
