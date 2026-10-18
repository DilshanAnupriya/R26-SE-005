"""Edge cases for the story-point model (M1_STORY_POINTS): what it does when the input is hard.

Asked for at Progress Presentation 2. test_predictor.py checks that an ordinary story
is explained properly; this file feeds the model the stories a planning tool receives
on a bad day — blank, malformed, impossible, enormous, self-contradictory, or written
in words the model has never seen — and pins down what should happen to each.

Every test expects one of four behaviours, and its docstring says which:

* a sensible prediction — on the 1-13 scale, the range around the estimate, nothing
  NaN, and a response FastAPI can serialise;
* a wider or less confident answer, when the model knows less;
* a recorded fallback — an unusable value treated as missing and named in
  ``provenance``, rather than scored as though it were a measurement;
* a clean error — 422 for a malformed request, 503 when no model is loaded.

The IDs in brackets ([SP-01] and so on) are the rows of docs/edge-case-testing-m1.md.

Some tests swap the trained models for fixed stand-ins. That is how the cases where
the model disagrees with itself — crossed quantile heads, a point estimate outside its
own interval — are reproduced on demand rather than waited for.
"""

from __future__ import annotations

import copy
import json
import math
from pathlib import Path

import numpy as np
import pytest

from app.features import TRAINING_MAXIMUMS, TRAINING_MEDIANS, TRAINING_MINIMUMS, build_row
from app.predictor import ESTIMATION_SCALE, RAW_CEILING, StoryPointPredictor

MODEL = Path(__file__).resolve().parent.parent / "models" / "model_xgb_lgbm_uncertainty.pkl"

pytestmark = pytest.mark.skipif(not MODEL.exists(), reason="model bundle not present")

NUMERIC_FIELDS = ("num_components", "external_apis", "team_size", "sprint_duration_days",
                  "similar_task_count", "team_velocity_avg", "recent_completion_rate",
                  "task_age_days")
CATEGORICAL_FIELDS = ("task_type", "domain", "tech_stack", "dev_experience_level")
FLAG_FIELDS = ("has_integration", "has_security", "has_ui_complexity")

# Values a caller can send that are not a usable number. 1e300 is finite to Python but
# past float32, which is what XGBoost reads, so to the model it is infinity.
UNUSABLE_NUMBERS = [float("nan"), float("inf"), float("-inf"), 1e300, "1e999", "six", [6], {"n": 6}]
UNUSABLE_IDS = ["nan", "inf", "-inf", "past-float32", "inf-as-string", "word", "list", "dict"]


@pytest.fixture(scope="module")
def predictor() -> StoryPointPredictor:
    return StoryPointPredictor(MODEL)


def story(**overrides):
    """A fully specified story, so nothing falls back unless a test says so."""
    base = {
        "title": "Add CSV export to the reports page",
        "description": "Let a manager download the filtered report as a CSV file.",
        "task_type": "feature", "num_components": 3, "external_apis": 0,
        "team_size": 5, "sprint_duration_days": 14, "domain": "finance",
        "tech_stack": "springboot", "dev_experience_level": "mid",
        "team_velocity_avg": 22, "recent_completion_rate": 0.8,
        "similar_task_count": 8, "task_age_days": 5,
        "has_integration": 0, "has_security": 0, "has_ui_complexity": 1,
    }
    base.update(overrides)
    return base


def without(payload, *keys):
    return {k: v for k, v in payload.items() if k not in keys}


def column(row, predictor, name):
    return row[0][predictor.struct_columns.index(name)]


def assert_well_formed(result, predictor):
    """What every answer must satisfy, however strange the question."""
    # The deck the estimate is reported on: 0.5 to 100, wider than the 1-13 trained on.
    assert result["value"] in predictor.scale
    assert result["lower_bound"] in predictor.scale
    assert result["upper_bound"] in predictor.scale
    assert result["lower_bound"] <= result["value"] <= result["upper_bound"]
    assert 0.0 <= result["confidence"] <= 1.0
    assert result["label"] in ("LOW", "MEDIUM", "HIGH")
    assert all(math.isfinite(v) for v in result["raw"].values())
    assert isinstance(result["explanation"], str) and result["explanation"]
    # FastAPI serialises with allow_nan=False, so a NaN anywhere would be a 500.
    json.dumps(result, allow_nan=False)


def same_answer(a, b):
    keys = ("value", "lower_bound", "upper_bound", "confidence", "raw")
    return all(a[key] == b[key] for key in keys)


class _Fixed:
    """A stand-in model that answers the same number for any story."""

    def __init__(self, value: float):
        self.value = value

    def predict(self, matrix):
        return np.full(matrix.shape[0], self.value)


