"""Next-sprint velocity, with the transfer-learning routing the bundle carries.

The bundle holds a base XGBoost regressor trained across four domains with the
domain column deliberately excluded, plus a ``DomainAdapter`` that routes a
prediction to a fine-tuned model when one exists for that domain and to the base
model when it does not. A domain nobody has fine-tuned gets a zero-shot answer
immediately rather than no answer at all — which is the point of training it that
way.

Honesty about what that buys: on the bundle's own held-out numbers, zero-shot is
MAE 3.31 and the fine-tuned Logistics model is 2.92, but a model trained *only*
on Logistics data reaches 2.59. Transfer is behind in-domain training once you
have 322 sprints of it; its value is at the other end, where five sprints already
beat starting from nothing. The response says which path answered so nobody reads
a zero-shot number as a tuned one.
"""

from __future__ import annotations

import logging
import sys
from pathlib import Path
from typing import Any

import joblib
import numpy as np

from .velocity_features import KNOWN_DOMAINS, build_row, normalise_domain

log = logging.getLogger(__name__)


class DomainAdapter:
    """Routes a prediction to a fine-tuned model, or the base model.

    Defined here because the bundle was pickled from a notebook, where the class
    lived in ``__main__``. Unpickling looks the class up by the module path it was
    saved under, so this is aliased into ``__main__`` below — the alternative is a
    bundle that cannot be loaded outside the notebook that produced it.

    Only ``predict`` is needed to serve. ``fine_tune`` stays in the notebook: it
    retrains on the concatenated source and target sets, which is not something to
    do inside a request.
    """

    def __init__(self, base=None, base_params=None, src_X=None, src_y=None, best_iter=None):
        self.base = base
        self.base_params = base_params or {}
        self.src_X = src_X
        self.src_y = src_y
        self.best_iter = best_iter
        self.tuned: dict[str, Any] = {}
        self.n_sprints: dict[str, int] = {}

    def predict(self, X, domain: str | None = None):
        if domain and domain in self.tuned:
            return self.tuned[domain].predict(X)
        return self.base.predict(X)


# The bundle references ``__main__.DomainAdapter``. Registering the alias before
# joblib.load is what makes the notebook's pickle loadable from a service.
sys.modules["__main__"].DomainAdapter = DomainAdapter  # type: ignore[attr-defined]


# Readable names for the driver list. A planner asking "why 26?" should not have
# to know that ``velocity_per_dev`` is points per person per sprint.
DRIVER_LABELS = {
    "avg_velocity_last3": "average velocity over the last 3 sprints",
    "avg_velocity_last5": "average velocity over the last 5 sprints",
    "team_size": "team size",
    "sprint_duration_days": "sprint length in days",
    "completion_rate": "recent completion rate",
    "carryover_tasks": "work carried over",
    "developer_availability": "developer availability",
    "leave_days": "planned leave",
    "planned_story_points": "points planned for this sprint",
    "workload_ratio": "planned work against capacity",
    "num_bugs": "open defects",
    "avg_experience_years": "average experience",
    "velocity_delta": "recent velocity trend",
    "effective_capacity": "effective capacity",
    "stress_score": "carryover, defects and leave combined",
    "velocity_per_dev": "points per developer",
}


