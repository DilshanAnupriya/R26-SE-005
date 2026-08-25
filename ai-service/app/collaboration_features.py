"""The five collaboration readings, the equation that scores them, and the
sentence the language model reads.

Communication risk has no single measurable outcome the way effort overrun does,
so this model is labelled by a composite score rather than by a recorded event.
:func:`compute_risk_score` *is* that labelling function — the same one that
generated the fine-tuning corpus — which makes it the model's own decision
boundary written out in closed form rather than a second opinion about it. It is
kept here, beside the text builder, because the two must move together: change
the bands without regenerating the corpus and the classifier is answering a
question nobody asked any more.

:func:`features_to_text` must stay byte-identical to the version in
``training/create_communication_dataset.py``. DistilBERT was fine-tuned on that
exact phrasing; a reworded sentence at inference time is domain shift, and it
degrades quietly rather than erroring.
"""

from __future__ import annotations

from typing import Any

# What the corpus was sampled over. Inputs outside these ranges still score, but
# they are extrapolation — the model has seen nothing like them.
FEATURE_RANGES: dict[str, tuple[int, int]] = {
    "avg_response_time": (1, 96),   # hours
    "comments_per_task": (0, 20),
    "inactive_days": (0, 14),
    "blockers": (0, 10),
    "reopened_tasks": (0, 8),
}

# Readable names for the driver list. A planner reading "why High?" should not
# have to know what cpt stands for.
COLLABORATION_LABELS = {
    "avg_response_time": "average reply time",
    "comments_per_task": "comments per task",
    "inactive_days": "days with no activity",
    "blockers": "blocked tasks",
    "reopened_tasks": "reopened tasks",
}

# Each reading's ceiling in the composite. Response time, silence and blockers
# carry four points; thin discussion and rework carry three. The weighting is
# not arbitrary: the first three are a team that has stopped talking, the last
# two are a team that talks but not clearly, and the former is the harder
# failure to recover from inside one sprint.
FEATURE_WEIGHT_CEILINGS = {
    "avg_response_time": 4,
    "comments_per_task": 3,
    "inactive_days": 4,
    "blockers": 4,
    "reopened_tasks": 3,
}

MAX_COMPOSITE = sum(FEATURE_WEIGHT_CEILINGS.values())  # 18

# Where the bands fall on the 0..18 composite.
MEDIUM_BAND = 4
HIGH_BAND = 8

SEVERITY = {"Low": "LOW", "Medium": "MEDIUM", "High": "HIGH"}


def _response_time_points(avg_rt: int) -> int:
    if avg_rt > 48:
        return 4
    if avg_rt > 24:
        return 2
    if avg_rt > 12:
        return 1
    return 0


def _comments_points(cpt: int) -> int:
    # Inverted: fewer comments is worse. Zero is its own case rather than the
    # bottom of a ramp, because "nobody wrote anything" is a different failure
    # from "the discussion was thin".
    if cpt == 0:
        return 3
    if cpt == 1:
        return 2
    if cpt < 3:
        return 1
    return 0


def _inactivity_points(inactive: int) -> int:
    if inactive > 7:
        return 4
    if inactive > 4:
        return 2
    if inactive > 2:
        return 1
    return 0


def _blockers_points(blockers: int) -> int:
    if blockers > 5:
        return 4
    if blockers > 3:
        return 2
    if blockers > 1:
        return 1
    return 0


def _reopens_points(reopened: int) -> int:
    if reopened > 4:
        return 3
    if reopened > 2:
        return 2
    if reopened > 0:
        return 1
    return 0


PER_FEATURE_SCORERS = {
    "avg_response_time": _response_time_points,
    "comments_per_task": _comments_points,
    "inactive_days": _inactivity_points,
    "blockers": _blockers_points,
    "reopened_tasks": _reopens_points,
}