def with_models(predictor, point: float, p20: float, p80: float) -> StoryPointPredictor:
    """The real feature pipeline and conformal correction, with chosen model outputs.

    The stand-ins have no booster, so the driver list comes back empty — which is the
    predictor's own behaviour when drivers cannot be computed, and beside the point here.
    """
    stub = copy.copy(predictor)
    stub.model = _Fixed(point)
    stub.quantiles = {"P20": _Fixed(p20), "P80": _Fixed(p80)}
    return stub


# --------------------------------------------------------------------- missing input

class TestMissingInput:
    def test_an_empty_request_still_answers_and_calls_itself_a_baseline(self, predictor):
        """[SP-01] Expected: a sensible prediction that says it is the model's baseline,
        with low confidence and every defaulted input named."""
        result = predictor.predict({})
        assert_well_formed(result, predictor)
        provenance = result["provenance"]
        assert set(provenance["defaulted_to_training_median"]) == set(NUMERIC_FIELDS + CATEGORICAL_FIELDS)
        assert set(provenance["inferred_from_text"]) == set(FLAG_FIELDS)
        assert provenance["text_terms_recognised"] == 0
        assert result["confidence"] <= 0.3
        assert "baseline" in result["explanation"]

    def test_none_everywhere_is_the_same_as_leaving_everything_out(self, predictor):
        """[SP-02] Expected: null fields are "not supplied", exactly as absent ones are."""
        keys = ("title", "description", "acceptance_criteria", "story_type",
                *NUMERIC_FIELDS, *CATEGORICAL_FIELDS, *FLAG_FIELDS)
        nulls = predictor.predict({key: None for key in keys})
        assert same_answer(nulls, predictor.predict({}))
        assert nulls["provenance"]["rejected_as_invalid"] == []

    def test_a_story_with_no_text_rests_on_its_structure_and_says_so(self, predictor):
        """[SP-03] Expected: a sensible prediction from the structure alone, and an
        explanation that admits the wording was not read."""
        result = predictor.predict(story(title="   ", description=None, acceptance_criteria=""))
        assert_well_formed(result, predictor)
        assert result["provenance"]["text_length_words"] == 0
        assert result["provenance"]["text_terms_recognised"] == 0
        assert "did not recognise any of this story's wording" in result["explanation"]


# ------------------------------------------------------------------ malformed numbers

class TestMalformedNumbers:
    @pytest.mark.parametrize("bad", UNUSABLE_NUMBERS, ids=UNUSABLE_IDS)
    def test_an_unusable_number_is_treated_as_missing_and_named(self, predictor, bad):
        """[SP-04] Expected: fallback to the training median, recorded as rejected.

        Before the fix NaN went to the model as XGBoost's "missing" without being
        recorded, and infinity was scored as an extreme nobody meant."""
        _, row, provenance = build_row(story(team_size=bad), predictor.struct_columns)
        assert column(row, predictor, "team_size") == TRAINING_MEDIANS["team_size"]
        assert "team_size" in provenance["defaulted_to_training_median"]
        assert "team_size" in provenance["rejected_as_invalid"]
        assert np.isfinite(row).all()

    def test_an_infinite_number_scores_exactly_like_an_omitted_one(self, predictor):
        """[SP-05] Expected: the same answer as leaving the field out, and a response
        that serialises."""
        infinite = predictor.predict(story(team_size=float("inf")))
        assert_well_formed(infinite, predictor)
        assert same_answer(infinite, predictor.predict(without(story(), "team_size")))

    @pytest.mark.parametrize("field, value", [
        ("team_size", 0), ("team_size", -4), ("sprint_duration_days", 0),
        ("num_components", -3), ("external_apis", -1), ("recent_completion_rate", -0.5),
        ("task_age_days", -10), ("team_velocity_avg", -22),
    ])
    def test_an_impossible_value_is_not_scored_as_a_measurement(self, predictor, field, value):
        """[SP-06] Expected: fallback, recorded as rejected. A team of nobody or a
        negative count cannot be real, so it is not priced as though it were."""
        _, row, provenance = build_row(story(**{field: value}), predictor.struct_columns)
        assert column(row, predictor, field) == TRAINING_MEDIANS[field]
        assert field in provenance["rejected_as_invalid"]

    def test_zero_stays_a_real_value_where_it_can_be(self, predictor):
        """[SP-06] Expected: no fallback. No external APIs and a team that has not
        delivered yet are both real readings, not gaps."""
        _, row, provenance = build_row(story(external_apis=0, team_velocity_avg=0),
                                       predictor.struct_columns)
        assert column(row, predictor, "external_apis") == 0
        assert column(row, predictor, "team_velocity_avg") == 0
        assert provenance["rejected_as_invalid"] == []

    def test_numbers_sent_as_strings_are_read(self, predictor):
        """[SP-07] Expected: read as numbers, not rejected."""
        _, row, provenance = build_row(story(team_size="7", num_components=" 4 "),
                                       predictor.struct_columns)
        assert column(row, predictor, "team_size") == 7
        assert column(row, predictor, "num_components") == 4
        assert provenance["defaulted_to_training_median"] == []

    def test_the_explanation_names_a_rejected_value(self, predictor):
        """[SP-08] Expected: the explanation says which supplied value was unusable, so
        the fix — correcting that field upstream — is obvious."""
        explanation = predictor.predict(story(team_size=-4))["explanation"]
        assert "team_size" in explanation
        assert "could not be used" in explanation


