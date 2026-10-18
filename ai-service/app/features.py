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

import math
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

# The largest size signals the models were trained on. Components come from the
# interval heads' own record of their training data (LightGBM feature_infos, 1-7);
# external APIs from the point model's, whose top split sits just above 6 (the heads
# saw at most 3, so 6 is the most either model has seen). Past these the trees cannot
# tell a bigger story from the biggest one they knew, and XGBoost sends an external-API
# count above 6 down its "missing" branch — which, for a sparse row, is the branch for
# zero. The predictor scores such a story at these maximums and says it is beyond them.
# tests/test_edge_cases_story_points.py checks these against the bundle.
TRAINING_MAXIMUMS: dict[str, float] = {
    "num_components": 7,
    "external_apis": 6,
}

# The other end of the same records: the smallest values the training data held. The
# predictor reads the model's per-unit trend between these and the maximums.
TRAINING_MINIMUMS: dict[str, float] = {
    "num_components": 1,
    "external_apis": 0,
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

# SprintIQ's STORY and TASK have no counterpart in the dataset, which splits user-facing
# work into "feature" (a new capability) and "enhancement" (a change to an existing one).
# Mapping every Story to "feature" put a tooltip on an existing button in the population
# of new capabilities, and the model — which puts about two points between the two at
# the same structure — sized it 8. A Story is now read as an enhancement when everything
# the service can measure says it is a small change: no more components than the
# dataset's median story, no external dependency, no security work, and a short write-up.
# Anything bigger stays a feature, so large stories size exactly as before.
AMBIGUOUS_STORY_TYPES = frozenset({"STORY", "TASK"})
SMALL_CHANGE_MAX_COMPONENTS = TRAINING_MEDIANS["num_components"]
# About twice the longest text in the training data (19 words). The model's length
# features stop there, so a story written up at more length than this carries scope
# the model cannot read, and is not called small on the strength of its structure.
SMALL_CHANGE_MAX_WORDS = 40


def _reads_as_small_change(row: dict[str, float], words: int, defaulted: list[str]) -> bool:
    """Whether an ambiguous Story looks like the dataset's enhancements rather than its
    features. Unknown size is not small: a Story whose components were not supplied
    keeps the old reading."""
    return ("num_components" not in defaulted
            and row["num_components"] <= SMALL_CHANGE_MAX_COMPONENTS
            and row["external_apis"] == 0
            and row["has_security"] == 0
            and words <= SMALL_CHANGE_MAX_WORDS)

# Signals the app cannot know without asking, inferred from the story text when
# it has not been supplied. Deliberately conservative: these are keyword probes,
# not understanding, and the response says when a value was inferred this way.
# No bare "import"/"export", matching the backend's list: in a sentence they are usually
# the system's own feature (an Export button, a CSV export), not another system.
_INTEGRATION_HINTS = re.compile(
    r"\b(integrat\w*|webhook|sync|third[- ]party|api|sso|oauth)\b", re.I)
_SECURITY_HINTS = re.compile(
    r"\b(auth\w*|security|encrypt\w*|permission|rbac|gdpr|pci|compliance|audit|token)\b", re.I)
_UI_HINTS = re.compile(
    r"\b(ui|ux|screen|page|form|dashboard|layout|responsive|modal|chart|design)\b", re.I)


# XGBoost reads every input as float32, so a value past float32's range is infinity
# by the time it is scored — and XGBoost refuses infinity when it builds the DMatrix
# the driver list is computed from. It takes no effort to send one: JSON's 1e999
# parses to inf in Python.
FLOAT32_MAX = float(np.finfo(np.float32).max)

# No count or rate here can be negative, and a team of nobody or a sprint of no days
# is not a project at all. A value like that is a data-entry slip, and scoring it
# would price the story for a team that cannot exist.
_MUST_BE_POSITIVE = frozenset({"team_size", "sprint_duration_days"})

_YES = frozenset({"true", "yes", "y"})
_NO = frozenset({"false", "no", "n"})


def finite_number(value: Any) -> float | None:
    """The value as a float the trees can score, or None when there is none.

    NaN and infinity are None too. NaN reaches XGBoost as "missing" and is never
    recorded as such; infinity is scored as an extreme the caller never meant, and
    the explanation silently loses its drivers.
    """
    if value is None:
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if not math.isfinite(number) or abs(number) > FLOAT32_MAX:
        return None
    return number


def _number(source: dict[str, Any], key: str) -> tuple[float, bool]:
    """Returns the value and whether it fell back to the training median.

    Unreadable and impossible values fall back the same way an omitted one does,
    and are reported the same way, rather than being scored as though they were
    a measurement.
    """
    number = finite_number(source.get(key))
    if number is None or number < 0 or (number == 0 and key in _MUST_BE_POSITIVE):
        return float(TRAINING_MEDIANS[key]), True
    return number, False


def _flag(source: dict[str, Any], key: str, text: str, probe: re.Pattern[str]) -> tuple[float, bool]:
    """Returns the flag and whether it had to be guessed from the text.

    A value that cannot be read as yes or no is guessed from the text, as an
    omitted one is. Reading it as "no" was a guess too, only an unrecorded one —
    and NaN, being truthy, was read as "yes".
    """
    value = source.get(key)
    if isinstance(value, bool):
        return float(value), False
    if isinstance(value, str) and value.strip().lower() in _YES | _NO:
        return (1.0 if value.strip().lower() in _YES else 0.0), False
    number = finite_number(value)
    if number is None:
        return (1.0 if probe.search(text) else 0.0), True
    return (1.0 if number else 0.0), False


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
    # Whitespace collapsed to single spaces. char_count is a length feature, and it
    # counted formatting: the same story with its criteria on separate lines, or
    # pasted with tabs, came back with a different range (3-8 became 5-13).
    text = " ".join(" ".join(
        str(payload.get(key) or "")
        for key in ("title", "description", "acceptance_criteria")
    ).split())

    inferred: list[str] = []
    defaulted: list[str] = []
    # Supplied, but not usable as given. Kept apart from "not supplied" because the
    # fix is different: this one is a bad value upstream, not a missing field.
    rejected: list[str] = []

    # Length features are computed here rather than trusted from the caller, so
    # they always describe the text the model actually scored.
    words = len(text.split()) if text else 0
    row: dict[str, float] = {column: 0.0 for column in struct_columns}
    row["word_count"] = float(words)
    row["char_count"] = float(len(text))

    for key in ("num_components", "external_apis", "team_size", "sprint_duration_days",
                "similar_task_count", "team_velocity_avg", "recent_completion_rate",
                "task_age_days"):
        row[key], fell_back = _number(payload, key)
        if fell_back:
            defaulted.append(key)
            if payload.get(key) not in (None, ""):
                rejected.append(key)

    for key, probe in (("has_integration", _INTEGRATION_HINTS),
                       ("has_security", _SECURITY_HINTS),
                       ("has_ui_complexity", _UI_HINTS)):
        row[key], guessed = _flag(payload, key, text, probe)
        if guessed:
            inferred.append(key)
            if payload.get(key) not in (None, ""):
                rejected.append(key)

    story_type = str(payload.get("story_type") or "").upper()
    mapped = STORY_TYPE_TO_TASK_TYPE.get(story_type, payload.get("task_type"))
    read_as = None
    if story_type in AMBIGUOUS_STORY_TYPES:
        read_as = "enhancement" if _reads_as_small_change(row, words, defaulted) else "feature"
        mapped = read_as
    task_type = _category(mapped, KNOWN_TASK_TYPES)
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
        "rejected_as_invalid": rejected,
        "text_length_words": words,
    }
    if read_as:
        provenance["story_type_read_as"] = read_as
    return text, struct_row, provenance


def to_matrix(text: str, struct_row: np.ndarray, tfidf) -> csr_matrix:
    """TF-IDF first, then structural — the order the model was fitted with."""
    return hstack([tfidf.transform([text]), csr_matrix(struct_row)]).tocsr()
