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


def _label_for(column: str) -> str:
    if column in DRIVER_LABELS:
        return DRIVER_LABELS[column]
    for prefix, noun in (("task_type_", "task type"), ("domain_", "domain"),
                         ("tech_stack_", "tech stack"),
                         ("dev_experience_level_", "team experience")):
        if column.startswith(prefix):
            return f"{noun}: {column[len(prefix):]}"
    return column.replace("_", " ")


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

    def _drivers(self, matrix, limit: int = 5) -> list[dict[str, Any]]:
        """Per-prediction contributions from the trees, not global importances.

        ``pred_contribs`` is XGBoost's exact SHAP for tree ensembles: it says what
        moved *this* estimate. Global feature importance would answer a different
        question — what matters on average — and presenting that as "why 8?" is
        the kind of explanation that is worse than none.
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
            ((self.struct_columns[i], float(value)) for i, value in enumerate(structural)),
            key=lambda pair: abs(pair[1]),
            reverse=True,
        )[:limit]

        drivers = [
            {"feature": _label_for(column),
             "contribution": round(value, 3),
             "direction": "increases" if value > 0 else "decreases"}
            for column, value in ranked if abs(value) > 0.01
        ]
        # The wording matters: the text moved the estimate, but which words did it
        # is not something a TF-IDF contribution sum can honestly attribute.
        if abs(text_total) > 0.05:
            drivers.append({
                "feature": "wording of the story",
                "contribution": round(text_total, 3),
                "direction": "increases" if text_total > 0 else "decreases",
            })
        return drivers

    def predict(self, payload: dict[str, Any]) -> dict[str, Any]:
        text, struct_row, provenance = build_row(payload, self.struct_columns)
        matrix = to_matrix(text, struct_row, self.tfidf)

        raw_point = float(self.model.predict(matrix)[0])
        raw_low = float(self.quantiles["P20"].predict(matrix)[0]) - self.cqr_delta
        raw_high = float(self.quantiles["P80"].predict(matrix)[0]) + self.cqr_delta

        point = self._snap(raw_point)
        low = min(self._snap(raw_low), point)
        high = max(self._snap(raw_high), point)
        span = self._fib_index[high] - self._fib_index[low]
        risk = self._risk(span)

        drivers = self._drivers(matrix)

        return {
            "value": point,
            "lower_bound": low,
            "upper_bound": high,
            "confidence": self._confidence(span),
            "label": risk,
            "drivers": drivers,
            "explanation": self._explain(point, low, high, risk, drivers, provenance),
            "raw": {"point": round(raw_point, 2),
                    "lower": round(raw_low, 2),
                    "upper": round(raw_high, 2)},
            "interval_steps": span,
            "provenance": provenance,
        }

    def _confidence(self, span: int) -> float:
        """Confidence as interval tightness, on the scale the interval is drawn on.

        Not a probability. The interval already carries the calibrated
        probability — it covers the true value about %.0f%% of the time. This is
        the narrower question a planner asks: how precise is this?
        """
        widest = len(self.fib) - 1
        return round(max(0.0, min(1.0, 1.0 - span / widest)), 2)

    def _explain(self, point: int, low: int, high: int, risk: str,
                 drivers: list[dict[str, Any]], provenance: dict) -> str:
        coverage = int(round(self.coverage * 100))
        sentence = (f"Estimated {point} points, most likely between {low} and {high} "
                    f"({coverage}% of the time). ")

        if drivers:
            top = [d for d in drivers if d["direction"] == "increases"][:2]
            if top:
                sentence += "Pushed up by " + " and ".join(d["feature"] for d in top) + ". "

        if risk == "HIGH":
            sentence += ("The range is wide, so treat this as a rough size and split the "
                         "story before committing to it.")
        elif provenance["defaulted_to_training_median"]:
            missing = len(provenance["defaulted_to_training_median"])
            sentence += (f"{missing} input{'s' if missing != 1 else ''} were not supplied and "
                         f"fell back to typical values, so the estimate is less specific to "
                         f"this team than it could be.")
        elif high >= low * 2:
            # LOW here is relative to what this model usually produces, not an
            # absolute claim. Calling a 5-to-13 range "tight" would be a plain
            # overstatement — it is nearly a threefold spread.
            sentence += ("That is still a wide spread in absolute terms; it is narrow only "
                         "compared with this model's usual range.")
        else:
            sentence += "The range is tight enough to plan against."
        return sentence.strip()