# ------------------------------------------------------------------------------ flags

class TestFlags:
    def test_a_nan_flag_is_guessed_from_the_text_not_read_as_yes(self, predictor):
        """[SP-09] Expected: fallback to the keyword guess, recorded. NaN is truthy, so
        it used to switch the flag on."""
        payload = story(title="Add CSV export", description="", has_security=float("nan"))
        _, row, provenance = build_row(payload, predictor.struct_columns)
        assert column(row, predictor, "has_security") == 0.0
        assert "has_security" in provenance["inferred_from_text"]
        assert "has_security" in provenance["rejected_as_invalid"]

    @pytest.mark.parametrize("word, expected", [
        ("yes", 1.0), ("TRUE", 1.0), ("y", 1.0), ("1", 1.0), (True, 1.0),
        ("no", 0.0), ("False", 0.0), ("0", 0.0), (False, 0.0),
    ])
    def test_yes_and_no_are_read_however_they_are_spelt(self, predictor, word, expected):
        """[SP-09] Expected: read as given. "yes" used to fail float() and become "no"."""
        _, row, provenance = build_row(story(has_security=word), predictor.struct_columns)
        assert column(row, predictor, "has_security") == expected
        assert "has_security" not in provenance["inferred_from_text"]

    def test_an_explicit_flag_beats_the_keyword_guess(self, predictor):
        """[SP-10] Contradictory signals: the text says security and integration, the
        caller says neither. Expected: the caller wins, because it is the one that knows."""
        title = "Add OAuth token encryption to the login API"
        _, said_no, provenance = build_row(
            story(title=title, description="", has_security=0, has_integration=0),
            predictor.struct_columns)
        _, guessed, _ = build_row(
            without(story(title=title, description=""), "has_security", "has_integration"),
            predictor.struct_columns)

        assert column(said_no, predictor, "has_security") == 0.0
        assert column(said_no, predictor, "has_integration") == 0.0
        assert provenance["inferred_from_text"] == []
        # The guess, for contrast, would have switched both on.
        assert column(guessed, predictor, "has_security") == 1.0
        assert column(guessed, predictor, "has_integration") == 1.0


# -------------------------------------------------------------- contradictory signals

class TestContradictorySignals:
    def test_the_story_type_wins_over_a_conflicting_task_type(self, predictor):
        """[SP-11] Expected: SprintIQ's own story type is used; the two are never both set."""
        _, row, _ = build_row(story(story_type="BUG", task_type="feature"), predictor.struct_columns)
        assert column(row, predictor, "task_type_bug") == 1.0
        assert column(row, predictor, "task_type_feature") == 0.0

    def test_a_tiny_title_with_a_huge_structure_still_answers(self, predictor):
        """[SP-12] Expected: a sensible prediction, whatever the model makes of it."""
        result = predictor.predict(story(title="Fix typo", description="", num_components=12,
                                         external_apis=5, has_integration=1, has_security=1))
        assert_well_formed(result, predictor)

    def test_crossed_quantile_heads_are_repaired_not_read_as_certainty(self, predictor):
        """[SP-13] The P20 head answering above the P80 head. Expected: the same answer as
        the uncrossed pair. Before the fix the interval collapsed to a single value
        with a confidence of 1.0, so the heads disagreeing most read as most certain."""
        crossed = with_models(predictor, point=6.0, p20=9.0, p80=4.0).predict(story())
        uncrossed = with_models(predictor, point=6.0, p20=4.0, p80=9.0).predict(story())
        assert_well_formed(crossed, predictor)
        assert same_answer(crossed, uncrossed)
        assert crossed["lower_bound"] < crossed["upper_bound"]
        assert crossed["confidence"] < 1.0

    def test_a_point_outside_its_own_interval_is_not_reported_as_precise(self, predictor):
        """[SP-14] XGBoost says 13 while the interval heads say 2 to 4. Expected: a wide
        range, a HIGH label and a low confidence. Before the fix confidence was measured
        on the heads alone and came out at about 0.8 beside the HIGH label."""
        result = with_models(predictor, point=12.8, p20=2.0, p80=4.0).predict(story())
        assert_well_formed(result, predictor)
        assert result["value"] == 13
        assert result["lower_bound"] <= 3
        assert result["label"] == "HIGH"
        assert result["confidence"] <= 0.5