class VelocityPredictor:
    """Loads the bundle once and predicts one sprint at a time."""

    def __init__(self, bundle_path: Path):
        bundle = joblib.load(bundle_path)
        self.adapter = bundle["adapter"]
        self.base_model = bundle["base_model"]
        self.features: list[str] = list(bundle["features"])
        self.metrics: dict[str, Any] = bundle.get("metrics", {})
        self.learning_curve: list[dict] = bundle.get("learning_curve", [])
        self.tuned_domains: list[str] = sorted(getattr(self.adapter, "tuned", {}).keys())
        self.tuned_sprint_counts: dict[str, int] = dict(getattr(self.adapter, "n_sprints", {}))

        # Width is asserted at load rather than at first request, so a swapped
        # bundle fails on startup instead of halfway through a planning session.
        expected = len(self.features)
        actual = int(self.base_model.n_features_in_)
        if expected != actual:
            raise ValueError(
                f"Bundle is inconsistent: {expected} feature names but the model "
                f"expects {actual} columns")

        # The interval is the model's own held-out error, not a quantile head —
        # this bundle ships a single point regressor. Reported as ± MAE so the
        # band means "typical miss", which is a claim the numbers support.
        self.zero_shot_mae = float(self.metrics.get("zero_shot_mae", 3.31))
        self.tuned_mae = float(self.metrics.get("full_finetune_mae", self.zero_shot_mae))

        log.info("Loaded velocity model: %d features, zero-shot MAE %.2f, tuned domains %s",
                 actual, self.zero_shot_mae, self.tuned_domains or "none")

    # ------------------------------------------------------------------ drivers

    def _drivers(self, matrix, values, domain: str | None, limit: int = 5) -> list[dict[str, Any]]:
        """Exact tree SHAP for this prediction, not global importances.

        Global importance answers "what matters on average", which is a different
        question from "why 26 for this sprint" and worse than no answer when
        presented as the latter.
        """
        model = self.adapter.tuned.get(domain) if domain else None
        model = model or self.base_model
        try:
            import xgboost as xgb

            contributions = model.get_booster().predict(
                xgb.DMatrix(matrix, feature_names=self.features), pred_contribs=True)[0]
        except Exception as error:  # noqa: BLE001 — never fail a prediction for its narration
            log.warning("Could not compute velocity drivers: %s", error)
            return []

        ranked = sorted(
            ((self.features[i], float(c), float(values[i])) for i, c in enumerate(contributions[:-1])),
            key=lambda row: abs(row[1]),
            reverse=True,
        )[:limit]

        return [
            {"feature": f"{DRIVER_LABELS.get(name, name.replace('_', ' '))} ({value:g})",
             "contribution": round(contribution, 3),
             # INCREASE / DECREASE is the vocabulary PredictionDriver uses in the
             # backend and the UI compares against literally.
             "direction": "INCREASE" if contribution > 0 else "DECREASE"}
            for name, contribution, value in ranked if abs(contribution) > 0.05
        ]

    # ------------------------------------------------------------------ predict

    def predict(self, payload: dict[str, Any]) -> dict[str, Any]:
        matrix, provenance = build_row(payload, self.features)
        domain = normalise_domain(payload.get("domain"))
        tuned = bool(domain and domain in getattr(self.adapter, "tuned", {}))

        point = float(self.adapter.predict(matrix, domain=domain)[0])
        point = max(0.0, point)

        # ± the model's typical miss on held-out data. Widened when the row was
        # built mostly from dataset medians, because the error measured on
        # complete rows does not apply to an incomplete one.
        mae = self.tuned_mae if tuned else self.zero_shot_mae
        missing = len(provenance["defaulted_to_training_median"])
        band = mae * (1.0 + 0.12 * missing)

        lower = max(0.0, point - band)
        upper = point + band

        drivers = self._drivers(matrix, matrix[0], domain if tuned else None)
        provenance["domain"] = domain
        provenance["routed_to"] = f"fine-tuned ({domain})" if tuned else "base model (zero-shot)"
        provenance["known_domains"] = list(KNOWN_DOMAINS)

        return {
            "value": round(point, 1),
            "lower_bound": round(lower, 1),
            "upper_bound": round(upper, 1),
            "confidence": self._confidence(band, point, provenance),
            "label": self._label(band, point),
            "drivers": drivers,
            "explanation": self._explain(point, lower, upper, tuned, domain, provenance, drivers),
            "raw": {"point": round(point, 3), "band": round(band, 3), "mae": round(mae, 3)},
            "provenance": provenance,
        }

    def _confidence(self, band: float, point: float, provenance: dict) -> float:
        """Precision, not probability: how tight the band is against the number.

        A ±3 point band on a predicted 40 is a far more useful prediction than the
        same band on a predicted 8, and a single confidence that ignored the scale
        would call them equally good.
        """
        if point <= 0:
            return 0.0
        confidence = 1.0 - (band / point)
        # A team with no closed sprints is being predicted from dataset medians
        # alone. The band cannot express that, so the confidence must.
        if provenance["sprints_of_history"] == 0:
            confidence -= 0.25
        elif provenance["sprints_of_history"] < 3:
            confidence -= 0.10
        return round(max(0.0, min(1.0, confidence)), 2)

    def _label(self, band: float, point: float) -> str:
        """Risk band, on the same scale the story-point model uses."""
        if point <= 0:
            return "HIGH"
        relative = band / point
        if relative <= 0.15:
            return "LOW"
        if relative <= 0.30:
            return "MEDIUM"
        return "HIGH"

    def _explain(self, point: float, lower: float, upper: float, tuned: bool,
                 domain: str | None, provenance: dict, drivers: list[dict]) -> str:
        sentence = (f"Expect about {point:.0f} points next sprint, typically between "
                    f"{lower:.0f} and {upper:.0f}. ")

        if tuned:
            seen = self.tuned_sprint_counts.get(domain, 0)
            sentence += (f"Scored by the model fine-tuned on {domain} ({seen} sprints of it), "
                         f"which lands within {self.tuned_mae:.1f} points on average. ")
        elif domain:
            sentence += (f"No {domain} history has been fine-tuned yet, so this is the "
                         f"universal model — it lands within {self.zero_shot_mae:.1f} points "
                         f"on average across domains. ")
        else:
            sentence += (f"Scored by the universal model, which lands within "
                         f"{self.zero_shot_mae:.1f} points on average. ")

        if drivers:
            up = [d for d in drivers if d["direction"] == "INCREASE"][:2]
            down = [d for d in drivers if d["direction"] == "DECREASE"][:2]
            if up:
                sentence += "Pushed up by " + " and ".join(d["feature"] for d in up) + ". "
            if down:
                sentence += "Held down by " + " and ".join(d["feature"] for d in down) + ". "

        history = provenance["sprints_of_history"]
        if history == 0:
            sentence += ("This team has no completed sprints on record, so the prediction "
                         "rests entirely on typical values rather than on anything they have "
                         "actually done. Treat it as a starting point, not a forecast.")
        elif history < 3:
            sentence += (f"Only {history} completed sprint{'s' if history != 1 else ''} of "
                         f"history, so the averages it leans on are still thin.")
        elif provenance["defaulted_to_training_median"]:
            missing = len(provenance["defaulted_to_training_median"])
            sentence += (f"{missing} input{'s' if missing != 1 else ''} "
                         f"{'were' if missing != 1 else 'was'} not supplied and fell back to "
                         f"typical values, widening the range.")
        return sentence.strip()
