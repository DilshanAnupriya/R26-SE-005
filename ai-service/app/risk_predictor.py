"""The two CatBoost risk models: over-budget, and requirement change.

Both bundles have the same shape — a multiclass head over Low/Medium/High and a
binary head over the "did it happen" flag — so one class serves both and the
differences live in the feature builders.

Two heads rather than one is not redundancy. The multiclass head answers "how bad"
and the binary head answers "how likely", and a planner needs both: a High that the
model is 55% sure of is a different conversation from a High it is 95% sure of.
They are trained separately and can disagree, which is reported rather than
smoothed over.
"""

from __future__ import annotations

import logging
import pickle
from pathlib import Path
from typing import Any, Callable

import pandas as pd

from .risk_features import (OVERBUDGET_MEDIANS, REQUIREMENT_CHANGE_MEDIANS,
                            build_overbudget_row, build_requirement_change_row)
from .risk_fixes import shap_fixes

log = logging.getLogger(__name__)

# Readable names for the driver list, per model. A planner reading "why High?"
# should not have to know what prev_carry_over_rate is.
OVERBUDGET_LABELS = {
    "team_type": "team pattern",
    "team_size": "team size",
    "base_velocity": "baseline velocity",
    "complexity": "estimated complexity",
    "hours_per_point": "hours per point",
    "sprint_capacity_hours": "capacity in hours",
    "planned_hours": "planned hours",
    "committed_story_points": "points committed",
    "velocity_rolling_prev": "recent velocity",
    "velocity_trend": "velocity trend",
    "prev_carry_over_points": "points carried over last sprint",
    "prev_carry_over_rate": "carry-over rate last sprint",
    "prev_rework_score": "rework last sprint",
    "prev_blocked_tasks": "blocked tasks last sprint",
    "prev_reopened_tasks": "reopened tasks last sprint",
    "fatigue": "team fatigue",
    "scope_added": "scope added mid-sprint",
}

REQUIREMENT_CHANGE_LABELS = {
    "project_domain": "domain",
    "project_phase": "project phase",
    "team_seniority": "team seniority",
    "has_dedicated_BA": "dedicated business analyst",
    "requirements_doc_quality": "requirements quality",
    "n_stakeholders": "stakeholders involved",
    "baseline_story_count": "stories committed",
    "avg_story_length_words": "average story length",
    "avg_acceptance_criteria_per_story": "acceptance criteria per story",
    "ambiguity_marker_count": "hedging words in the stories",
    "stories_without_AC": "stories with no acceptance criteria",
    "stakeholder_meetings_held": "stakeholder meetings",
    "prev_change_requests_count": "change requests last sprint",
    "prev_acceptance_criteria_changes": "criteria changed last sprint",
    "prev_story_change_ratio": "story churn last sprint",
    "prev_high_risk_flag": "flagged high risk last sprint",
    "prev_requirement_volatility_score": "requirement volatility last sprint",
}

SEVERITY = {"Low": "LOW", "Medium": "MEDIUM", "High": "HIGH"}


