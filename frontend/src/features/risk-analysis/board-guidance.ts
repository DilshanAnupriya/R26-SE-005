import type { RiskAdjustment, RiskType } from "@/lib/api/ai-schemas";

/**
 * What to do about each board event that moved one of the three risk scores.
 *
 * <p>The model's own readings get their fix from the model: each risk service measures
 * one from SHAP for every reading that raised the score, and the panel shows that. Some
 * board charges are also counted in a reading the model scores — a blocked card and a
 * reopen for over-budget; an unanswered question, a silent card and a reopen for
 * collaboration — so the panel answers those with that reading's SHAP fix. The rest — a
 * task past its estimate, a card nobody owns, a logged requirement change — are not
 * readings any model scores, so SHAP has nothing to say about them. What clears one of those is a fact about the
 * backend rule that charged it, and that is what this file records.</p>
 *
 * <p><strong>Nothing here moves a number.</strong> Where a charge withdraws itself once
 * the problem is gone, the text says so, because that is the part that makes the fix
 * worth doing today rather than at the review.</p>
 */

/** What a board charge means and how to clear it, keyed by its source key's prefix. */
const CHARGE_ADVICE: Record<string, string> = {
  TASK_BLOCKED:
    "Name who will unblock it and by which day. If it can't be unblocked this sprint, move it out. The points come off as soon as the card leaves Blocked.",
  TASK_REOPEN_BUDGET:
    "Write the missed acceptance criteria on the card and check them before it goes back to Done. The rework already done stays counted, but the charge stops growing if the card isn't reopened again.",
  TASK_OVERDUE:
    "Finish it, or split off what's left into a new task for the next sprint. The points come off when the card reaches Done.",
  TASK_NOT_DONE_OVERDUE:
    "Finish it, or split off what's left into a new task for the next sprint. The points come off when the card reaches Done.",
  TASK_NOT_STARTED_HALF:
    "Give it an owner and start it today, or move it out of the sprint. The points come off once it's in progress.",
  STORY_OVER_BUDGET:
    "Its tasks add up to more time than its points allow. Re-point the story, trim or re-estimate its tasks, or split it in two. The points come off once the tasks fit the estimate.",
  COST_OVER_BUDGET:
    "The cost forecast is over budget. Defer the lowest-value scope or agree a revised budget, then re-run the forecast. The points come off once the forecast is back inside the margin.",
  // ------------------------------------------------------------ collaboration
  TASK_UNASSIGNED:
    "Give it an owner now. Once someone owns it, only the share of its estimate that ran out while it waited stays charged — and extending the estimate by that wait clears the rest.",
  TASK_UNANSWERED:
    "Answer the question on the card. The charge grows the longer it waits and is withdrawn the moment it's answered.",
  COMMENT_UNANSWERED:
    "Reply on the card — even “not this week” is an answer somebody can work with. The charge comes off as soon as a newer comment appears.",
  TASK_SILENT:
    "Write on the card: a question, a decision, or where it stands. One comment clears this charge.",
  TASK_REOPENED:
    "Agree what “done” means for this card — write its acceptance criteria and check them before it goes back to Done. The reopens already logged stay counted; the charge only grows if it's reopened again.",
};

/**
 * The same, for rows written before charges carried a source key.
 *
 * <p>Older ledgers hold anonymous "Task blocked: PAY-18" rows from when a block was a
 * charge and an unblock a separate refund. They still count, so they still need the
 * real fix rather than the generic line, and the reason is all there is to go on.</p>
 */
const CHARGE_ADVICE_BY_REASON: [RegExp, string][] = [
  [/^Task blocked/i, CHARGE_ADVICE.TASK_BLOCKED],
  [/^Rework on/i, CHARGE_ADVICE.TASK_REOPEN_BUDGET],
  [/^Estimate exceeded|past its estimate/i, CHARGE_ADVICE.TASK_OVERDUE],
  [/not started at half/i, CHARGE_ADVICE.TASK_NOT_STARTED_HALF],
  [/^Nobody assigned|for an owner/i, CHARGE_ADVICE.TASK_UNASSIGNED],
  [/^Unanswered on/i, CHARGE_ADVICE.TASK_UNANSWERED],
  [/with no reply$/i, CHARGE_ADVICE.COMMENT_UNANSWERED],
  [/^No discussion on/i, CHARGE_ADVICE.TASK_SILENT],
  [/^Reopened d+ time/i, CHARGE_ADVICE.TASK_REOPENED],
];