def compute_risk_score(avg_response_time: int, comments_per_task: int,
                       inactive_days: int, blockers: int,
                       reopened_tasks: int) -> int:
    """Composite communication risk, 0..18. Higher is worse.

    The parameters are named for the payload keys so a built row can be splatted
    straight in, and their order matches ``FEATURE_RANGES``, which is what the
    corpus generator iterates.

    The bands each reading contributes are step functions rather than a linear
    ramp, because the underlying signals are: a team replying in 30 hours and
    one replying in 40 are the same team with a habit, and a team replying in
    60 is a team that has stopped reading its notifications.
    """
    return (_response_time_points(avg_response_time)
            + _comments_points(comments_per_task)
            + _inactivity_points(inactive_days)
            + _blockers_points(blockers)
            + _reopens_points(reopened_tasks))


def score_to_label(score: int) -> str:
    """The three-way band. Must match ``create_communication_dataset.py``."""
    if score >= HIGH_BAND:
        return "High"
    if score >= MEDIUM_BAND:
        return "Medium"
    return "Low"


def features_to_text(avg_response_time: int, comments_per_task: int,
                     inactive_days: int, blockers: int,
                     reopened_tasks: int) -> str:
    """The sentence DistilBERT was fine-tuned on.

    *** This phrasing is the model's input contract. Reword it and inference is
    reading text the corpus never contained — which degrades quietly rather than
    erroring, so nothing would tell you. ***
    """
    return (
        f"The team had an average response time of {avg_response_time} hours. "
        f"There were {comments_per_task} comments per task on average. "
        f"The team was inactive for {inactive_days} days during the sprint. "
        f"There were {blockers} blocked tasks and {reopened_tasks} reopened tasks."
    )


def _as_int(value: Any, default: int = 0) -> int:
    """A backend figure as the whole number the corpus was written in.

    The Spring side sends averages — 2.4 comments per task, 31.7 hours — and the
    training sentences only ever contained integers. Rounding here rather than
    letting the f-string print "2.4 comments per task" keeps inference inside the
    vocabulary the model was fitted on.
    """
    if value is None or value == "":
        return default
    try:
        return int(round(float(value)))
    except (TypeError, ValueError):
        return default


def build_collaboration_row(payload: dict[str, Any]) -> tuple[dict[str, int], dict]:
    """The five readings, plus a note of which ones the caller did not send.

    A missing reading defaults to zero rather than to a training median, and that
    is a deliberate difference from the other two risk models. Zero comments and
    zero blockers are the *healthy* end of three of these five scales, so an
    absent field reads as "nothing to report" rather than as "typical sprint".
    Substituting a median here would invent collaboration problems for a sprint
    that simply has not logged anything yet — which is why the backend also
    declines to call this model at all until something has been recorded.
    """
    row: dict[str, int] = {}
    missing: list[str] = []

    for name in FEATURE_RANGES:
        raw = payload.get(name)
        if raw is None or raw == "":
            missing.append(name)
        row[name] = max(0, _as_int(raw))

    out_of_range = [
        name for name, value in row.items()
        if value > FEATURE_RANGES[name][1]
    ]

    provenance = {
        "defaulted_to_zero": missing,
        "outside_training_range": out_of_range,
    }
    return row, provenance


# ------------------------------------------------------------------ narration

def detected_causes(row: dict[str, int]) -> list[str]:
    """What is wrong, in the order the composite weights it.

    These fire on the same thresholds the score does, so a cause is never listed
    without contributing points and points are never scored without a cause. The
    two drifting apart is the failure mode that makes an explanation worse than
    none.
    """
    avg_rt = row["avg_response_time"]
    cpt = row["comments_per_task"]
    inactive = row["inactive_days"]
    blockers = row["blockers"]
    reopened = row["reopened_tasks"]

    causes: list[str] = []

    if avg_rt > 48:
        causes.append(f"Replies take {avg_rt}h on average — more than double the "
                      f"24h a working conversation needs")
    elif avg_rt > 24:
        causes.append(f"Replies take {avg_rt}h on average, so a question asked "
                      f"today is answered tomorrow at best")
    elif avg_rt > 12:
        causes.append(f"Replies take {avg_rt}h on average — slower than a working "
                      f"day, but not yet blocking")

    if cpt == 0:
        causes.append("No comments on any task — the work is being done without "
                      "any written discussion")
    elif cpt == 1:
        causes.append("One comment per task — enough to acknowledge work, not "
                      "enough to coordinate it")
    elif cpt < 3:
        causes.append(f"{cpt} comments per task — discussion is thin")

    if inactive > 7:
        causes.append(f"{inactive} days with no recorded activity — more than "
                      f"half a two-week sprint spent silent")
    elif inactive > 4:
        causes.append(f"{inactive} days with no recorded activity — engagement "
                      f"has dropped noticeably")
    elif inactive > 2:
        causes.append(f"{inactive} quiet days — worth watching")

    if blockers > 5:
        causes.append(f"{blockers} blocked tasks — a dependency or a conversation "
                      f"is not happening")
    elif blockers > 3:
        causes.append(f"{blockers} blocked tasks — blockers are accumulating "
                      f"rather than clearing")
    elif blockers > 1:
        causes.append(f"{blockers} blocked tasks open")

    if reopened > 4:
        causes.append(f"{reopened} tasks reopened — a strong signal that "
                      f"'done' means different things to different people")
    elif reopened > 2:
        causes.append(f"{reopened} tasks reopened — acceptance criteria are "
                      f"being read differently by author and reviewer")
    elif reopened > 0:
        causes.append(f"{reopened} task(s) reopened")

    if not causes:
        causes.append("No communication or collaboration warning signals in this "
                      "sprint's recorded activity")
    return causes


