"""The story-point model: point estimate, calibrated interval, and drivers.

Two models, not one. XGBoost gives the point estimate; a pair of LightGBM
quantile heads (P20/P80) give the interval, widened by a conformal correction
measured on a held-out split. That correction is why the interval means what it
says — raw quantile heads covered 70% of the time when they claimed 80%.

Everything is snapped to the deck a planning session actually uses. Snapping is to
the *nearest* value, not outward: the notebook records that rounding the bounds
outward turned a raw [4.65, 9.02] into a displayed [3, 13], four steps wide, and
flagged it HIGH risk when the honest answer was [5, 8].

The deck is wider than the training data. The dataset's stories run from 1 to 13
points, so inside its range the model answers on 1-13 as it was trained to. The deck
adds the cards either side — 0 and 0.5 below, 20, 40 and 100 above — because a planning
session uses them and a story can be bigger than anything in the dataset. The model
only reaches past 13 for a story whose size signals are past the training data,
and then by an extrapolation it labels as one (see ``_extrapolate``).
"""

from __future__ import annotations

import logging
import math
from pathlib import Path
from typing import Any

import joblib
import numpy as np

from .features import TRAINING_MAXIMUMS, TRAINING_MINIMUMS, build_row, to_matrix

log = logging.getLogger(__name__)

# The Planning Poker deck, less the cards that are not sizes ("?", coffee): the one scale
# SprintIQ uses everywhere, from the picker to the backend's mock to this. The model's own
# training scale, 1 to 13, is the middle of it. 0 is on it for completeness; a model
# answer would have to fall below 0.25 to reach it, which this one does not.
ESTIMATION_SCALE: tuple[float, ...] = (0, 0.5, 1, 2, 3, 5, 8, 13, 20, 40, 100)