function chargeAdvice(entry: RiskAdjustment): string {
  if (entry.code && CHARGE_ADVICE[entry.code]) {
    return CHARGE_ADVICE[entry.code];
  }
  const match = CHARGE_ADVICE_BY_REASON.find(([pattern]) => pattern.test(entry.reason));
  return match ? match[1] : GENERIC_CHARGE_ADVICE;
}

const GENERIC_CHARGE_ADVICE =
  "Open the card named here and deal with the underlying problem, then re-assess the sprint.";

/** Why a board credit counts in the sprint's favour, keyed the same way. */
const CREDIT_NOTE: Record<string, string> = {
  TASK_ON_TIME: "Finished inside its estimate, which shows the plan is holding.",
  TASK_STARTED_ON_TIME: "Picked up while its estimate still had time left.",
  TASK_COMMENTED: "Discussion written on the card — the behaviour the collaboration score rewards.",
  TASK_REPLIED: "Replies on the card: a thread being answered, which counts for more than a comment.",
};

const GENERIC_CREDIT_NOTE = "Board activity that brought the risk down.";

const CREDIT_NOTE_BY_REASON: [RegExp, string][] = [
  [/^Task unblocked/i, "The card was unblocked, which cancels the charge it got when it was blocked."],
  [
    /^Requirement change withdrawn/i,
    "The logged change was withdrawn, which cancels the charge it brought.",
  ],
];

/**
 * What the board section says above its rows, and when it has none, per risk.
 *
 * <p>Says which fixes are SHAP's and which are a board rule's, because the two sit in
 * the same list and only one of them is a measurement.</p>
 */
const BOARD_SECTION_COPY: Partial<Record<RiskType, { intro: string; empty: string }>> = {
  OVER_BUDGET: {
    intro:
      "Blocked and reopened cards are counted in readings the model scores, so their fix is the model’s own SHAP fix. The other charges come from board rules the model never sees — SHAP has no value for them, so their fix is the rule’s.",
    empty:
      "No board activity is moving this score right now. Blocked cards, reopens and tasks past their estimate will show up here as they happen.",
  },
  COMMUNICATION_COLLABORATION: {
    intro:
      "Unanswered questions, silent cards and reopens are counted in readings the model scores — reply time, comments per task, reopened tasks — so their fix is the model’s own SHAP fix. Unowned and late-starting cards come from board rules the model never sees — SHAP has no value for them, so their fix is the rule’s.",
    empty:
      "No board activity is moving this score right now. Unanswered questions, silent or unowned cards and reopens will show up here as they happen.",
  },
  REQUIREMENT_CHANGE: {
    intro:
      "Logged requirement changes, and their withdrawals, are entries on the sprint’s ledger, not readings the model scores — SHAP has no value for them, so their fix is the board rule’s. The model’s own readings, with their SHAP fixes, are below.",
    empty:
      "No board activity is moving this score right now. Requirement changes logged against this sprint will show up here, each with a tick for when the plan has been adjusted around it.",
  },
};

export function boardSectionCopy(riskType: RiskType): { intro: string; empty: string } {
  return BOARD_SECTION_COPY[riskType] ?? BOARD_SECTION_COPY.OVER_BUDGET!;
}

function creditNote(entry: RiskAdjustment): string {
  if (entry.code && CREDIT_NOTE[entry.code]) {
    return CREDIT_NOTE[entry.code];
  }
  const match = CREDIT_NOTE_BY_REASON.find(([pattern]) => pattern.test(entry.reason));
  return match ? match[1] : GENERIC_CREDIT_NOTE;
}