def mitigations(row: dict[str, int], level: str) -> list[str]:
    """What to do about it — one action per breached threshold, not a checklist.

    Returned to the backend as ``mitigations`` because that is the key
    ``RiskService`` already reads off a raw model response and stores on the
    assessment. Naming it anything else would mean new plumbing for a list the
    UI already knows how to render.
    """
    avg_rt = row["avg_response_time"]
    cpt = row["comments_per_task"]
    inactive = row["inactive_days"]
    blockers = row["blockers"]
    reopened = row["reopened_tasks"]

    actions: list[str] = []

    if avg_rt > 24:
        actions.append("Agree a reply norm — task comments and mentions "
                       "acknowledged within four working hours.")
    if cpt < 2:
        actions.append("Move decisions and questions onto the task itself rather "
                       "than into direct messages, so the next person can read them.")
    if inactive > 4:
        actions.append("Run a daily standup and make sure every person has one "
                       "clearly owned task at all times.")
    if blockers > 2:
        actions.append("Give each blocked task a named owner and review the open "
                       "blockers at every standup — target clearing within 24 hours.")
    if reopened > 2:
        actions.append("Refine acceptance criteria before committing, and add a "
                       "peer review to the definition of done.")

    if level == "High":
        actions.append("Hold a retrospective on communication alone, before the "
                       "next sprint is planned — the causes are upstream of the sprint.")
        actions.append("Pair on the most entangled work for a week to rebuild "
                       "shared context and break the knowledge silos.")

    if not actions:
        actions.append("Keep the current practices. Nothing in this sprint's "
                       "activity suggests a change is needed.")
    return actions


def drivers(row: dict[str, int], limit: int = 5) -> list[dict[str, Any]]:
    """Which readings moved the score, ranked by how much.

    The contribution is each reading's own points expressed as a fraction of the
    composite ceiling, which is exact rather than attributed: unlike the SHAP
    values the two CatBoost models report, this model's score is a sum, so its
    parts are simply known.

    A reading that scored zero is still returned, as a DECREASE, when nothing
    else did. "Everything is fine" needs evidence too, and an empty driver list
    reads on the dashboard as a model that failed rather than one that found
    nothing.
    """
    ranked: list[dict[str, Any]] = []
    for name, scorer in PER_FEATURE_SCORERS.items():
        value = row[name]
        points = scorer(value)
        ranked.append({
            # The machine name, so a consumer can recognise the reading without
            # matching prose — the label and the value travel in their own fields.
            "feature": name,
            "label": COLLABORATION_LABELS[name],
            "detail": str(value),
            "contribution": round(points / MAX_COMPOSITE, 3),
            "direction": "INCREASE" if points > 0 else "DECREASE",
            "_points": points,
        })

    ranked.sort(key=lambda item: item["_points"], reverse=True)
    breached = [item for item in ranked if item["_points"] > 0]
    chosen = breached[:limit] if breached else ranked[:limit]
    for item in chosen:
        item.pop("_points")
    return chosen