# An extrapolated number past the top of the deck says "100 or more" and nothing finer.
# Capped so a story with a million components cannot write a number the backend's
# NUMERIC(6,2) raw-points column refuses.
RAW_CEILING = 1000.0

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
        # The scale the model was trained on (1-13), kept for what it says about the
        # model; estimates are reported on the wider deck.
        self.fib: list[int] = [int(v) for v in bundle["fib"]]
        self.scale: list[float] = list(ESTIMATION_SCALE)
        self.cqr_delta: float = float(bundle["cqr_delta"])
        self.risk_thresholds: dict[str, int] = bundle["risk_thresholds"]
        self.metrics: dict[str, Any] = bundle.get("metrics", {})
        self.coverage: float = float(bundle.get("production_coverage", 0.8))

        # Risk is a span in steps of the deck. The thresholds were set on 1-13, and
        # the wider deck keeps those steps where they were.
        self._fib_index = {value: i for i, value in enumerate(self.scale)}
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

    def _snap(self, value: float) -> float:
        """Nearest card on the deck. Never outward — see the module docstring."""
        return min(self.scale, key=lambda point: abs(point - value))

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

    # A story sized by extrapolation rather than measured by the model is not precise,
    # however its range is drawn. Matches the bar an unreadable story is held to.
    BEYOND_TRAINING_CONFIDENCE_CEILING = 0.3

    def _extrapolate(self, text: str, scored_row: np.ndarray, beyond: dict[str, dict],
                     raw_point: float, raw_low: float, raw_high: float) -> dict[str, float]:
        """An estimate for a story bigger than any in the training data.

        No model can measure what it never saw, so this brackets the answer between
        two extrapolations from the edge of what it did see, and takes the middle:

        * the model's own trend, continued — for each signal past its maximum, the
          points per unit the model itself puts on that signal between the training
          minimum and maximum, for this story, times the units beyond. Conservative:
          it holds everything else about the story where it is, so it undercounts a
          story that is bigger in every respect at once;
        * proportional — the model's answer at the maximum, scaled by how far past
          it the story is. Story points are additive under splitting, which is what
          splitting an epic relies on; an 18-component story is then 18/7 of the
          biggest one the model knows. Generous: it scales the fixed part of a story
          (its context, its setup) along with the parts.

        The estimate is their geometric mean, the middle on a deck whose cards grow
        geometrically. The range runs from the model's calibrated lower bound plus the
        trend, to its calibrated upper bound scaled in proportion, so it widens the
        further the story is from the data. At the maximum both extrapolations equal
        the model's own answer, so this meets the model exactly where it stops.
        """
        gain = 0.0
        ratio = 1.0
        per_unit: dict[str, float] = {}
        for key, detail in beyond.items():
            index = self.struct_columns.index(key)
            floor = TRAINING_MINIMUMS[key]
            at_floor = scored_row.copy()
            at_floor[0][index] = floor
            at_min = float(self.model.predict(to_matrix(text, at_floor, self.tfidf))[0])
            # Never negative: more of something that makes work bigger cannot be read
            # as making the story smaller, whatever a dip in the trees says.
            slope = max(0.0, (raw_point - at_min) / (detail["training_max"] - floor))
            per_unit[key] = round(slope, 3)
            gain += (detail["value"] - detail["training_max"]) * slope
            ratio = max(ratio, detail["value"] / detail["training_max"])

        trend = raw_point + gain
        proportional = raw_point * ratio
        estimate = math.sqrt(max(trend, 0.0) * max(proportional, 0.0))
        return {
            "model_point_at_limit": round(raw_point, 2),
            "trend_estimate": round(min(trend, RAW_CEILING), 2),
            "proportional_estimate": round(min(proportional, RAW_CEILING), 2),
            "points_per_extra_unit": per_unit,
            "scale_factor": round(ratio, 3),
            "point": min(estimate, RAW_CEILING),
            "low": min(raw_low + gain, RAW_CEILING),
            "high": min(raw_high * ratio, RAW_CEILING),
        }

    def _beyond_training(self, struct_row: np.ndarray) -> tuple[np.ndarray, dict[str, dict]]:
        """The row to score, capped at the training maximums, and what was capped.

        The dataset's stories stop at 13 points, and its size signals stop with them:
        at most 7 components and 6 external APIs. A story past those is bigger than
        anything the model has seen, and the model has no way to say so — every tree
        reads 18 components as 7, and XGBoost reads 7 external APIs as none. Scored at
        the maximums, the model at least answers for the biggest story it knows; the
        caller then reports the story as beyond it rather than trusting that answer.
        """
        scored = struct_row.copy()
        beyond: dict[str, dict] = {}
        for key, maximum in TRAINING_MAXIMUMS.items():
            index = self.struct_columns.index(key)
            value = float(struct_row[0][index])
            if value > maximum:
                beyond[key] = {"value": value, "training_max": maximum}
                scored[0][index] = maximum
        return scored, beyond

    def predict(self, payload: dict[str, Any]) -> dict[str, Any]:
        text, struct_row, provenance = build_row(payload, self.struct_columns)
        scored_row, beyond = self._beyond_training(struct_row)
        if beyond:
            provenance["beyond_training_range"] = beyond
        matrix = to_matrix(text, scored_row, self.tfidf)

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
        p20 = float(self.quantiles["P20"].predict(matrix)[0])
        p80 = float(self.quantiles["P80"].predict(matrix)[0])
        # The two heads are trained separately, and on an input unlike anything they
        # were fitted on they can cross. Crossed, the interval had negative width,
        # which _confidence floored at zero and reported as perfect precision: the
        # heads disagreeing most read as the model being most sure. Sorting them
        # before the conformal widening is the standard repair, and leaves an
        # uncrossed pair exactly as it was.
        p20, p80 = min(p20, p80), max(p20, p80)
        raw_low = p20 - self.cqr_delta
        raw_high = p80 + self.cqr_delta

        model_point = raw_point
        if beyond:
            # Bigger than anything in the training data: extrapolated, not measured,
            # and HIGH because the model is answering outside everything it saw.
            extrapolation = self._extrapolate(text, scored_row, beyond,
                                              raw_point, raw_low, raw_high)
            provenance["extrapolation"] = {key: value for key, value in extrapolation.items()
                                           if key not in ("point", "low", "high")}
            raw_point = extrapolation["point"]
            raw_low = extrapolation["low"]
            raw_high = extrapolation["high"]

        point = self._snap(raw_point)
        low = min(self._snap(raw_low), point)
        high = max(self._snap(raw_high), point)
        span = self._fib_index[high] - self._fib_index[low]
        risk = "HIGH" if beyond else self._risk(span)

        # Contributions from the row the model scored; labels from the story as it
        # is, so the planner reads "components touched (18)", not the capped 7.
        drivers = self._drivers(matrix, struct_row[0])

        return {
            "value": point,
            "lower_bound": low,
            "upper_bound": high,
            "confidence": self._confidence(raw_point, raw_low, raw_high, provenance),
            "label": risk,
            "drivers": drivers,
            "explanation": self._explain(point, low, high, risk, drivers, provenance),
            # For an extrapolated story "point" is the extrapolation, so the panel places
            # it on the deck correctly, and "model_point" is what the model said at the
            # edge of its training data.
            "raw": {"point": round(raw_point, 2),
                    "lower": round(raw_low, 2),
                    "upper": round(raw_high, 2),
                    **({"model_point": round(model_point, 2)} if beyond else {})},
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

        The point and the interval come from different models, and when the point
        lands outside the heads' interval the displayed range is stretched to take it
        in. Precision is measured on that same stretched range. Measured on the heads
        alone, a story the two models disagree about could be labelled HIGH risk and
        still report 90% confidence.
        """
        width = max(raw_high, raw_point) - min(raw_low, raw_point)
        relative = width / max(abs(raw_point), 1.0)
        confidence = 1.0 - relative / self.WIDEST_USEFUL_RELATIVE_WIDTH

        # An interval computed mostly from training medians is a statement about the
        # training set, not about this story, and it can look deceptively tight. Each
        # missing input costs a little certainty; unreadable wording costs more,
        # because the text is the only per-story signal the structure cannot supply.
        confidence -= 0.03 * len(provenance.get("defaulted_to_training_median", []))
        if provenance.get("text_terms_recognised") == 0:
            confidence -= 0.15
        if provenance.get("beyond_training_range"):
            confidence = min(confidence, self.BEYOND_TRAINING_CONFIDENCE_CEILING)

        return round(max(0.0, min(1.0, confidence)), 2)

    def _explain(self, point: float, low: float, high: float, risk: str,
                 drivers: list[dict[str, Any]], provenance: dict) -> str:
        coverage = int(round(self.coverage * 100))
        beyond = provenance.get("beyond_training_range")
        if beyond:
            # No coverage figure: the 70% was measured on stories the model knows.
            sentence = (f"Estimated about {point:g} points, somewhere between {low:g} and "
                        f"{high:g}. ")
        else:
            sentence = (f"Estimated {point:g} points, most likely between {low:g} and "
                        f"{high:g} ({coverage}% of the time). ")

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

        # The model did not measure this story; it was extrapolated from the edge of
        # what the model knows. Said first and alone, because nothing else about the
        # range applies to a number the model could not reach.
        if beyond:
            extrapolation = provenance["extrapolation"]
            reasons = " and ".join(
                f"{detail['value']:g} {DRIVER_LABELS.get(key, key)} (the most in its "
                f"training data was {detail['training_max']:g})"
                for key, detail in beyond.items())
            sentence += (f"This story has {reasons}, so it is bigger than anything the "
                         f"model has seen and the estimate is extrapolated, not measured: "
                         f"the model's own trend continued gives about "
                         f"{extrapolation['trend_estimate']:.0f}, the biggest story it knows "
                         f"scaled in proportion gives about "
                         f"{extrapolation['proportional_estimate']:.0f}, and the estimate "
                         f"sits between them. ")
            if point == self.scale[-1]:
                sentence += f"{point:g} is the top of the scale, so read it as {point:g} or more. "
            return sentence + "Treat it as an epic, and split it before committing to it."

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

        # Said because it moves the number by about two points, and a planner who sees
        # "task type: enhancement" on something filed as a Story deserves the reason.
        if provenance.get("story_type_read_as") == "enhancement":
            sentence += ("A Story this small — a few components, no external dependency or "
                         "security work, a short write-up — is sized as a change to something "
                         "existing rather than a new feature. ")

        # A bad value upstream, not a missing field, so it is named: the fix is to
        # correct that field, not to fill in more of them.
        rejected = provenance.get("rejected_as_invalid") or []
        if rejected:
            plural = len(rejected) != 1
            sentence += (f"{len(rejected)} supplied value{'s' if plural else ''} "
                         f"({', '.join(rejected)}) could not be used as given and "
                         f"{'were' if plural else 'was'} treated as missing. ")
        not_supplied = [key for key in provenance["defaulted_to_training_median"]
                        if key not in rejected]

        if risk == "HIGH":
            sentence += ("The range is wide, so treat this as a rough size and split the "
                         "story before committing to it.")
        elif point == self.fib[-1]:
            # The training data stops at 13. A story inside its size signals cannot be
            # placed above that by the model, and its range is cut off at the same
            # place, so a narrow-looking range here does not mean a well-sized story.
            sentence += (f"{point:g} is the largest size this model was trained on, so read "
                         f"it as {point:g} or more. A story this size is usually worth "
                         f"splitting before it is committed to.")
        elif not_supplied:
            missing = len(not_supplied)
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