# ------------------------------------------------------------------- size extremes

TINY = story(title="Fix typo in footer", description="", task_type="bug",
             num_components=1, external_apis=0, has_ui_complexity=0)
HUGE = story(title="Migrate the billing platform to a multi-region datastore",
             description="Zero downtime, dual-write, backfill, reconciliation, compliance audit.",
             task_type="feature", num_components=12, external_apis=5,
             has_integration=1, has_security=1)


class TestSizeExtremes:
    def test_a_one_line_fix_is_not_sized_above_a_platform_migration(self, predictor):
        """[SP-15] Expected: the ordering a planner would give them."""
        tiny, huge = predictor.predict(TINY), predictor.predict(HUGE)
        assert_well_formed(tiny, predictor)
        assert_well_formed(huge, predictor)
        assert tiny["value"] <= huge["value"]

    def test_a_story_at_the_top_of_the_scale_is_read_as_that_or_more(self, predictor):
        """[SP-16] The model's scale stops at 13, where its training data did. Expected:
        13, with an explanation that reads it as "13 or more" and suggests splitting.
        Before the fix a story beyond the scale, its range cut off at 13, was described
        as "tight enough to plan against"."""
        result = with_models(predictor, point=14.2, p20=11.0, p80=16.0).predict(story())
        assert_well_formed(result, predictor)
        assert result["value"] == predictor.fib[-1] == 13
        assert "13 or more" in result["explanation"]
        assert "tight enough to plan against" not in result["explanation"]

    def test_values_far_past_the_training_range_plateau(self, predictor):
        """[SP-17] Expected: a thousand components and a million both land on the top
        card, 100, read as "100 or more" — the extrapolation does not run off the deck,
        and the raw number behind it stops at a ceiling the backend can store."""
        thousand = predictor.predict(story(num_components=1_000))
        million = predictor.predict(story(num_components=1_000_000))
        assert_well_formed(million, predictor)
        shown = ("value", "lower_bound", "upper_bound", "label")
        assert {k: thousand[k] for k in shown} == {k: million[k] for k in shown}
        assert million["value"] == ESTIMATION_SCALE[-1]
        assert max(million["raw"]["point"], million["raw"]["upper"]) <= RAW_CEILING

    def test_every_input_at_an_absurd_extreme_still_answers(self, predictor):
        """[SP-18] Expected: a sensible prediction on the scale, not a crash or NaN."""
        result = predictor.predict(story(
            team_size=500, sprint_duration_days=365, num_components=999, external_apis=999,
            similar_task_count=100_000, team_velocity_avg=1e6, recent_completion_rate=50,
            task_age_days=10_000))
        assert_well_formed(result, predictor)

    def test_a_five_thousand_word_description_is_scored(self, predictor):
        """[SP-19] Expected: a sensible prediction; length is counted, not choked on."""
        description = " ".join(["integrate the payment api and secure the dashboard"] * 625)
        result = predictor.predict(story(description=description))
        assert_well_formed(result, predictor)
        assert result["provenance"]["text_length_words"] >= 5000


# ------------------------------------------------------- beyond the training range

MIGRATION = dict(title="Migrate the billing platform to a multi-region datastore",
                 description="Zero downtime, dual-write, backfill, reconciliation and a "
                             "compliance audit.",
                 has_integration=1, has_security=1, has_ui_complexity=0)


