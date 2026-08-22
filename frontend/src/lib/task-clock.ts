import type { Task } from "@/lib/api/delivery-schemas";

/**
 * The task work clock.
 *
 * <p>A stopwatch, not a deadline. The obvious implementation — `createdAt + estimate`
 * — is wrong in the one case that matters: a card parked in Done for a fortnight would
 * go on burning its estimate, and one dragged back out would either restart from zero
 * or jump forward two weeks. Neither is what anybody means by "how long has this
 * taken".</p>
 *
 * <p>So the server owns the state (seconds banked, plus the start of the current run if
 * it is running) and this module derives everything the card shows from it. Two
 * consequences worth knowing: the reading survives a reload, and two people looking at
 * the same board see the same number.</p>
 */

/** Statuses that stop the clock. Matches `TaskStatus.isTerminal()` on the server. */
const TERMINAL_STATUSES = new Set(["DONE", "CANCELLED"]);

export interface TaskClock {
  /** Working seconds so far. Frozen once the task is finished. */
  elapsedSeconds: number;
  /** The estimate in seconds, or null when the task carries none. */
  estimateSeconds: number | null;
  /** Seconds before the estimate runs out; 0 once it has, null with no estimate. */
  remainingSeconds: number | null;
  /** Seconds spent beyond the estimate; 0 while still inside it. */
  overrunSeconds: number;
  /** True while the clock is ticking. */
  running: boolean;
  /** True when the task is finished and the clock is frozen. */
  finished: boolean;
  /** Estimate exceeded, whatever column the card is in. */
  overEstimate: boolean;
  /**
   * The overdue badge condition: finished, but only after the estimate had run out. A
   * task completed inside its estimate is never flagged, and one still in progress past
   * its estimate shows a live overrun instead — that is a different situation and
   * deserves different wording.
   */
  overdueOnCompletion: boolean;
  /** How much of the estimate is gone, 0–1. Null with no estimate. */
  fraction: number | null;
  /**
   * Whether this item has a work clock at all.
   *
   * <p>False only for the story rows the board renders through the task shape: they
   * carry an estimate derived from story points but no clock, and without this flag
   * they would show a "Time left" that equalled the whole estimate and never moved —
   * a countdown that is visibly a lie.</p>
   */
  tracked: boolean;
}

/**
 * @param task  the card to read
 * @param nowMs current time, injected so it can be tested and so every card in one
 *              render pass agrees about "now" — otherwise two cards a millisecond apart
 *              can round to different seconds and the column visibly jitters
 */
export function computeTaskClock(task: Task, nowMs: number = Date.now()): TaskClock {
  const banked = Number.isFinite(task.elapsedSeconds) ? Math.max(0, task.elapsedSeconds) : 0;
  const finished = TERMINAL_STATUSES.has(task.status);
  const running = Boolean(task.runningSince) && !finished;

  const currentRun =
    running && task.runningSince
      ? Math.max(0, Math.floor((nowMs - new Date(task.runningSince).getTime()) / 1000))
      : 0;
  const elapsedSeconds = banked + currentRun;

  const estimateSeconds =
    task.estimateMinutes != null && task.estimateMinutes > 0 ? task.estimateMinutes * 60 : null;

  const remainingSeconds =
    estimateSeconds == null ? null : Math.max(0, estimateSeconds - elapsedSeconds);
  const overrunSeconds = estimateSeconds == null ? 0 : Math.max(0, elapsedSeconds - estimateSeconds);
  const overEstimate = estimateSeconds != null && elapsedSeconds > estimateSeconds;

  return {
    elapsedSeconds,
    estimateSeconds,
    remainingSeconds,
    overrunSeconds,
    running,
    finished,
    overEstimate,
    overdueOnCompletion: finished && overEstimate,
    fraction: estimateSeconds == null ? null : Math.min(1, elapsedSeconds / estimateSeconds),
    // A real task always has one: the server starts the clock when the card is created
    // and the migration back-filled the ones that predate it.
    tracked: Boolean(task.runningSince) || banked > 0 || finished,
  };
}

/** `HH:MM:SS`, with hours growing past two digits for long overruns. */
export function formatDuration(totalSeconds: number): string {
  const secs = Math.max(0, Math.floor(totalSeconds));
  const hours = Math.floor(secs / 3600);
  const minutes = Math.floor((secs % 3600) / 60);
  const seconds = secs % 60;
  return [hours, minutes, seconds].map((part) => String(part).padStart(2, "0")).join(":");
}

/** Compact phrasing for a badge, e.g. "2h 15m". Seconds only below a minute. */
export function formatCompact(totalSeconds: number): string {
  const secs = Math.max(0, Math.floor(totalSeconds));
  const hours = Math.floor(secs / 3600);
  const minutes = Math.floor((secs % 3600) / 60);
  if (hours > 0) return `${hours}h ${minutes}m`;
  if (minutes > 0) return `${minutes}m`;
  return `${secs}s`;
}

export type ClockTone = "muted" | "warning" | "danger" | "positive";

/**
 * How urgently the countdown should read.
 *
 * <p>Red once the estimate is gone, amber inside the last quarter of it, quiet
 * otherwise — and green for a task that finished with time to spare, which is the one
 * state worth showing as good news rather than as the absence of bad news.</p>
 */
export function clockTone(clock: TaskClock): ClockTone {
  if (clock.estimateSeconds == null) return "muted";
  if (clock.overEstimate) return "danger";
  if (clock.finished) return "positive";
  return clock.fraction != null && clock.fraction >= 0.75 ? "warning" : "muted";
}

/** The label and value for the timer row, in each of the four states it can be in. */
export function clockLabel(clock: TaskClock): { label: string; value: string } | null {
  if (clock.estimateSeconds == null || !clock.tracked) return null;
  if (clock.finished) {
    return clock.overEstimate
      ? { label: "Overdue by", value: formatDuration(clock.overrunSeconds) }
      : { label: "Finished with", value: `${formatDuration(clock.remainingSeconds ?? 0)} to spare` };
  }
  return clock.overEstimate
    ? { label: "Over by", value: formatDuration(clock.overrunSeconds) }
    : { label: "Time left", value: formatDuration(clock.remainingSeconds ?? 0) };
}