/**
 * The model reading a board charge is also counted in, where there is one.
 *
 * <p>RiskService feeds the sprint's live blocked count into {@code prev_blocked_tasks}
 * and its live reopens into {@code prev_reopened_tasks}, so for those two over-budget
 * charges the model has a SHAP value — and a measured fix — of its own. The
 * collaboration readings are read off the same task threads its charges are about: a
 * question left unanswered is in the reply time, a card nobody has written on is in the
 * comments per task, and a reopen is a reopen. Every other charge is a backend rule the
 * model never sees, and has no SHAP value to read.</p>
 *
 * <p>Codes are distinct across risks, so one map serves all three tabs; a tab only
 * ever looks a reading up among its own model's drivers.</p>
 */
const SHAP_READING: Record<string, string> = {
  TASK_BLOCKED: "prev_blocked_tasks",
  TASK_REOPEN_BUDGET: "prev_reopened_tasks",
  TASK_UNANSWERED: "avg_response_time",
  COMMENT_UNANSWERED: "avg_response_time",
  TASK_SILENT: "comments_per_task",
  TASK_REOPENED: "reopened_tasks",
};

const SHAP_READING_BY_REASON: [RegExp, string][] = [
  [/^Task blocked/i, SHAP_READING.TASK_BLOCKED],
  [/^Rework on/i, SHAP_READING.TASK_REOPEN_BUDGET],
  [/^Unanswered on/i, SHAP_READING.TASK_UNANSWERED],
  [/with no reply$/i, SHAP_READING.COMMENT_UNANSWERED],
  [/^No discussion on/i, SHAP_READING.TASK_SILENT],
  [/^Reopened d+ time/i, SHAP_READING.TASK_REOPENED],
];

function shapReading(entry: RiskAdjustment): string | null {
  if (entry.code && SHAP_READING[entry.code]) {
    return SHAP_READING[entry.code];
  }
  const match = SHAP_READING_BY_REASON.find(([pattern]) => pattern.test(entry.reason));
  return match ? match[1] : null;
}

/** One board event behind the score, with what it means and what to do about it. */
export type BoardEvent = {
  entry: RiskAdjustment;
  /** For a charge, how to clear it; for a credit, why it counts in the sprint's favour. */
  note: string;
  /** The model reading whose SHAP fix answers this charge, or null for a rule-only one. */
  reading: string | null;
};

/**
 * Ledger prefixes this list leaves to another part of the panel.
 *
 * <p>A claimed step already appears, ticked, under the reading it answers, and a
 * logged requirement change has its own section with its own tick. Listing either here
 * as well would count one fact twice on the same screen.</p>
 */
const SHOWN_ELSEWHERE = ["STEP:", "REQ_CHANGE:", "ABSORBED:"];

/**
 * The board events counting toward one risk's score right now.
 *
 * <p>Mirrors {@code SprintRiskAdjustmentService.Ledger.netSince}: an entry counts if it
 * survives scoring or was written after the assessment being shown. Everything else
 * was already folded into the model's own figure and is history, not a reason.</p>
 */
export function boardEventsFor(
  adjustments: RiskAdjustment[],
  riskType: RiskType,
  scoredAt: string | null | undefined,
): { raised: BoardEvent[]; lowered: BoardEvent[] } {
  const cutoff = scoredAt ? Date.parse(scoredAt) : Number.NaN;
  const counting = adjustments.filter(
    (entry) =>
      entry.riskType === riskType &&
      entry.delta !== 0 &&
      !SHOWN_ELSEWHERE.some((prefix) => entry.sourceKey?.startsWith(prefix)) &&
      (entry.survivesScoring || Number.isNaN(cutoff) || Date.parse(entry.createdAt) > cutoff),
  );
  const biggestFirst = (left: BoardEvent, right: BoardEvent) =>
    Math.abs(right.entry.delta) - Math.abs(left.entry.delta);

  return {
    raised: counting
      .filter((entry) => entry.delta > 0)
      .map((entry) => ({ entry, note: chargeAdvice(entry), reading: shapReading(entry) }))
      .sort(biggestFirst),
    lowered: counting
      .filter((entry) => entry.delta < 0)
      .map((entry) => ({ entry, note: creditNote(entry), reading: null }))
      .sort(biggestFirst),
  };
}