class TestBeyondTrainingRange:
    """The dataset's stories stop at 13 points and 7 components. A story past that is
    extrapolated from the edge of what the model knows — said to be, and labelled HIGH."""

    def test_the_training_maximums_are_the_bundles_own(self, predictor):
        """[SP-29] Expected: the limits the guard uses are the largest values either model
        was actually trained on — the interval heads' record of their data, and the
        point model's highest split — not numbers someone picked."""
        width = len(predictor.tfidf.vocabulary_)
        infos = next(line for line in predictor.quantiles["P80"].booster_.model_to_string().splitlines()
                     if line.startswith("feature_infos=")).split("=", 1)[1].split(" ")
        splits = predictor.model.get_booster().trees_to_dataframe()
        for key, maximum in TRAINING_MAXIMUMS.items():
            index = width + predictor.struct_columns.index(key)
            heads_max = float(infos[index].strip("[]").split(":")[1])
            point_max = math.floor(splits[splits.Feature == f"f{index}"].Split.max())
            assert maximum == max(heads_max, point_max), key
            heads_min = float(infos[index].strip("[]").split(":")[0])
            assert TRAINING_MINIMUMS[key] == heads_min, key

    @pytest.mark.parametrize("field, value", [("num_components", 8), ("num_components", 18),
                                              ("external_apis", 7), ("external_apis", 12)])
    def test_a_story_past_the_training_maximum_is_extrapolated(self, predictor, field, value):
        """[SP-30] Expected: an estimate at least as big as the biggest story the model
        knows, labelled HIGH, confidence at most 0.3, an explanation that says it was
        extrapolated rather than measured and suggests splitting, the input named."""
        at_limit = predictor.predict(story(**MIGRATION, **{field: TRAINING_MAXIMUMS[field]}))
        result = predictor.predict(story(**MIGRATION, **{field: value}))
        assert_well_formed(result, predictor)
        assert result["value"] >= at_limit["value"]
        assert result["label"] == "HIGH"
        assert result["confidence"] <= predictor.BEYOND_TRAINING_CONFIDENCE_CEILING
        assert "extrapolated, not measured" in result["explanation"]
        assert "split it" in result["explanation"]
        assert result["provenance"]["beyond_training_range"][field] == {
            "value": value, "training_max": TRAINING_MAXIMUMS[field]}

    def test_at_the_maximum_the_model_answers_for_itself(self, predictor):
        """[SP-31] Expected: 7 components and 6 external APIs are inside what the model
        knows, so its own number stands and nothing is flagged."""
        result = predictor.predict(story(**MIGRATION, num_components=7, external_apis=6))
        assert "beyond_training_range" not in result["provenance"]
        assert "bigger than anything the model has seen" not in result["explanation"]

    def test_seven_external_apis_are_not_scored_as_none(self, predictor):
        """[SP-32] Expected: past the maximum, the model scores the story as the biggest
        it knows. Before the fix XGBoost sent 7 external APIs down its "missing" branch,
        which for a sparse row means zero: a story with seven integrations scored
        lower than one with three (7.46 against 8.74)."""
        seven = predictor.predict(story(external_apis=7))["raw"]
        six = predictor.predict(story(external_apis=6))["raw"]["point"]
        none = predictor.predict(story(external_apis=0))["raw"]["point"]
        assert seven["model_point"] == six
        assert seven["point"] >= six > none

    def test_a_senior_teams_epic_is_not_called_tight(self, predictor):
        """[SP-33] Expected: above 13, and HIGH. Before, an 18-component migration for a
        senior team came back as 8, "tight enough to plan against": the trees read 18
        components as 7, and seniority pulled the rest down."""
        result = predictor.predict(story(**MIGRATION, dev_experience_level="senior",
                                         num_components=18, external_apis=2))
        assert result["value"] > 13
        assert result["label"] == "HIGH"
        assert "tight enough to plan against" not in result["explanation"]

    def test_the_drivers_name_the_story_as_it_is(self, predictor):
        """[SP-34] Expected: the model scored the story at 7 components, but the planner
        reads the 18 it really has — the number that explains the flag."""
        drivers = predictor.predict(story(**MIGRATION, num_components=18))["drivers"]
        assert any(d["feature"] == "components touched (18)" for d in drivers)

    def test_bigger_stories_climb_the_whole_deck(self, predictor):
        """[SP-35] Expected: past the training data, more components never mean a smaller
        story, and the deck above 13 is reachable: 20 by 18 components, 40 by 35, and
        100 — read as "100 or more" — for a story the size of a programme of work."""
        results = {n: predictor.predict(story(**MIGRATION, num_components=n, external_apis=2))
                   for n in (7, 8, 12, 18, 35, 60, 150)}
        sizes = [results[n]["value"] for n in sorted(results)]
        assert sizes == sorted(sizes), sizes
        assert results[18]["value"] >= 20
        assert results[35]["value"] >= 40
        assert results[150]["value"] == 100
        assert "read it as 100 or more" in results[150]["explanation"]

    def test_the_estimate_sits_between_its_two_extrapolations(self, predictor):
        """[SP-36] Expected: the extrapolated number lies between the model's own trend
        continued (the conservative one) and the biggest known story scaled in
        proportion (the generous one), and the range holds the estimate."""
        result = predictor.predict(story(**MIGRATION, num_components=18, external_apis=2))
        extrapolation = result["provenance"]["extrapolation"]
        raw = result["raw"]
        assert extrapolation["trend_estimate"] <= raw["point"] <= extrapolation["proportional_estimate"]
        assert raw["lower"] <= raw["point"] <= raw["upper"]
        assert extrapolation["points_per_extra_unit"]["num_components"] >= 0
        assert extrapolation["model_point_at_limit"] == raw["model_point"]

    def test_the_extrapolation_meets_the_model_at_its_edge(self, predictor):
        """[SP-37] Expected: one component past the maximum is barely different from the
        maximum itself — the extrapolation starts from the model's own answer there,
        not from a jump."""
        at_limit = predictor.predict(story(**MIGRATION, num_components=7, external_apis=2))
        one_past = predictor.predict(story(**MIGRATION, num_components=8, external_apis=2))
        assert one_past["raw"]["model_point"] == at_limit["raw"]["point"]
        assert 0 <= one_past["raw"]["point"] - at_limit["raw"]["point"] < 1.5

    def test_an_extrapolated_range_claims_no_coverage(self, predictor):
        """[SP-38] Expected: the "70% of the time" was measured on stories the model knows;
        an extrapolated range must not borrow it."""
        explanation = predictor.predict(story(**MIGRATION, num_components=18))["explanation"]
        assert "% of the time" not in explanation
        assert "somewhere between" in explanation


