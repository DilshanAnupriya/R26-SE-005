"""Fixes for the collaboration model, read off its exact SHAP values.

The collaboration score is labelled by a composite that is a plain sum of five
readings' points (see :mod:`collaboration_features`). For a sum, each reading's SHAP
value — against the all-healthy sprint, where every reading scores nought — is simply
its own points: nothing is attributed or approximated. The driver list reports exactly
that, as a share of the composite's ceiling.

So a fix here is the same measurement the CatBoost models' fixes are, made exactly:

* **A reading the team can change this sprint** — the reply time, how much is written
  on tasks, how many tasks are blocked — is moved to the nearest value at which it
  scores no points, which is where its SHAP value reaches nought.

* **A reading the team cannot change** — days that already passed in silence, reopens
  already logged — is history. Its points are cancelled by the smallest move of a
  controllable reading that takes at least as many points off the composite, or the
  best partial one when nothing within reach does.

Every candidate is then re-scored by the model actually serving — DistilBERT when its
weights are loaded, the composite otherwise — so the probability quoted beside a fix is
the model's own figure for the sprint with that one change made. A move the model does
not reward is not offered, whatever the composite says.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable

from .collaboration_features import (COLLABORATION_LABELS, MAX_COMPOSITE,
                                     PER_FEATURE_SCORERS)


@dataclass(frozen=True)
class Lever:
    feature: str
    # The values at which this reading scores fewer points, nearest first. The last is
    # where it scores none.
    steps: tuple[int, ...]
    upward: bool = False
    unit: str = ""


# Controllable this sprint, given how RiskService builds each reading: replies can be
# written, discussion moved onto the task, blocked tasks unblocked. Silent days already
# passed and reopens already logged are history, and are offset rather than moved.
LEVERS: dict[str, Lever] = {
    "avg_response_time": Lever("avg_response_time", (48, 24, 12), unit="h"),
    "comments_per_task": Lever("comments_per_task", (1, 2, 3), upward=True),
    "blockers": Lever("blockers", (5, 3, 1)),
}


def _points(feature: str, value: int) -> int:
    return PER_FEATURE_SCORERS[feature](value)


def _shown(lever: Lever | None, value: Any) -> str:
    return f"{value}{lever.unit if lever else ''}"


def _pct(probability: float) -> int:
    return int(round(probability * 100))


def _rungs(lever: Lever, current: int) -> list[int]:
    """The step values that are a move in the helpful direction from today's."""
    if lever.upward:
        return [v for v in lever.steps if v > current]
    return [v for v in lever.steps if v < current]


def collaboration_fixes(row: dict[str, int], drivers: list[dict[str, Any]],
                        defaulted: list[str],
                        probability_of: Callable[[dict[str, int]], float]
                        ) -> dict[str, dict[str, Any]]:
    """One fix per driver raising the risk, keyed by the driver's feature name.

    ``probability_of`` scores a row with the model serving this prediction, so the
    figure beside each fix is that model's, not the composite's.
    """
    raising = [d for d in drivers if d["direction"] == "INCREASE"]
    if not raising:
        return {}

    p_now = probability_of(row)
    # Every candidate move, re-scored once: (lever, value, points removed, probability).
    candidates: list[tuple[str, int, int, float]] = []
    for lever in LEVERS.values():
        if lever.feature in defaulted:
            continue
        current = row[lever.feature]
        for value in _rungs(lever, current):
            moved = {**row, lever.feature: value}
            removed = _points(lever.feature, current) - _points(lever.feature, value)
            if removed > 0:
                candidates.append((lever.feature, value, removed, probability_of(moved)))

    fixes: dict[str, dict[str, Any]] = {}
    for driver in raising:
        feature = driver["feature"]
        label = COLLABORATION_LABELS.get(feature, feature.replace("_", " "))
        points = _points(feature, row[feature])
        push = points / MAX_COMPOSITE
        base = {"method": "shap", "feature": feature, "shap_now": round(push, 3),
                "probability_now": round(p_now, 4)}
        said = (f"SHAP: {label} adds +{push:.2f} ({points} of the composite's "
                f"{MAX_COMPOSITE} points)")

        if feature in defaulted:
            fixes[feature] = {**base, "kind": "unsupplied", "move": None,
                              "summary": (f"{said}, but this reading was not recorded and "
                                          f"was read as nought — record the real figure "
                                          f"before acting on it.")}
        elif feature in LEVERS:
            fixes[feature] = _direct(base, said, feature, row, candidates, p_now)
        else:
            fixes[feature] = _offset(base, said, points, row, candidates, p_now)
    return fixes


