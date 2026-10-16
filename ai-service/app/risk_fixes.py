"""Fixes for the over-budget and requirement-change models, read off their own SHAP values.

The driver list already says, per reading, how far it pushed this sprint's risk — the
SHAP contribution from the binary head, in log-odds. This module answers the question
that list leaves open: what would have to change for that push to go away.

Nothing in here is advice written by hand. Each fix is a measurement:

* **A reading the team can change this sprint** (for over-budget: the commitment, the
  planned hours, how big the stories are, scope added, blocked work; for requirement
  change: missing acceptance criteria, hedging words, story length, how many stories)
  is walked along a ladder of values in the direction that helps, and the fix is the
  first one at which the model's SHAP value for that reading stops being positive — the
  point where it no longer raises the risk at all.

* **A reading the team cannot change** (last sprint's rework or churn, the project's
  domain and phase, the team's size and history) has no lever of its own. Since SHAP
  values add up to the model's log-odds, a reading adding +0.9 is cancelled by any move
  that takes 0.9 off the log-odds — so the fix is the smallest move of a controllable
  reading that does, or the best partial one when nothing within reach does.

Every candidate is re-scored by the model itself, so the probability quoted beside a
fix is the model's figure for the sprint with that one change made, not an estimate.
Which readings count as controllable is the only judgement here, and it is a fact
about how the backend builds each column, not an opinion about what a team should do.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import numpy as np
import pandas as pd

# Below this a SHAP value is noise — the same floor the driver list uses.
SHAP_FLOOR = 0.01


@dataclass(frozen=True)
class Lever:
    """A reading the team can move this sprint, and how far it can plausibly go."""

    feature: str
    # Lowering: the lowest fraction of today's value a fix may propose. Cutting a
    # commitment by half is a re-plan; cutting it to nothing is cancelling the sprint.
    # Raising: the highest multiple of today's value.
    bound: float
    # Counts move in whole steps; "1.6 blocked tasks" is not a target anyone can hit.
    whole: bool
    # True where more is safer — acceptance criteria per story, say.
    upward: bool = False
    # Raising only: the least the ladder reaches, so a reading at nought has somewhere
    # to go (a multiple of zero is zero).
    reach: float = 0.0


# Controllable this sprint, given how RiskService builds each column. Everything else is
# history or a fact about the team or project, and is offset rather than moved.
LEVERS_BY_KIND: dict[str, dict[str, Lever]] = {
    "over_budget": {
        # The sprint's own commitment; planned_hours follows it.
        "committed_story_points": Lever("committed_story_points", 0.5, True),
        # Committed points / story count, so splitting stories lowers it.
        "complexity": Lever("complexity", 0.4, False),
        # Points added after the sprint started.
        "scope_added": Lever("scope_added", 0.0, True),
        # The backend feeds the sprint's live blocked count into it.
        "prev_blocked_tasks": Lever("prev_blocked_tasks", 0.0, True),
    },
    "requirement_change": {
        # All four are read off the committed stories' own text, which the team edits.
        "stories_without_AC": Lever("stories_without_AC", 0.0, True),
        "ambiguity_marker_count": Lever("ambiguity_marker_count", 0.0, True),
        "avg_story_length_words": Lever("avg_story_length_words", 0.6, False),
        "avg_acceptance_criteria_per_story": Lever("avg_acceptance_criteria_per_story", 2.0,
                                                   False, upward=True, reach=4.0),
        # Moving stories out of the sprint.
        "baseline_story_count": Lever("baseline_story_count", 0.7, True),
    },
}

# A reading that is moved by moving another one, per model.
#   planned_hours is committed points times hours per point in the backend, so the only
#   honest way to lower it is to commit less.
#   requirements_doc_quality is 1 - stories_without_AC / story count in the backend, so it
#   rises exactly as criteria are written for the stories that have none.
MOVED_BY_BY_KIND: dict[str, dict[str, str]] = {
    "over_budget": {"planned_hours": "committed_story_points"},
    "requirement_change": {"requirements_doc_quality": "stories_without_AC"},
}

RUNGS = 12


def _ladder(lever: Lever, current: float) -> list[float]:
    """Candidate values from just past today's to the lever's bound, in the helpful direction."""
    if current is None or not np.isfinite(current):
        return []
    if lever.upward:
        top = max(current * lever.bound, lever.reach)
        if top <= current:
            return []
        values = np.linspace(current, top, RUNGS + 1)[1:]
        if lever.whole:
            return [float(v) for v in sorted({int(np.ceil(v)) for v in values
                                              if np.ceil(v) > current})]
        return [round(float(v), 2) for v in values if round(float(v), 2) > current]

    if current <= 0:
        return []
    floor = current * lever.bound
    values = np.linspace(current, floor, RUNGS + 1)[1:]
    if lever.whole:
        stepped = sorted({int(np.floor(v)) for v in values if np.floor(v) < current},
                         reverse=True)
        return [float(v) for v in stepped]
    return [round(float(v), 2) for v in values if round(float(v), 2) < current]


def _apply(row: dict[str, Any], lever: str, value: float) -> dict[str, Any]:
    """The row with one lever moved, and anything derived from it moved with it."""
    moved = dict(row)
    moved[lever] = value
    current = row.get(lever)
    if (lever == "committed_story_points" and isinstance(row.get("planned_hours"), (int, float))
            and isinstance(current, (int, float)) and current > 0):
        # Scaled in proportion rather than recomputed from hours per point: a payload
        # whose two figures disagree slightly would otherwise credit the cut with the
        # disagreement, and the fix would claim a fall the commitment did not cause.
        moved["planned_hours"] = round(float(row["planned_hours"]) * value / float(current), 2)
    if (lever == "stories_without_AC" and isinstance(row.get("requirements_doc_quality"), (int, float))
            and isinstance(row.get("baseline_story_count"), (int, float))
            and row["baseline_story_count"] > 0):
        # The backend's own formula, so the two readings move together as they would on
        # the board once the criteria were written.
        moved["requirements_doc_quality"] = round(
            1 - min(value, row["baseline_story_count"]) / float(row["baseline_story_count"]), 3)
    return moved


def _shown(value: Any) -> str:
    if isinstance(value, float):
        return f"{value:.2f}".rstrip("0").rstrip(".") if not value.is_integer() else f"{value:g}"
    return f"{value}"


def _pct(probability: float) -> int:
    return int(round(probability * 100))


def _verb(levers: dict[str, Lever], feature: str) -> str:
    lever = levers.get(feature)
    return "raise" if lever is not None and lever.upward else "lower"


def shap_fixes(model, features: list[str], cat_features: list[str], labels: dict[str, str],
               row: dict[str, Any], drivers: list[dict[str, Any]],
               defaulted: list[str], kind: str = "over_budget") -> dict[str, dict[str, Any]]:
    """One fix per driver raising the risk, keyed by the driver's feature name.

    ``model`` is the binary head the drivers were computed from, so the SHAP values a
    fix is measured against are the same ones the driver list shows.
    """
    from catboost import Pool

    levers = LEVERS_BY_KIND.get(kind, {})
    moved_by = MOVED_BY_BY_KIND.get(kind, {})

    raising = [d for d in drivers if d["direction"] == "INCREASE"
               and d["contribution"] > SHAP_FLOOR]
    if not raising:
        return {}

    # Every candidate row in one frame: one SHAP call and one predict for the lot, so
    # the cost is the same whether a sprint has two raising readings or twelve.
    candidates: list[tuple[str, float, dict[str, Any]]] = [("", float("nan"), row)]
    for lever in levers.values():
        if lever.feature in defaulted or lever.feature not in features:
            continue
        current = row.get(lever.feature)
        if not isinstance(current, (int, float)):
            continue
        for value in _ladder(lever, float(current)):
            candidates.append((lever.feature, value, _apply(row, lever.feature, value)))

    frame = pd.DataFrame([c[2] for c in candidates])[features]
    for column in cat_features:
        frame[column] = frame[column].astype(str)
    shap = model.get_feature_importance(Pool(frame, cat_features=cat_features),
                                        type="ShapValues")[:, :-1]
    raw = np.asarray(model.predict(frame, prediction_type="RawFormulaVal")).reshape(-1)
    probability = np.asarray(model.predict_proba(frame))[:, 1]

    index = {name: i for i, name in enumerate(features)}
    p_now, raw_now = float(probability[0]), float(raw[0])

    def rungs_of(lever: str) -> list[int]:
        return [i for i, c in enumerate(candidates) if c[0] == lever]

    fixes: dict[str, dict[str, Any]] = {}
    for driver in raising:
        feature, push = driver["feature"], float(driver["contribution"])
        label = labels.get(feature, feature.replace("_", " "))
        base = {"method": "shap", "feature": feature, "shap_now": round(push, 3),
                "probability_now": round(p_now, 4)}

        if feature in defaulted:
            fixes[feature] = {**base, "kind": "unsupplied", "move": None,
                              "summary": (f"SHAP: {label} adds +{push:.2f}, but this reading "
                                          f"was not supplied and was filled with a typical "
                                          f"value — record the real figure before acting "
                                          f"on it.")}
            continue

        lever = moved_by.get(feature, feature)
        if lever in levers and rungs_of(lever):
            fixes[feature] = _direct(base, feature, label, lever, labels, row, candidates,
                                     rungs_of(lever), shap[:, index[feature]], probability,
                                     _verb(levers, lever))
        else:
            fixes[feature] = _offset(base, label, push, raw_now, labels, row, candidates,
                                     raw, probability, levers)
    return fixes


def _direct(base, feature, label, lever, labels, row, candidates, rungs, feature_shap,
            probability, verb) -> dict[str, Any]:
    """Move the reading itself until SHAP says it has stopped raising the risk."""
    # Only rungs at which the model's own probability falls. A model is not monotonic,
    # and a move that silences this reading's push while another reading's grows by
    # more would be quoted as a fix that raises the risk.
    helpful = [i for i in rungs if probability[i] < probability[0]]
    if not helpful:
        return {**base, "kind": "none", "move": None,
                "summary": (f"SHAP: {label} adds +{base['shap_now']:.2f}, but every change to "
                            f"it within a sprint's reach raises the model's risk elsewhere by "
                            f"as much, so the model offers no fix for it.")}
    hit = next((i for i in helpful if feature_shap[i] <= 0), None)
    complete = hit is not None
    # Not reachable within the lever's bound: the rung that pushed least is still worth
    # stating, with how much of the push it leaves.
    chosen = hit if complete else min(helpful, key=lambda i: feature_shap[i])
    after = float(feature_shap[chosen])
    if after >= base["shap_now"] - SHAP_FLOOR:
        return {**base, "kind": "none", "move": None,
                "summary": (f"SHAP: {label} adds +{base['shap_now']:.2f}, and changing it as "
                            f"far as a sprint plausibly can does not reduce that.")}

    lever_label = labels.get(lever, lever.replace("_", " "))
    target = candidates[chosen][1]
    p_after = float(probability[chosen])
    move = {"feature": lever, "label": lever_label, "from": row.get(lever), "to": target}
    via = (f"{verb} {lever_label} from {_shown(row.get(lever))} to {_shown(target)}"
           if lever == feature
           else f"{verb} {lever_label} from {_shown(row.get(lever))} to {_shown(target)}, "
                f"which brings {label} to {_shown(candidates[chosen][2].get(feature))}")
    outcome = ("it stops raising the risk" if complete
               else f"its push falls to +{after:.2f}")
    return {**base, "kind": "direct", "complete": complete, "move": move,
            "shap_after": round(after, 3), "cancels": round(min(1.0, (base['shap_now'] - after) / base['shap_now']), 3),
            "probability_after": round(p_after, 4),
            "summary": (f"SHAP: {label} adds +{base['shap_now']:.2f}. To fix it, {via} — "
                        f"{outcome}, and the model's risk goes from {_pct(base['probability_now'])}% "
                        f"to {_pct(p_after)}%.")}


def _offset(base, label, push, raw_now, labels, row, candidates, raw,
            probability, levers) -> dict[str, Any]:
    """Cancel a reading nobody can move with the smallest move of one that can be.

    SHAP values sum to the log-odds, so taking ``push`` off the log-odds is exactly
    what this reading added. The smallest move — measured as a share of the lever's
    current value — that does so wins; failing that, the move that removes the most.
    """
    best_full: tuple[float, int] | None = None
    best_partial: tuple[float, int] | None = None
    for i, (lever, value, _) in enumerate(candidates):
        if not lever:
            continue
        removed = raw_now - float(raw[i])
        if removed <= SHAP_FLOOR:
            continue
        current = float(row[lever])
        # A share of the larger of the two ends, so a lever raised from nought is
        # compared on the same 0-1 scale as one cut from forty.
        span = max(abs(current), abs(value))
        size = abs(current - value) / span if span else 1.0
        if removed >= push:
            if best_full is None or size < best_full[0]:
                best_full = (size, i)
        elif best_partial is None or removed > best_partial[0]:
            best_partial = (removed, i)

    choice = best_full[1] if best_full else (best_partial[1] if best_partial else None)
    if choice is None:
        return {**base, "kind": "none", "move": None,
                "summary": (f"SHAP: {label} adds +{push:.2f} and cannot be changed this "
                            f"sprint, and no change to the readings a team controls lowers "
                            f"the model's risk enough to offset it.")}

    lever, target, _ = candidates[choice]
    lever_label = labels.get(lever, lever.replace("_", " "))
    removed = raw_now - float(raw[choice])
    share = min(1.0, removed / push)
    p_after = float(probability[choice])
    move = {"feature": lever, "label": lever_label, "from": row.get(lever), "to": target}
    reach = ("cancels it" if best_full
             else f"cancels {int(round(share * 100))}% of it — the most any single change within reach does")
    return {**base, "kind": "offset", "complete": bool(best_full), "move": move,
            "removed": round(removed, 3), "cancels": round(share, 3),
            "probability_after": round(p_after, 4),
            "summary": (f"SHAP: {label} adds +{push:.2f} and cannot be changed this sprint. "
                        f"{'Raising' if _verb(levers, lever) == 'raise' else 'Lowering'} {lever_label} from "
                        f"{_shown(row.get(lever))} to {_shown(target)} takes {removed:.2f} off "
                        f"the model's log-odds, which {reach}; the model's risk goes from "
                        f"{_pct(base['probability_now'])}% to {_pct(p_after)}%.")}
