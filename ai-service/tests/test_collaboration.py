"""The communication-and-collaboration model: the equation, the bands, and the
promise that the two scorers answer the same question.

The tests that matter here are the consistency ones. This model has two paths —
the fine-tuned classifier and the composite equation its corpus was labelled with
— and the fallback is only defensible while the second is genuinely the boundary
of the first. If the thresholds in ``collaboration_features`` ever drift from the
ones the corpus was generated with, every one of these still passes except
``test_causes_fire_exactly_when_points_are_scored``, which is the point of it.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from app.collaboration_features import (FEATURE_RANGES, HIGH_BAND, MAX_COMPOSITE,
                                        MEDIUM_BAND, PER_FEATURE_SCORERS,
                                        build_collaboration_row,
                                        compute_risk_score, detected_causes,
                                        drivers, features_to_text, mitigations,
                                        score_to_label)
from app.collaboration_predictor import CollaborationRiskPredictor

MODEL_DIR = Path(__file__).resolve().parent.parent / "models" / "communication_model"

HEALTHY = {"avg_response_time": 4, "comments_per_task": 6, "inactive_days": 0,
           "blockers": 0, "reopened_tasks": 0}
STRUGGLING = {"avg_response_time": 72, "comments_per_task": 0, "inactive_days": 9,
              "blockers": 7, "reopened_tasks": 6}


@pytest.fixture(scope="module")
def rules_only() -> CollaborationRiskPredictor:
    """The fallback path, forced — so it is tested on every machine."""
    return CollaborationRiskPredictor(None)


# ------------------------------------------------------------------- equation

def test_healthy_sprint_scores_zero():
    assert compute_risk_score(**HEALTHY) == 0
    assert score_to_label(0) == "Low"


def test_worst_case_saturates_the_composite():
    worst = {name: high for name, (_, high) in FEATURE_RANGES.items()}
    worst["comments_per_task"] = 0  # inverted: fewer is worse
    assert compute_risk_score(**worst) == MAX_COMPOSITE
    assert score_to_label(MAX_COMPOSITE) == "High"


def test_composite_never_leaves_its_stated_range():
    # Every corner of the feature space, which is cheap at 2^5 and catches a
    # ceiling that was raised in one scorer and not in MAX_COMPOSITE.
    for avg_rt in (1, 96):
        for cpt in (0, 20):
            for inactive in (0, 14):
                for blockers in (0, 10):
                    for reopened in (0, 8):
                        score = compute_risk_score(avg_rt, cpt, inactive, blockers, reopened)
                        assert 0 <= score <= MAX_COMPOSITE


def test_each_reading_is_monotone_in_the_direction_it_claims():
    """More delay, more silence, more blockers and more rework never score better.

    Comments are the exception and run the other way, which is exactly the sort
    of inversion an edit to one branch could quietly flip.
    """
    for name, scorer in PER_FEATURE_SCORERS.items():
        low, high = FEATURE_RANGES[name]
        values = range(low, high + 1)
        scores = [scorer(v) for v in values]
        if name == "comments_per_task":
            assert scores == sorted(scores, reverse=True)
        else:
            assert scores == sorted(scores)


def test_bands_are_contiguous_and_ordered():
    labels = [score_to_label(s) for s in range(MAX_COMPOSITE + 1)]
    assert labels[:MEDIUM_BAND] == ["Low"] * MEDIUM_BAND
    assert labels[MEDIUM_BAND:HIGH_BAND] == ["Medium"] * (HIGH_BAND - MEDIUM_BAND)
    assert set(labels[HIGH_BAND:]) == {"High"}


# --------------------------------------------------------------- text builder

def test_text_matches_the_corpus_phrasing():
    """Byte-for-byte, because DistilBERT was fitted on this exact sentence."""
    assert features_to_text(30, 2, 5, 4, 3) == (
        "The team had an average response time of 30 hours. "
        "There were 2 comments per task on average. "
        "The team was inactive for 5 days during the sprint. "
        "There were 4 blocked tasks and 3 reopened tasks.")


def test_fractional_backend_figures_are_rounded_into_the_corpus_vocabulary():
    """The backend sends 2.4 comments per task; the corpus only ever said 2."""
    row, _ = build_collaboration_row({"comments_per_task": 2.4, "avg_response_time": 31.7})
    assert row["comments_per_task"] == 2
    assert row["avg_response_time"] == 32
    assert "0." not in features_to_text(**row)


# ------------------------------------------------------------------ row build

def test_missing_readings_default_to_healthy_not_to_a_median():
    row, provenance = build_collaboration_row({})
    assert row == {name: 0 for name in FEATURE_RANGES}
    assert set(provenance["defaulted_to_zero"]) == set(FEATURE_RANGES)
    # Zero comments is the one default that is *not* healthy, and the composite
    # says so — an all-empty payload is not scored as a perfect sprint.
    assert compute_risk_score(**row) == 3


def test_readings_beyond_the_corpus_are_flagged_as_extrapolation():
    _, provenance = build_collaboration_row({**STRUGGLING, "blockers": 40})
    assert "blockers" in provenance["outside_training_range"]


# ------------------------------------------------------------------ narration

def test_causes_fire_exactly_when_points_are_scored():
    """No cause without points, and no points without a cause.

    A sprint whose score is driven by a reading the explanation does not mention
    is worse than an unexplained score: it sends the team to fix the wrong thing.
    """
    cases = [HEALTHY, STRUGGLING,
             {**HEALTHY, "avg_response_time": 30},
             {**HEALTHY, "inactive_days": 3},
             {**HEALTHY, "blockers": 2},
             {**HEALTHY, "reopened_tasks": 1},
             {**HEALTHY, "comments_per_task": 1}]
    for case in cases:
        row, _ = build_collaboration_row(case)
        breached = sum(1 for name, scorer in PER_FEATURE_SCORERS.items() if scorer(row[name]) > 0)
        causes = detected_causes(row)
        if breached == 0:
            assert len(causes) == 1 and causes[0].startswith("No communication")
        else:
            assert len(causes) == breached


def test_drivers_sum_to_the_composite_fraction():
    row, _ = build_collaboration_row(STRUGGLING)
    total = sum(d["contribution"] for d in drivers(row))
    assert total == pytest.approx(compute_risk_score(**row) / MAX_COMPOSITE, abs=0.01)


def test_a_healthy_sprint_still_gets_drivers():
    """An empty list reads on the dashboard as a failed model, not a clean bill."""
    row, _ = build_collaboration_row(HEALTHY)
    found = drivers(row)
    assert found
    assert all(d["direction"] == "DECREASE" for d in found)


def test_mitigations_escalate_with_the_band():
    healthy_row, _ = build_collaboration_row(HEALTHY)
    struggling_row, _ = build_collaboration_row(STRUGGLING)
    assert len(mitigations(healthy_row, "Low")) == 1
    assert len(mitigations(struggling_row, "High")) > 5


# ------------------------------------------------------------------- predictor

def test_fallback_scores_without_the_weights(rules_only):
    assert not rules_only.uses_transformer
    assert rules_only.scorer == "composite_rules"

    result = rules_only.predict(STRUGGLING)
    assert result["risk_level"] == "High"
    assert result["label"] == "HIGH"
    assert result["probability"] > 0.6
    assert result["provenance"]["scorer"] == "composite_rules"
    # Said in the text, not only in the metadata — the explanation is what a
    # planner reads, and a rules-scored number presented as a model's opinion
    # is the one thing this fallback must not do.
    assert "composite equation" in result["explanation"]


def test_fallback_probabilities_are_a_distribution(rules_only):
    for score_case in (HEALTHY, STRUGGLING, {**HEALTHY, "inactive_days": 5}):
        probabilities = rules_only.predict(score_case)["class_probabilities"]
        assert set(probabilities) == {"Low", "Medium", "High"}
        assert sum(probabilities.values()) == pytest.approx(1.0, abs=0.01)


def test_fallback_probability_rises_with_the_composite(rules_only):
    escalating = [
        HEALTHY,
        {**HEALTHY, "avg_response_time": 30},
        {**HEALTHY, "avg_response_time": 30, "inactive_days": 5},
        {**HEALTHY, "avg_response_time": 60, "inactive_days": 9, "blockers": 6},
        STRUGGLING,
    ]
    highs = [rules_only.predict(case)["probability"] for case in escalating]
    assert highs == sorted(highs)


def test_a_score_near_the_boundary_is_not_reported_as_confident(rules_only):
    """7 is the top of Medium. It must not look like a settled Medium."""
    edge = {"avg_response_time": 30, "comments_per_task": 1, "inactive_days": 3,
            "blockers": 2, "reopened_tasks": 1}
    assert compute_risk_score(**edge) == 7
    result = rules_only.predict(edge)
    assert result["risk_level"] == "Medium"
    assert result["class_probabilities"]["High"] > result["class_probabilities"]["Low"]


def test_unrecorded_readings_lower_the_confidence(rules_only):
    complete = rules_only.predict(HEALTHY)["confidence"]
    partial = rules_only.predict({"comments_per_task": 6})["confidence"]
    assert partial < complete


def test_the_response_carries_everything_the_backend_reads(rules_only):
    result = rules_only.predict(STRUGGLING)
    # HttpAiModelClient reads value/label/confidence/drivers/explanation; RiskService
    # additionally pulls mitigations off the raw body.
    for key in ("value", "label", "confidence", "drivers", "explanation", "mitigations"):
        assert key in result, key
    assert all({"feature", "contribution", "direction"} <= set(d) for d in result["drivers"])
    assert all(d["direction"] in ("INCREASE", "DECREASE") for d in result["drivers"])


@pytest.mark.skipif(not MODEL_DIR.exists(),
                    reason="DistilBERT weights not present — see training/")
def test_transformer_and_equation_agree_on_the_unambiguous_cases():
    """The fallback is only defensible while it is the model's own boundary.

    Only the clear-cut sprints are asserted. The whole reason the transformer
    exists is to disagree near the band edges, so pinning those would be pinning
    the thing it was trained to smooth.
    """
    predictor = CollaborationRiskPredictor(MODEL_DIR)
    if not predictor.uses_transformer:
        pytest.skip("transformers/torch not installed")

    assert predictor.predict(HEALTHY)["risk_level"] == "Low"
    assert predictor.predict(STRUGGLING)["risk_level"] == "High"
    assert predictor.scorer == "distilbert"