# ------------------------------------------------------------- the low end of the deck

class TestTheLowEndOfTheDeck:
    @pytest.mark.parametrize("raw, expected", [(0.2, 0), (0.6, 0.5), (0.7, 0.5), (0.8, 1),
                                               (1.4, 1), (1.6, 2)])
    def test_small_numbers_land_on_the_small_cards(self, predictor, raw, expected):
        """[SP-39] Expected: the deck reaches 0 and 0.5, so a model answer under 0.75 is a
        half-point story and one under 0.25 a zero, not rounded up to 1 (stand-in models)."""
        result = with_models(predictor, point=raw, p20=raw, p80=raw).predict(story())
        assert result["value"] == expected

    def test_a_tiny_storys_range_reaches_the_small_cards(self, predictor):
        """[SP-40] Expected: the trained model's smallest answers sit around 2 — its
        training data starts at 1 — but its calibrated range for a one-line fix reaches
        down to the small cards, which is where the honest uncertainty is."""
        result = predictor.predict(story(title="Fix typo in footer", description="",
                                         task_type="bug", num_components=1, external_apis=0,
                                         has_ui_complexity=0, dev_experience_level="senior"))
        assert_well_formed(result, predictor)
        assert result["lower_bound"] <= 1


# ------------------------------------------- SprintIQ's Story type: feature or change?

# The context every case in docs/m1-story-point-test-cases.txt is run with.
PRESENTATION_TEAM = dict(team_size=4, sprint_duration_days=14, domain="finance",
                         tech_stack="springboot", dev_experience_level="mid",
                         team_velocity_avg=30, recent_completion_rate=0.8,
                         similar_task_count=0, task_age_days=0)


def criteria(*lines):
    return "\n".join(f"- {line}" for line in lines)


TOOLTIP = dict(title="Add a tooltip to the Export button",
               description="Explain what the export contains when a user hovers over the button.",
               acceptance_criteria=criteria("Tooltip shows on hover", "Tooltip text can be translated"),
               num_components=3, external_apis=0, has_integration=False, has_security=False,
               has_ui_complexity=True)
STRIPE = dict(title="Integrate the Stripe payment gateway for subscriptions",
              description="Customers pay monthly by card through Stripe. Handle failed payments, "
                          "webhooks and refunds, and store no card data ourselves.",
              acceptance_criteria=criteria("Card payments succeed",
                                           "Failed payments retried and the user told",
                                           "Stripe webhooks verified",
                                           "Refunds issued from the admin page"),
              num_components=7, external_apis=1, has_integration=True, has_security=True,
              has_ui_complexity=False)
RBAC = dict(title="Add role-based access control to project settings",
            description="Only owners and admins may change project settings; members see them "
                        "read-only. Every change is written to the audit log.",
            acceptance_criteria=criteria("Owners and admins can edit", "Members see settings read-only",
                                         "Blocked attempts return 403", "Every change is audited"),
            num_components=7, external_apis=0, has_integration=False, has_security=True,
            has_ui_complexity=True)
SSO = dict(title="Single sign-on with Google and Microsoft accounts",
           description="Users sign in with their company Google or Microsoft account through OAuth "
                       "2.0 / OpenID Connect, accounts are linked by email, and admins can force "
                       "SSO for their organisation.",
           acceptance_criteria=criteria("Google sign-in works", "Microsoft sign-in works",
                                        "Existing accounts are linked by email",
                                        "Admins can enforce SSO"),
           num_components=7, external_apis=2, has_integration=True, has_security=True,
           has_ui_complexity=False)
MOBILE = dict(title="Build the customer mobile app for iOS and Android",
              description="A native-feeling mobile app with sign-in, dashboards, notifications, "
                          "offline mode and payments, released to both app stores.",
              num_components=16, external_apis=0, has_integration=True, has_security=False,
              has_ui_complexity=True)
MICROSERVICES = dict(title="Split the monolith into microservices",
                     description="Move every module of the platform into its own service with its "
                                 "own database, with no downtime and no lost data.",
                     num_components=36, external_apis=0, has_integration=True, has_security=True,
                     has_ui_complexity=False)