def _move(lever_name: str, row: dict[str, int], value: int) -> dict[str, Any]:
    return {"feature": lever_name, "label": COLLABORATION_LABELS[lever_name],
            "from": row[lever_name], "to": value}


def _direct(base, said, feature, row, candidates, p_now) -> dict[str, Any]:
    """Move the reading to where its points, and so its SHAP value, reach nought."""
    lever = LEVERS[feature]
    own = [c for c in candidates if c[0] == feature and c[3] < p_now]
    if not own:
        return {**base, "kind": "none", "move": None,
                "summary": (f"{said}, but the model does not score this sprint any lower "
                            f"for moving it, so it offers no fix.")}
    # Nearest value at which it scores nothing; failing that (the model did not reward
    # going all the way), the one that leaves it fewest points.
    zero = [c for c in own if _points(feature, c[1]) == 0]
    chosen = zero[0] if zero else min(own, key=lambda c: _points(feature, c[1]))
    _, value, _, p_after = chosen
    left = _points(feature, value)
    verb = "raise" if lever.upward else "lower"
    outcome = ("it stops raising the risk" if left == 0
               else f"its push falls to +{left / MAX_COMPOSITE:.2f}")
    return {**base, "kind": "direct", "complete": left == 0, "move": _move(feature, row, value),
            "shap_after": round(left / MAX_COMPOSITE, 3),
            "probability_after": round(p_after, 4),
            "summary": (f"{said}. To fix it, {verb} {COLLABORATION_LABELS[feature]} from "
                        f"{_shown(lever, row[feature])} to {_shown(lever, value)} — {outcome}, "
                        f"and the model's risk goes from {_pct(p_now)}% to {_pct(p_after)}%.")}


def _offset(base, said, points, row, candidates, p_now) -> dict[str, Any]:
    """Cancel a reading nobody can move with the smallest move of one that can be."""
    useful = [c for c in candidates if c[3] < p_now]
    full = [c for c in useful if c[2] >= points]

    def size(c: tuple[str, int, int, float]) -> float:
        current = row[c[0]]
        span = max(abs(current), abs(c[1]))
        return abs(current - c[1]) / span if span else 1.0

    if full:
        chosen = min(full, key=size)
    elif useful:
        chosen = max(useful, key=lambda c: (c[2], -c[3]))
    else:
        return {**base, "kind": "none", "move": None,
                "summary": (f"{said} and cannot be changed this sprint, and no change to the "
                            f"readings a team controls lowers the model's risk to offset it.")}

    lever_name, value, removed, p_after = chosen
    lever = LEVERS[lever_name]
    share = min(1.0, removed / points)
    reach = ("cancels it" if full
             else f"cancels {int(round(share * 100))}% of it — the most any single change within reach does")
    verb = "Raising" if lever.upward else "Lowering"
    return {**base, "kind": "offset", "complete": bool(full), "move": _move(lever_name, row, value),
            "removed": round(removed / MAX_COMPOSITE, 3), "cancels": round(share, 3),
            "probability_after": round(p_after, 4),
            "summary": (f"{said} and cannot be changed this sprint. {verb} "
                        f"{COLLABORATION_LABELS[lever_name]} from {_shown(lever, row[lever_name])} "
                        f"to {_shown(lever, value)} takes {removed} point{'s' if removed != 1 else ''} "
                        f"off the composite, which {reach}; the model's risk goes from "
                        f"{_pct(p_now)}% to {_pct(p_after)}%.")}