class RiskPredictor:
    """Loads one CatBoost bundle and scores one sprint at a time."""

    def __init__(self, bundle_path: Path, kind: str):
        with open(bundle_path, "rb") as handle:
            bundle = pickle.load(handle)

        self.kind = kind
        self.model_multi = bundle["model_multiclass"]
        self.model_binary = bundle["model_binary"]
        self.encoder = bundle["label_encoder"]
        self.features: list[str] = list(bundle["predictive_features"])
        self.cat_features: list[str] = list(bundle["categorical_features"])
        self.class_names: list[str] = list(bundle["class_names"])
        self.metadata: dict[str, Any] = bundle.get("metadata", {})

        # How many readings the driver list may name. Over-budget sends every reading
        # that moved the score: its tab answers "why did this go up or down" in full,
        # with a fix under each reading that raised it, and a top five left a sprint's
        # other reasons unexplained. The 0.01 floor in _drivers still drops the noise.
        self.driver_limit = 5
        if kind == "over_budget":
            self._build: Callable = build_overbudget_row
            self.labels = OVERBUDGET_LABELS
            self.medians = OVERBUDGET_MEDIANS
            self.outcome = "going over budget"
            self.driver_limit = len(OVERBUDGET_LABELS)
        else:
            self._build = build_requirement_change_row
            self.labels = REQUIREMENT_CHANGE_LABELS
            self.medians = REQUIREMENT_CHANGE_MEDIANS
            self.outcome = "significant requirement change"

        # Asserted at load, so a swapped bundle fails on startup rather than
        # halfway through a planning session.
        expected = len(self.features)
        actual = int(self.model_binary.feature_names_ and len(self.model_binary.feature_names_))
        if actual and expected != actual:
            raise ValueError(f"Bundle is inconsistent: {expected} feature names but the "
                             f"model expects {actual}")

        log.info("Loaded %s risk model: %d features, test AUC %.3f, grouped CV AUC %.3f",
                 kind, expected, self.metadata.get("test_auc_binary", float("nan")),
                 self.metadata.get("cv_mean_auc", float("nan")))

    # ------------------------------------------------------------------ drivers

    def _drivers(self, frame: pd.DataFrame, row: dict[str, Any],
                 limit: int = 5) -> list[dict[str, Any]]:
        """Per-prediction SHAP from the binary head.

        The binary head rather than the multiclass one because its contributions
        are on a single axis — "this made it more likely" — which is a sentence.
        Multiclass SHAP is per class and does not summarise into one list without
        picking a class first, which would beg the question the drivers exist to
        answer.
        """
        try:
            from catboost import Pool

            pool = Pool(frame, cat_features=self.cat_features)
            # Last column is the model's expected value, not a feature.
            contributions = self.model_binary.get_feature_importance(
                pool, type="ShapValues")[0][:-1]
        except Exception as error:  # noqa: BLE001 — never fail a prediction for its narration
            log.warning("Could not compute %s drivers: %s", self.kind, error)
            return []

        ranked = sorted(
            ((name, float(value), row.get(name))
             for name, value in zip(self.features, contributions)),
            key=lambda item: abs(item[1]),
            reverse=True,
        )[:limit]

        drivers = []
        for name, contribution, value in ranked:
            if abs(contribution) <= 0.01:
                continue
            label = self.labels.get(name, name.replace("_", " "))
            shown = f"{value:g}" if isinstance(value, (int, float)) else value
            drivers.append({
                # The machine name, on its own. The backend's PredictionDriver contract
                # has always documented this field that way, and the mock client has
                # always honoured it; this end used to send "label (value)" instead,
                # which cost the consumer any way of recognising a driver except by
                # matching prose. Anything keyed on the driver — the remediation
                # catalogue most of all — needs the name the model was trained on.
                "feature": name,
                "label": label,
                "detail": shown,
                "contribution": round(contribution, 3),
                # INCREASE / DECREASE is the vocabulary PredictionDriver uses in the
                # backend and the UI compares against literally.
                "direction": "INCREASE" if contribution > 0 else "DECREASE",
            })
        return drivers

    # ------------------------------------------------------------------ predict

    def predict(self, payload: dict[str, Any]) -> dict[str, Any]:
        row, provenance = self._build(payload, self.features)
        frame = pd.DataFrame([row])[self.features]
        for column in self.cat_features:
            frame[column] = frame[column].astype(str)

        index = int(self.model_multi.predict(frame).flatten()[0])
        level = str(self.encoder.inverse_transform([index])[0])
        class_probabilities = {
            name: round(float(p), 4)
            for name, p in zip(self.class_names, self.model_multi.predict_proba(frame)[0])
        }
        probability = float(self.model_binary.predict_proba(frame)[0, 1])

        drivers = self._drivers(frame, row, limit=self.driver_limit)
        if self.kind == "over_budget" and drivers:
            # Each raising reading gets the fix its own SHAP value points to. Attached to
            # the driver it answers, so the reason and the remedy cannot drift apart.
            try:
                fixes = shap_fixes(self.model_binary, self.features, self.cat_features,
                                   self.labels, row, drivers,
                                   provenance.get("defaulted_to_training_median", []))
            except Exception as error:  # noqa: BLE001 — a missing fix never fails a prediction
                log.warning("Could not compute SHAP fixes: %s", error)
                fixes = {}
            for driver in drivers:
                if driver["feature"] in fixes:
                    driver["fix"] = fixes[driver["feature"]]
        provenance["model_test_auc"] = self.metadata.get("test_auc_binary")
        provenance["model_cv_auc"] = self.metadata.get("cv_mean_auc")

        return {
            # The backend reads `value` as the prediction's number and `label` as its
            # severity; the probability is the number a planner can act on.
            "value": round(probability, 4),
            "label": SEVERITY.get(level, "LOW"),
            "risk_level": level,
            "probability": round(probability, 4),
            "class_probabilities": class_probabilities,
            "confidence": self._confidence(class_probabilities, provenance),
            "drivers": drivers,
            "explanation": self._explain(level, probability, class_probabilities,
                                         drivers, provenance),
            "provenance": provenance,
        }

    def _confidence(self, class_probabilities: dict[str, float], provenance: dict) -> float:
        """How decided the model is, not how likely the risk.

        The margin between the top class and the runner-up. A 0.40/0.39/0.21 split
        is a model that cannot tell Low from Medium, and reporting that as a
        confident Low would be the most misleading thing this could do.
        """
        ordered = sorted(class_probabilities.values(), reverse=True)
        margin = ordered[0] - ordered[1] if len(ordered) > 1 else ordered[0]
        confidence = min(1.0, margin + 0.25)
        confidence -= 0.03 * len(provenance.get("defaulted_to_training_median", []))
        if provenance.get("team_type_inferred"):
            confidence -= 0.05
        return round(max(0.0, min(1.0, confidence)), 2)

    def _explain(self, level: str, probability: float, class_probabilities: dict[str, float],
                 drivers: list[dict[str, Any]], provenance: dict) -> str:
        sentence = (f"{level} risk of {self.outcome} — the model puts it at "
                    f"{probability * 100:.0f}%. ")

        ordered = sorted(class_probabilities.items(), key=lambda kv: kv[1], reverse=True)
        if len(ordered) > 1 and ordered[0][1] - ordered[1][1] < 0.15:
            sentence += (f"It is close between {ordered[0][0]} and {ordered[1][0]}, so treat "
                         f"the level as a leaning rather than a verdict. ")

        if drivers:
            up = [d for d in drivers if d["direction"] == "INCREASE"][:2]
            down = [d for d in drivers if d["direction"] == "DECREASE"][:2]
            # The readable label, not the trained column name: this sentence is shown
            # to a planner as-is, and "prev_carry_over_rate" is not a reason anyone reads.
            if up:
                sentence += "Raised by " + " and ".join(d["label"] for d in up) + ". "
            if down:
                sentence += "Lowered by " + " and ".join(d["label"] for d in down) + ". "

        if provenance.get("team_type_inferred"):
            sentence += (f"The team pattern '{provenance['team_type']}' was inferred from "
                         f"rework and velocity swings rather than recorded, so it is a "
                         f"reading of behaviour, not a fact about the team. ")

        missing = len(provenance.get("defaulted_to_training_median", []))
        if missing >= 4:
            sentence += (f"{missing} inputs were not supplied and fell back to typical "
                         f"values, so this says more about sprints in general than about "
                         f"this one.")
        return sentence.strip()