def sprintiq_story(shape, **overrides):
    return {**PRESENTATION_TEAM, **shape, "story_type": "STORY", **overrides}


class TestStoryTypeReading:
    """SprintIQ's Story has no counterpart in the dataset, which splits work into features
    (a new capability) and enhancements (a change to an existing one). Reading every Story
    as a feature sized a tooltip at 8."""

    def test_a_small_story_is_read_as_a_change_to_something_existing(self, predictor):
        """[SP-41] The tooltip from TC-03. Expected: read as an enhancement — no more
        components than the median training story, no dependency, no security work, a
        short write-up — and sized below the same story read as a feature. Before: 8."""
        result = predictor.predict(sprintiq_story(TOOLTIP))
        as_feature = predictor.predict({**PRESENTATION_TEAM, **TOOLTIP, "task_type": "feature"})
        assert result["provenance"]["story_type_read_as"] == "enhancement"
        assert result["value"] < as_feature["value"]
        assert result["value"] <= 5
        assert "change to something existing" in result["explanation"]
        _, row, _ = build_row(sprintiq_story(TOOLTIP), predictor.struct_columns)
        assert column(row, predictor, "task_type_enhancement") == 1.0
        assert column(row, predictor, "task_type_feature") == 0.0

    @pytest.mark.parametrize("change", [
        {"num_components": 4},
        {"external_apis": 1},
        {"has_security": True},
        {"description": " ".join(["Explain what the export contains."] * 8)},
        {"num_components": None},
    ], ids=["four components", "a dependency", "security work", "a long write-up",
            "size not supplied"])
    def test_any_sign_of_size_keeps_a_story_a_feature(self, predictor, change):
        """[SP-42] Expected: every condition matters on its own — one more component, one
        dependency, security work, a write-up past 40 words, or a size nobody supplied
        keeps the old reading."""
        payload = {k: v for k, v in sprintiq_story(TOOLTIP, **change).items() if v is not None}
        result = predictor.predict(payload)
        assert result["provenance"]["story_type_read_as"] == "feature"

    @pytest.mark.parametrize("shape, level, expected", [
        (STRIPE, "mid", 13), (RBAC, "mid", 8), (SSO, "mid", 13),
        (STRIPE, "junior", 13), (STRIPE, "senior", 8),
    ], ids=["TC-06 Stripe", "TC-07 RBAC", "TC-08 SSO", "TC-15 junior", "TC-15 senior"])
    def test_the_large_test_cases_keep_their_sizes(self, predictor, shape, level, expected):
        """[SP-43] Expected: stories bigger than the median stay features, so the
        presentation's large cases do not move."""
        result = predictor.predict(sprintiq_story(shape, dev_experience_level=level))
        assert result["provenance"]["story_type_read_as"] == "feature"
        assert result["value"] == expected

    @pytest.mark.parametrize("shape, at_least", [(MOBILE, 20), (MICROSERVICES, 40)],
                             ids=["TC-09 mobile", "TC-10 microservices"])
    def test_the_epics_keep_their_sizes(self, predictor, shape, at_least):
        """[SP-43] Expected: 20 and 40, HIGH, as before."""
        result = predictor.predict(sprintiq_story(shape))
        assert result["value"] >= at_least
        assert result["label"] == "HIGH"

    def test_a_small_task_is_read_the_same_way(self, predictor):
        """[SP-44] Expected: SprintIQ's TASK, which the old mapping also called a feature,
        follows the same rule."""
        result = predictor.predict({**sprintiq_story(TOOLTIP), "story_type": "TASK"})
        assert result["provenance"]["story_type_read_as"] == "enhancement"

    def test_an_export_button_is_not_integration_work(self, predictor):
        """[SP-45] Expected: "export" in a sentence is the system's own feature, not
        another system; "API" and "sync" still are."""
        tooltip = without(sprintiq_story(TOOLTIP), "has_integration")
        _, row, provenance = build_row(tooltip, predictor.struct_columns)
        assert column(row, predictor, "has_integration") == 0.0
        assert "has_integration" in provenance["inferred_from_text"]
        _, row, _ = build_row({**tooltip, "title": "Sync contacts with the Salesforce API"},
                              predictor.struct_columns)
        assert column(row, predictor, "has_integration") == 1.0


# --------------------------------------------------------------- unknown categories

UNKNOWN = dict(domain="manufacturing", tech_stack="laravel",
               dev_experience_level="expert", task_type="epic")


