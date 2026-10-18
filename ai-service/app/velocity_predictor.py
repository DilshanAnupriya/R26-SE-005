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

# The lowest developer_availability in the training set, which spans 0.55-1.00 (as
# EstimationService.availability in the backend records). The backend sends values
# down to 0.05, so heavier absence than the model has seen does arrive, and the
# trees cannot see it: their lowest availability split is 0.57, so 30% and 55%
# scored exactly the same, and 95% of the team away barely less.
AVAILABILITY_TRAINING_FLOOR = 0.55

# The range of recent velocity the trees can tell apart: the lowest and highest split on
# avg_velocity_last3 in the base model (13.0 and 54.1; the Logistics fine-tune's are
# within 0.4 of them). Outside it every team looks like the edge: three sprints of zero
# scored about 10, and a team averaging 67 scored about 45 — the model answering for a
# team it knows rather than the one in front of it.
HISTORY_TRAINING_FLOOR = 13.0
HISTORY_TRAINING_CEILING = 54.1

# Scaling up is capped at double the ceiling. A history that far past the training data
# is more often a recording problem — points summed across projects, a typo — than a
# team, and an unbounded factor would hand that straight to the forecast.
HISTORY_MAX_SCALE = 2.0

# The most a prediction for a team with no velocity of its own may claim. Matches
# what the story-point model's tests hold an unreadable story to: a number built
# from dataset medians is a starting point, and must not present as a forecast.
COLD_START_CONFIDENCE_CEILING = 0.3


def _cold_start(provenance: dict) -> bool:
    """No velocity of this team's own: no usable sprints and no average in their place."""
    return "avg_velocity_last3" in provenance["defaulted_to_training_median"]


