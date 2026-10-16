"""The communication-and-collaboration risk model (M2_COMMUNICATION_COLLABORATION).

A fine-tuned DistilBERT classifier over three bands — Low, Medium, High — reading
one sentence built from five sprint readings: how long replies take, how much
gets written on tasks, how many days pass in silence, how many tasks are blocked,
and how many come back after being called done.

Unlike the other two risk models, this one has a working fallback. Its training
labels came from :func:`compute_risk_score`, a deterministic function of the same
five readings, so that function is not an approximation of the classifier — it is
the boundary the classifier was fitted to. When the weights are absent (they are
270MB and regenerable, so they are not in the repository) the endpoint scores
from the equation instead of answering 503, and says which of the two produced
the number.

That honesty matters more than it might look. The transformer's contribution over
the equation is tolerance of readings between the thresholds and of combinations
the corpus did not contain; it is a smoothing of the bands, not a different
opinion. Reporting ``scorer`` on every response is what lets a reader know which
they are looking at instead of guessing from the shape of the probabilities.
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any

from .collaboration_features import (HIGH_BAND, MAX_COMPOSITE, MEDIUM_BAND,
                                     SEVERITY, build_collaboration_row,
                                     compute_risk_score, detected_causes,
                                     drivers, features_to_text, mitigations,
                                     score_to_label)
from .collaboration_fixes import LEVERS, collaboration_fixes

log = logging.getLogger(__name__)

CLASS_NAMES = ("Low", "Medium", "High")


class CollaborationRiskPredictor:
    """Scores one sprint's collaboration signals, with or without the weights."""

    def __init__(self, model_dir: Path | None = None):
        self.model_dir = model_dir
        self.tokenizer = None
        self.model = None
        self.torch = None
        self.metadata: dict[str, Any] = {
            "labelledBy": "composite score over five sprint readings, 0..18",
            "bands": {"Low": f"0..{MEDIUM_BAND - 1}",
                      "Medium": f"{MEDIUM_BAND}..{HIGH_BAND - 1}",
                      "High": f"{HIGH_BAND}..{MAX_COMPOSITE}"},
        }

        if model_dir is not None and model_dir.exists():
            self._load(model_dir)
        else:
            log.warning("No DistilBERT weights at %s — collaboration risk will be "
                        "scored from the composite equation the corpus was labelled "
                        "with. Regenerate with: python -m training.train_communication_model",
                        model_dir)

    @property
    def uses_transformer(self) -> bool:
        return self.model is not None and self.tokenizer is not None

    @property
    def scorer(self) -> str:
        return "distilbert" if self.uses_transformer else "composite_rules"

    def _load(self, model_dir: Path) -> None:
        # Imported here, not at module scope: torch and transformers are an
        # optional install worth about 2.5GB, and a deployment serving only the
        # three tabular models should not fail to boot for want of them.
        try:
            import torch
            from transformers import (DistilBertForSequenceClassification,
                                      DistilBertTokenizerFast)
        except ImportError as error:
            log.warning("transformers/torch not installed (%s) — collaboration risk "
                        "will be scored from the composite equation instead", error)
            return

        try:
            self.tokenizer = DistilBertTokenizerFast.from_pretrained(str(model_dir))
            self.model = DistilBertForSequenceClassification.from_pretrained(str(model_dir))
            self.model.eval()
            self.torch = torch
        except Exception as error:  # noqa: BLE001 — a bad bundle must not stop the service
            log.warning("Could not load collaboration weights from %s: %s — falling "
                        "back to the composite equation", model_dir, error)
            self.tokenizer = self.model = self.torch = None
            return

        # The label order is read off the checkpoint rather than assumed. A
        # retrained bundle that happened to permute id2label would otherwise
        # report High probabilities under the Low key, and every number on the
        # page would look plausible.
        id2label = self.model.config.id2label
        self.class_names = [id2label[i] for i in sorted(id2label)]
        self.metadata["labels"] = self.class_names
        log.info("Loaded collaboration risk model from %s: labels %s",
                 model_dir, self.class_names)

    # ------------------------------------------------------------------ predict

    def predict(self, payload: dict[str, Any]) -> dict[str, Any]:
        row, provenance = build_collaboration_row(payload)
        composite = compute_risk_score(**row)
        rule_level = score_to_label(composite)

        if self.uses_transformer:
            class_probabilities = self._transformer_probabilities(row)
            level = max(class_probabilities, key=class_probabilities.get)
        else:
            class_probabilities = self._rule_probabilities(composite)
            level = rule_level

        # P(High) is the number a planner acts on, and it is what the other two
        # risk models put in `value` — the probability of the bad outcome, not
        # the confidence in the predicted band.
        probability = class_probabilities.get("High", 0.0)

        provenance.update({
            "scorer": self.scorer,
            "composite_score": composite,
            "composite_max": MAX_COMPOSITE,
            "composite_level": rule_level,
        })

        # The transformer disagreeing with the equation it was trained on is
        # information, not noise — it means this sprint sits near a band edge.
        if self.uses_transformer and level != rule_level:
            provenance["disagrees_with_composite"] = True

        ranked = drivers(row)
        # Each raising reading gets the fix its exact SHAP value points to, re-scored by
        # the model serving this prediction. Attached to the driver it answers, so the
        # reason and the remedy cannot drift apart.
        try:
            fixes = collaboration_fixes(row, ranked, provenance.get("defaulted_to_zero", []),
                                        self._probability_of)
        except Exception as error:  # noqa: BLE001 — a missing fix never fails a prediction
            log.warning("Could not compute collaboration fixes: %s", error)
            fixes = {}
        for driver in ranked:
            if driver["feature"] in fixes:
                driver["fix"] = fixes[driver["feature"]]

        return {
            "value": round(probability, 4),
            "label": SEVERITY.get(level, "LOW"),
            "risk_level": level,
            "probability": round(probability, 4),
            "class_probabilities": class_probabilities,
            "confidence": self._confidence(class_probabilities, provenance),
            "drivers": ranked,
            "detected_causes": detected_causes(row),
            "mitigations": mitigations(row, level),
            "explanation": self._explain(level, probability, class_probabilities,
                                         ranked, composite, provenance),
            "provenance": provenance,
        }

    def _probability_of(self, row: dict[str, int]) -> float:
        """P(High) for a row, from whichever scorer is serving — what a fix is priced by."""
        if self.uses_transformer:
            return self._transformer_probabilities(row).get("High", 0.0)
        return self._rule_probabilities(compute_risk_score(**row)).get("High", 0.0)

    def _transformer_probabilities(self, row: dict[str, int]) -> dict[str, float]:
        text = features_to_text(**row)
        inputs = self.tokenizer(text, return_tensors="pt", truncation=True,
                                padding=True, max_length=128)
        with self.torch.no_grad():
            logits = self.model(**inputs).logits
        probs = self.torch.nn.functional.softmax(logits, dim=-1)[0]
        return {name: round(float(probs[i]), 4)
                for i, name in enumerate(self.class_names)}

    @staticmethod
    def _rule_probabilities(composite: int) -> dict[str, float]:
        """The composite as a three-way distribution.

        Not a real posterior and not presented as one: the mass is placed on the
        scored band and spread toward whichever neighbour the score is closest
        to, so a 7 (top of Medium) reads as nearly High rather than as confidently
        Medium. The alternative — 1.0 on the band, 0.0 elsewhere — would make a
        score one point from the boundary indistinguishable from one in the
        middle of it, which is exactly the distinction a planner needs.
        """
        if composite < MEDIUM_BAND:
            # 0..3. Blend toward Medium as the score approaches the boundary.
            toward = composite / MEDIUM_BAND
            low = 0.90 - 0.35 * toward
            medium = 0.08 + 0.30 * toward
        elif composite < HIGH_BAND:
            # 4..7. Blend from Low-adjacent to High-adjacent across the band.
            toward = (composite - MEDIUM_BAND) / (HIGH_BAND - MEDIUM_BAND)
            low = 0.25 - 0.18 * toward
            medium = 0.65 - 0.10 * toward
        else:
            # 8..18. Confidence grows with how far past the boundary it is.
            toward = (composite - HIGH_BAND) / max(MAX_COMPOSITE - HIGH_BAND, 1)
            low = 0.05 - 0.04 * toward
            medium = 0.25 - 0.16 * toward

        high = max(0.0, 1.0 - low - medium)
        total = low + medium + high
        return {"Low": round(low / total, 4),
                "Medium": round(medium / total, 4),
                "High": round(high / total, 4)}

    def _confidence(self, class_probabilities: dict[str, float],
                    provenance: dict) -> float:
        """How decided the model is, not how bad the risk is.

        The margin between the top band and the runner-up, on the same scale the
        other two risk models report — a 0.40/0.39/0.21 split is a model that
        cannot separate Low from Medium, and calling that a confident Low is the
        most misleading thing this could do.
        """
        ordered = sorted(class_probabilities.values(), reverse=True)
        margin = ordered[0] - ordered[1] if len(ordered) > 1 else ordered[0]
        confidence = min(1.0, margin + 0.25)

        # Each reading the backend could not supply is one fifth of the evidence
        # missing, and an absent reading here defaults to the healthy end.
        confidence -= 0.08 * len(provenance.get("defaulted_to_zero", []))
        if provenance.get("outside_training_range"):
            confidence -= 0.10
        if provenance.get("disagrees_with_composite"):
            confidence -= 0.10
        if not self.uses_transformer:
            # The equation has no uncertainty of its own to report, and pretending
            # otherwise would make the fallback look better calibrated than the model.
            confidence = min(confidence, 0.70)
        return round(max(0.0, min(1.0, confidence)), 2)

    def _explain(self, level: str, probability: float,
                 class_probabilities: dict[str, float], ranked: list[dict[str, Any]],
                 composite: int, provenance: dict) -> str:
        sentence = (f"{level} communication and collaboration risk — "
                    f"{probability * 100:.0f}% chance this sprint's coordination "
                    f"is the thing that costs it. ")
        sentence += self._explain_from_shap(ranked)

        ordered = sorted(class_probabilities.items(), key=lambda kv: kv[1], reverse=True)
        if len(ordered) > 1 and ordered[0][1] - ordered[1][1] < 0.15:
            sentence += (f"It is close between {ordered[0][0]} and {ordered[1][0]}, "
                         f"so read the level as a leaning rather than a verdict. ")

        if provenance.get("disagrees_with_composite"):
            sentence += (f"The composite score of {composite}/{MAX_COMPOSITE} puts it "
                         f"in {provenance['composite_level']}, so this sprint sits on "
                         f"a band edge. ")

        missing = provenance.get("defaulted_to_zero", [])
        if missing:
            sentence += (f"{len(missing)} of the five readings were not recorded and "
                         f"were treated as healthy, so this is a floor on the risk "
                         f"rather than a measurement of it. ")

        if not self.uses_transformer:
            sentence += ("Scored from the composite equation the model was trained "
                         "on rather than from the model itself — the weights are not "
                         "loaded on this deployment.")
        return sentence.strip()

    @staticmethod
    def _explain_from_shap(ranked: list[dict[str, Any]]) -> str:
        """The reasons and the remedy, read off the composite's exact SHAP values.

        The composite is a sum, so each reading's SHAP value is its own points over the
        ceiling. The remedy is the fix that lowers the model's probability the most —
        the same one shown under that reading.
        """
        up = [d for d in ranked if d["direction"] == "INCREASE"][:3]
        if not up:
            return ("SHAP: no reading is adding to the risk — every one of the five sits "
                    "at its healthy end. ")
        sentence = ("SHAP (exact — the composite is a sum of its readings): raised most by "
                    + ", ".join(f"{d['label']} ({d['contribution']:+.2f})" for d in up) + ". ")
        measured = [d["fix"] for d in ranked
                    if d.get("fix") and d["fix"].get("move")
                    and d["fix"].get("probability_after") is not None]
        if measured:
            best = min(measured, key=lambda fix: fix["probability_after"])
            move = best["move"]
            verb = "raise" if move["to"] > move["from"] else "lower"
            unit = LEVERS[move["feature"]].unit
            sentence += (f"Biggest fix from SHAP: {verb} {move['label']} from "
                         f"{move['from']}{unit} to {move['to']}{unit}, which takes the model's risk from "
                         f"{best['probability_now'] * 100:.0f}% to "
                         f"{best['probability_after'] * 100:.0f}%. ")
        return sentence
