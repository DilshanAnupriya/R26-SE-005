"""The story-point model: point estimate, calibrated interval, and drivers.

Two models, not one. XGBoost gives the point estimate; a pair of LightGBM
quantile heads (P20/P80) give the interval, widened by a conformal correction
measured on a held-out split. That correction is why the interval means what it
says — raw quantile heads covered 70% of the time when they claimed 80%.

Everything is snapped to the Fibonacci scale a planning session actually uses.
Snapping is to the *nearest* value, not outward: the notebook records that
rounding the bounds outward turned a raw [4.65, 9.02] into a displayed [3, 13],
four steps wide, and flagged it HIGH risk when the honest answer was [5, 8].
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any

import joblib
import numpy as np

from .features import build_row, to_matrix

log = logging.getLogger(__name__)

# Readable names for the structural columns, used in the driver list. The model
# thinks in column names; a planner reading "why 8?" should not have to.
DRIVER_LABELS = {
    "num_components": "components touched",
    "external_apis": "external APIs",
    "has_integration": "integration work",
    "has_security": "security or compliance",
    "has_ui_complexity": "UI complexity",
    "word_count": "description length",
    "char_count": "description length",
    "team_size": "team size",
    "sprint_duration_days": "sprint length",
    "similar_task_count": "comparable past work",
    "team_velocity_avg": "team velocity",
    "recent_completion_rate": "recent completion rate",
    "task_age_days": "how long it has sat in the backlog",
}


# Columns that are one-hot flags rather than measurements. A contribution from one
# of these is only meaningful when the flag is actually set: SHAP will happily report
# what "this is not a bug" did to the estimate, but showing a planner "task type: bug"
# as a driver of a story that is not a bug is worse than showing nothing.
ONE_HOT_PREFIXES = (("task_type_", "task type"), ("domain_", "domain"),
                    ("tech_stack_", "tech stack"),
                    ("dev_experience_level_", "team experience"))


def _is_one_hot(column: str) -> bool:
    return any(column.startswith(prefix) for prefix, _ in ONE_HOT_PREFIXES)


def _label_for(column: str, value: float | None = None) -> str:
    """A driver's name, carrying the value it had.

    "components touched" does not tell a planner whether the story touched two or
    twelve, which is the whole reason it moved the estimate. The number goes in the
    label because the alternative is an explanation that cannot be checked.
    """
    for prefix, noun in ONE_HOT_PREFIXES:
        if column.startswith(prefix):
            return f"{noun}: {column[len(prefix):]}"
    label = DRIVER_LABELS.get(column, column.replace("_", " "))
    if value is None:
        return label
    return f"{label} ({value:g})"


class StoryPointPredictor:
    """Loads the bundle once and scores one story at a time."""

    def __init__(self, bundle_path: Path):
        bundle = joblib.load(bundle_path)
        self.model = bundle["model"]
        self.quantiles = bundle["quantile_models"]
        self.tfidf = bundle["tfidf"]
        self.struct_columns: list[str] = bundle["struct_columns"]
        self.fib: list[int] = [int(v) for v in bundle["fib"]]
        self.cqr_delta: float = float(bundle["cqr_delta"])
        self.risk_thresholds: dict[str, int] = bundle["risk_thresholds"]
        self.metrics: dict[str, Any] = bundle.get("metrics", {})
        self.coverage: float = float(bundle.get("production_coverage", 0.8))

        self._fib_index = {value: i for i, value in enumerate(self.fib)}
        # 150 TF-IDF + 34 structural. Asserted at load rather than at first
        # request, so a swapped bundle fails on startup instead of at 3am.
        expected = len(self.tfidf.vocabulary_) + len(self.struct_columns)
        actual = int(self.model.n_features_in_)
        if expected != actual:
            raise ValueError(
                f"Bundle is inconsistent: TF-IDF({len(self.tfidf.vocabulary_)}) + "
                f"struct({len(self.struct_columns)}) = {expected}, but the model "
                f"expects {actual} columns")
        log.info("Loaded story-point model: %d columns, MAE %.2f on held-out test",
                 actual, self.metrics.get("xgb_test", {}).get("mae", float("nan")))

    def _snap(self, value: float) -> int:
        """Nearest Fibonacci value. Never outward — see the module docstring."""
        return min(self.fib, key=lambda point: abs(point - value))

    def _risk(self, span: int) -> str:
        if span <= self.risk_thresholds["low_max_steps"]:
            return "LOW"
        if span <= self.risk_thresholds["medium_max_steps"]:
            return "MEDIUM"
        return "HIGH"

    def _drivers(self, matrix, struct_values, limit: int = 5) -> list[dict[str, Any]]:
        """Per-prediction contributions from the trees, not global importances.

        ``pred_contribs`` is XGBoost's exact SHAP for tree ensembles: it says what
        moved *this* estimate. Global feature importance would answer a different
        question — what matters on average — and presenting that as "why 8?" is
        the kind of explanation that is worse than none.

        Takes the structural values alongside the contributions so a driver can say
        what it actually was. Two things depend on that: the label carries the value,
        and one-hot columns the story does not have are dropped rather than reported
        as reasons. A bug fix was previously explained as "task type: feature ↓",
        which is SHAP faithfully describing the absence of a category and the UI
        presenting it as a property of the story.
        """
        try:
            import xgboost as xgb

            booster = self.model.get_booster()
            contributions = booster.predict(xgb.DMatrix(matrix), pred_contribs=True)[0]
        except Exception as error:  # noqa: BLE001 — never fail a prediction for its narration
            log.warning("Could not compute drivers: %s", error)
            return []

        # Layout mirrors the input: TF-IDF columns, then structural, then the bias
        # term XGBoost appends last.
        text_width = len(self.tfidf.vocabulary_)
        structural = contributions[text_width:text_width + len(self.struct_columns)]
        text_total = float(np.sum(contributions[:text_width]))

        ranked = sorted(
            ((self.struct_columns[i], float(contribution), float(struct_values[i]))
             for i, contribution in enumerate(structural)),
            key=lambda row: abs(row[1]),
            reverse=True,
        )

        drivers: list[dict[str, Any]] = []
        seen: set[str] = set()
        for column, contribution, value in ranked:
            if len(drivers) >= limit or abs(contribution) <= 0.01:
                break
            # A category the story does not belong to is not a reason for its size.
            if _is_one_hot(column) and value == 0:
                continue
            label = _label_for(column, None if _is_one_hot(column) else value)
            # word_count and char_count are both "description length"; listing it
            # twice reads as two independent reasons when it is one.
            if label.split(" (")[0] in seen:
                continue
            seen.add(label.split(" (")[0])
            drivers.append({
                "feature": label,
                "contribution": round(contribution, 3),
                # INCREASE / DECREASE, matching PredictionDriver in the backend. The UI
                # compares against those exact strings, so lowercase prose here made
                # every driver render as a decrease — a silent mismatch, since nothing
                # validates the vocabulary on the way through.
                "direction": "INCREASE" if contribution > 0 else "DECREASE",
            })
        # The wording matters: the text moved the estimate, but which words did it
        # is not something a TF-IDF contribution sum can honestly attribute.
        if abs(text_total) > 0.05:
            drivers.append({
                "feature": "wording of the story",
                "contribution": round(text_total, 3),
                "direction": "INCREASE" if text_total > 0 else "DECREASE",
            })
        return drivers

    def predict(self, payload: dict[str, Any]) -> dict[str, Any]:
        text, struct_row, provenance = build_row(payload, self.struct_columns)
        matrix = to_matrix(text, struct_row, self.tfidf)

        # How much of the story's wording the model can actually read. The vocabulary
        # is 150 terms fixed at training time, so a story phrased in words it never
        # saw ("SCIM user provisioning") produces 150 zero columns and contributes
        # nothing. Two such stories are then indistinguishable to the model and get
        # the same number — which is how "every story is a 5" happens with nothing
        # visibly broken. Counted, so the caller can say so instead of implying the
        # estimate was read off this particular story.
        text_width = len(self.tfidf.vocabulary_)
        provenance["text_terms_recognised"] = int(matrix[:, :text_width].nnz)

        raw_point = float(self.model.predict(matrix)[0])
        raw_low = float(self.quantiles["P20"].predict(matrix)[0]) - self.cqr_delta
        raw_high = float(self.quantiles["P80"].predict(matrix)[0]) + self.cqr_delta

        point = self._snap(raw_point)
        low = min(self._snap(raw_low), point)
        high = max(self._snap(raw_high), point)
        span = self._fib_index[high] - self._fib_index[low]
        risk = self._risk(span)

        drivers = self._drivers(matrix, struct_row[0])

        return {
            "value": point,
            "lower_bound": low,
            "upper_bound": high,
            "confidence": self._confidence(raw_point, raw_low, raw_high, provenance),
            "label": risk,
            "drivers": drivers,
            "explanation": self._explain(point, low, high, risk, drivers, provenance),
            "raw": {"point": round(raw_point, 2),
                    "lower": round(raw_low, 2),
                    "upper": round(raw_high, 2)},
            "interval_steps": span,
            "provenance": provenance,
        }

    # A raw interval this wide relative to the estimate counts as no precision at
    # all. Set from the model's own held-out spread (mean raw width 4.72 on a median
    # of 5, so a relative width of 1.5 is already well past typical).
    WIDEST_USEFUL_RELATIVE_WIDTH = 1.5

    def _confidence(self, raw_point: float, raw_low: float, raw_high: float,
                    provenance: dict) -> float:
        """How precise this estimate is, from the interval before it was snapped.

        Not a probability. The interval carries the calibrated probability — it
        covers the true value about 70% of the time. This is the narrower question a
        planner asks: how precise is this one?

        Measured on the *raw* interval deliberately. Deriving it from the snapped
        Fibonacci span, as this did, quantised a continuous quantity into six
        possible values — and since the conformal correction is tuned to a mean of
        about two Fibonacci steps, essentially every story landed on exactly two and
        reported 60%. A confidence that reads 60% for a one-line typo fix and for a
        multi-region migration is not telling anyone anything.
        """
        width = max(0.0, raw_high - raw_low)
        relative = width / max(abs(raw_point), 1.0)
        confidence = 1.0 - relative / self.WIDEST_USEFUL_RELATIVE_WIDTH

        # An interval computed mostly from training medians is a statement about the
        # training set, not about this story, and it can look deceptively tight. Each
        # missing input costs a little certainty; unreadable wording costs more,
        # because the text is the only per-story signal the structure cannot supply.
        confidence -= 0.03 * len(provenance.get("defaulted_to_training_median", []))
        if provenance.get("text_terms_recognised") == 0:
            confidence -= 0.15

        return round(max(0.0, min(1.0, confidence)), 2)

    def _explain(self, point: int, low: int, high: int, risk: str,
                 drivers: list[dict[str, Any]], provenance: dict) -> str:
        coverage = int(round(self.coverage * 100))
        sentence = (f"Estimated {point} points, most likely between {low} and {high} "
                    f"({coverage}% of the time). ")

        # Both directions, not just increases. A small story is small *for reasons*,
        # and explaining only what pushed an estimate up left every below-average
        # story with no explanation at all — the case where a planner is most likely
        # to want to argue with the number.
        if drivers:
            up = [d for d in drivers if d["direction"] == "INCREASE"][:2]
            down = [d for d in drivers if d["direction"] == "DECREASE"][:2]
            if up:
                sentence += "Pushed up by " + " and ".join(d["feature"] for d in up) + ". "
            if down and not up:
                sentence += "Held down by " + " and ".join(d["feature"] for d in down) + ". "
            elif down and up:
                sentence += "Held down by " + down[0]["feature"] + ". "

        # Said before anything else about the range, because it changes what the
        # number means. With no recognised wording and the structural inputs left at
        # their defaults, this is the model's baseline for a story it cannot read —
        # not an assessment of this story. Every such story gets the same answer, so
        # a planner comparing two of them is comparing nothing.
        unread = provenance.get("text_terms_recognised", 1) == 0
        if unread and len(provenance["defaulted_to_training_median"]) >= 4:
            return (sentence + "None of this story's wording matches the vocabulary the model "
                    f"was trained on, and {len(provenance['defaulted_to_training_median'])} inputs "
                    "were left at their defaults, so this is the model's baseline rather than a "
                    "reading of this story. Size it yourself, or fill in the project's domain, "
                    "tech stack and team details to give the model something to work from.")
        if unread:
            sentence += ("The model did not recognise any of this story's wording, so the "
                         "estimate rests on its structure alone. ")

        if risk == "HIGH":
            sentence += ("The range is wide, so treat this as a rough size and split the "
                         "story before committing to it.")
        elif provenance["defaulted_to_training_median"]:
            missing = len(provenance["defaulted_to_training_median"])
            plural = missing != 1
            sentence += (f"{missing} input{'s' if plural else ''} {'were' if plural else 'was'} "
                         f"not supplied and fell back to typical values, so the estimate is less "
                         f"specific to this team than it could be.")
        elif high >= low * 2:
            # LOW here is relative to what this model usually produces, not an
            # absolute claim. Calling a 5-to-13 range "tight" would be a plain
            # overstatement — it is nearly a threefold spread.
            sentence += ("That is still a wide spread in absolute terms; it is narrow only "
                         "compared with this model's usual range.")
        else:
            sentence += "The range is tight enough to plan against."
        return sentence.strip()