def _untrusted_history(provenance: dict) -> bool:
    """A history so far past the training data that the scaling had to be capped."""
    return bool((provenance.get("scaled_for_history_range") or {}).get("capped"))


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

        # Below the availability floor the trees cannot see absence get any worse,
        # so the prediction is scaled by how much of the floor's capacity is left.
        # Delivery in proportion to the time people are actually there is how sprint
        # capacity is planned anyway, and at the floor the factor is 1, so this meets
        # the model's own answer exactly where the model stops knowing.
        availability = float(matrix[0][self.features.index("developer_availability")])
        if availability < AVAILABILITY_TRAINING_FLOOR:
            factor = max(0.0, availability) / AVAILABILITY_TRAINING_FLOOR
            provenance["scaled_for_low_availability"] = {
                "availability": round(availability, 3),
                "training_floor": AVAILABILITY_TRAINING_FLOOR,
                "factor": round(factor, 3),
                "model_point": round(point, 3),
            }
            point *= factor

        # A team whose own recent velocity is outside what the trees can tell apart is
        # scaled the same way: by its average against the edge it was read as. The
        # team's own last sprints are the strongest evidence there is about its next
        # one, and "yesterday's weather" is the planning rule this continues. Only for
        # a real history — a team with none was given the training median, which is
        # inside the range.
        last3 = float(matrix[0][self.features.index("avg_velocity_last3")])
        if "avg_velocity_last3" not in provenance["defaulted_to_training_median"] and (
                last3 < HISTORY_TRAINING_FLOOR or last3 > HISTORY_TRAINING_CEILING):
            edge = HISTORY_TRAINING_FLOOR if last3 < HISTORY_TRAINING_FLOOR else HISTORY_TRAINING_CEILING
            factor = max(0.0, last3) / edge
            capped = factor > HISTORY_MAX_SCALE
            factor = min(factor, HISTORY_MAX_SCALE)
            provenance["scaled_for_history_range"] = {
                "avg_velocity_last3": round(last3, 2),
                "training_range": [HISTORY_TRAINING_FLOOR, HISTORY_TRAINING_CEILING],
                "factor": round(factor, 3),
                "capped": capped,
                "model_point": round(point, 3),
            }
            point *= factor

        # ± the model's typical miss on held-out data. Widened when the row was
        # built mostly from dataset medians, because the error measured on
        # complete rows does not apply to an incomplete one.
        mae = self.tuned_mae if tuned else self.zero_shot_mae
        missing = len(provenance["defaulted_to_training_median"])
        band = mae * (1.0 + 0.12 * missing)

        # The model has no feature for how erratic a team is, so its typical miss is
        # the same for a team that delivers 26 every sprint and one that swings
        # between 5 and 60. A forecast cannot honestly be tighter than the team's own
        # sprint-to-sprint spread, so that is the band whenever it is the wider one.
        spread = provenance.get("velocity_spread_last5")
        provenance["band_basis"] = "model error"
        if spread is not None and spread > band:
            band = spread
            provenance["band_basis"] = "team's own sprint-to-sprint spread"

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
            "label": self._label(band, point, provenance),
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
        # The penalty alone left a brand-new team at about 0.6: the band is the
        # model's miss on teams *with* history, so it is narrow exactly where the
        # strongest input is a dataset median. Capped, so the number agrees with the
        # explanation's "a starting point, not a forecast".
        # The same bar for a history the scaling had to cap: the forecast rests on a
        # number nobody should plan against until it has been checked.
        if _cold_start(provenance) or _untrusted_history(provenance):
            confidence = min(confidence, COLD_START_CONFIDENCE_CEILING)
        return round(max(0.0, min(1.0, confidence)), 2)

    def _label(self, band: float, point: float, provenance: dict) -> str:
        """Risk band, on the same scale the story-point model uses.

        A team with no velocity of its own is HIGH whatever the band says. With the
        rest of the sprint filled in, its band is the model's usual miss widened by
        a quarter, which can come out LOW: a LOW label beside an explanation telling
        the planner not to rely on the number.
        """
        if point <= 0 or _cold_start(provenance) or _untrusted_history(provenance):
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

        # The drivers describe the model's own answer, so a scaling applied after it
        # has to be said in words or the number and its reasons disagree.
        scaled = provenance.get("scaled_for_low_availability")
        if scaled:
            sentence += (f"Only {scaled['availability']:.0%} of the team's time is available, "
                         f"below the lowest the model was trained on "
                         f"({scaled['training_floor']:.0%}), so its prediction of "
                         f"{scaled['model_point']:.0f} was scaled down in proportion. ")
        history_scaled = provenance.get("scaled_for_history_range")
        if history_scaled:
            low, high = history_scaled["training_range"]
            below = history_scaled["avg_velocity_last3"] < low
            sentence += (f"This team's recent average of "
                         f"{history_scaled['avg_velocity_last3']:.0f} points is "
                         f"{'below the lowest' if below else 'above the highest'} the model "
                         f"was trained on ({low:.0f} to {high:.0f}), so its prediction of "
                         f"{history_scaled['model_point']:.0f} was scaled "
                         f"{'down' if below else 'up'} in proportion")
            sentence += (f", by at most {HISTORY_MAX_SCALE:g} times: a history that far past the "
                         f"training data is worth checking before it is planned against. "
                         if history_scaled["capped"] else ". ")
        if provenance.get("band_basis") == "team's own sprint-to-sprint spread":
            sentence += (f"This team's last sprints vary by about "
                         f"{provenance['velocity_spread_last5']:.0f} points from one to the next, "
                         f"more than the model's typical miss, so the range is that wide. ")

        rejected = provenance.get("rejected_as_invalid") or []
        if rejected:
            plural = len(rejected) != 1
            sentence += (f"{len(rejected)} supplied value{'s' if plural else ''} "
                         f"({', '.join(rejected)}) could not be used as given and "
                         f"{'were' if plural else 'was'} treated as missing. ")
        not_supplied = [key for key in provenance["defaulted_to_training_median"]
                        if key not in rejected]

        history = provenance["sprints_of_history"]
        if _cold_start(provenance):
            sentence += ("This team has no completed sprints on record, so the prediction "
                         "rests entirely on typical values rather than on anything they have "
                         "actually done. Treat it as a starting point, not a forecast.")
        elif 0 < history < 3:
            sentence += (f"Only {history} completed sprint{'s' if history != 1 else ''} of "
                         f"history, so the averages it leans on are still thin.")
        elif not_supplied:
            missing = len(not_supplied)
            sentence += (f"{missing} input{'s' if missing != 1 else ''} "
                         f"{'were' if missing != 1 else 'was'} not supplied and fell back to "
                         f"typical values, widening the range.")
        return sentence.strip()