class TestUnknownCategories:
    def test_an_unknown_category_sets_no_column_at_all(self, predictor):
        """[SP-20] Expected: no one-hot column set — an unseen stack is not filed under
        whichever known one sorts first — and each one listed as defaulted."""
        _, row, provenance = build_row(story(**UNKNOWN), predictor.struct_columns)
        for prefix in ("domain_", "tech_stack_", "dev_experience_level_", "task_type_"):
            columns = [c for c in predictor.struct_columns if c.startswith(prefix)]
            assert columns, prefix
            assert all(column(row, predictor, c) == 0.0 for c in columns), prefix
        assert set(CATEGORICAL_FIELDS) <= set(provenance["defaulted_to_training_median"])

    def test_an_unknown_category_scores_exactly_like_a_blank_one(self, predictor):
        """[SP-20] Expected: the same answer as leaving those fields empty."""
        unknown = predictor.predict(story(**UNKNOWN))
        blank = predictor.predict(story(**{key: None for key in UNKNOWN}))
        assert_well_formed(unknown, predictor)
        assert same_answer(unknown, blank)

    def test_spelling_variants_of_known_values_are_recognised(self, predictor):
        """[SP-21] Expected: case, spaces, hyphens and dots do not make a known value
        unknown."""
        _, row, provenance = build_row(
            story(domain="E-Commerce", tech_stack="Spring Boot", dev_experience_level=" SENIOR ",
                  task_type=None, story_type="bug"),
            predictor.struct_columns)
        for name in ("domain_ecommerce", "tech_stack_springboot",
                     "dev_experience_level_senior", "task_type_bug"):
            assert column(row, predictor, name) == 1.0, name
        assert not set(CATEGORICAL_FIELDS) & set(provenance["defaulted_to_training_median"])


# ----------------------------------------------------------------------- odd text

class TestUnusualText:
    def test_wording_in_another_language_is_reported_as_unread(self, predictor):
        """[SP-22] Expected: a sensible prediction, and an explanation that says none of
        the wording matched the model's English vocabulary."""
        result = predictor.predict({"title": "修复登录页面的错误 🐛"})
        assert_well_formed(result, predictor)
        assert result["provenance"]["text_terms_recognised"] == 0
        assert "wording" in result["explanation"]

    def test_markup_and_sql_in_a_title_are_just_text(self, predictor):
        """[SP-23] Expected: a sensible prediction; nothing in the text is executed or
        breaks parsing."""
        result = predictor.predict(story(title="<script>alert(1)</script>'; DROP TABLE stories; --"))
        assert_well_formed(result, predictor)

    def test_text_fields_that_are_not_strings_do_not_break_it(self, predictor):
        """[SP-24] Expected: a sensible prediction; a number or list where text belongs is
        read as text."""
        result = predictor.predict(story(title=12345, description=["a", "list"],
                                         acceptance_criteria={"given": "a dict"}))
        assert_well_formed(result, predictor)


# ------------------------------------------------------------------------- over HTTP

@pytest.fixture
def service(predictor, monkeypatch):
    """The real endpoint, with this file's model in place of the startup loader."""
    main = pytest.importorskip("app.main")
    pytest.importorskip("httpx")
    from fastapi.testclient import TestClient

    monkeypatch.setattr(main, "predictor", predictor)
    return main, TestClient(main.app)


class TestOverHttp:
    def test_an_infinite_number_in_the_json_body_is_answered_not_crashed(self, service):
        """[SP-25] JSON's 1e999 parses to infinity in Python. Expected: 200, a body that
        serialises, and the field named as rejected."""
        _, client = service
        response = client.post(
            "/predict/story-points",
            content='{"features": {"title": "Add CSV export", "team_size": 1e999}}',
            headers={"Content-Type": "application/json"})
        assert response.status_code == 200, response.text
        assert "team_size" in response.json()["provenance"]["rejected_as_invalid"]

    @pytest.mark.parametrize("body", [{"features": "not a dict"}, {"features": [1, 2]},
                                      {"features": None}], ids=["string", "list", "null"])
    def test_a_request_that_is_not_shaped_like_one_is_a_422(self, service, body):
        """[SP-26] Expected: a clean validation error, not a 500."""
        _, client = service
        assert client.post("/predict/story-points", json=body).status_code == 422

    def test_an_empty_body_gets_the_baseline(self, service):
        """[SP-27] Expected: 200 and the same baseline as an empty feature map."""
        _, client = service
        response = client.post("/predict/story-points", json={})
        assert response.status_code == 200, response.text
        assert response.json()["provenance"]["text_terms_recognised"] == 0

    def test_no_model_loaded_is_a_503(self, service, monkeypatch):
        """[SP-28] Expected: 503, so the backend shows the story unestimated rather than
        inventing a number."""
        main, client = service
        monkeypatch.setattr(main, "predictor", None)
        assert client.post("/predict/story-points", json={"features": {}}).status_code == 503
