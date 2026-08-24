"use client";

import { useEffect, useState } from "react";
import { Clock, CornerDownRight, MessageSquare, Pause, RotateCcw } from "lucide-react";
import type { Task } from "@/lib/api/delivery-schemas";
import {
  clockLabel,
  clockTone,
  computeTaskClock,
  formatCompact,
  type TaskClock,
} from "@/lib/task-clock";
import { cn } from "@/lib/utils";

const TONE_CLASS = {
  muted: "text-muted-foreground",
  warning: "text-amber-600 dark:text-amber-400",
  danger: "text-red-600 dark:text-red-400",
  positive: "text-emerald-600 dark:text-emerald-400",
} as const;

/**
 * Ticks once a second, but only while there is something to animate.
 *
 * <p>A frozen clock and an unestimated task both render a value that cannot change, and
 * a board of forty such cards re-rendering every second for nothing is the difference
 * between a board that feels instant and one that does not.</p>
 */
function useTaskClock(task: Task): TaskClock {
  const [now, setNow] = useState(() => Date.now());
  const clock = computeTaskClock(task, now);
  const animate = clock.running && clock.estimateSeconds != null;

  useEffect(() => {
    if (!animate) return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [animate]);

  return clock;
}

/**
 * The countdown line on a board card.
 *
 * <p>Four states, deliberately worded differently rather than shown as one signed
 * number: counting down, over its estimate and still going, finished late, finished
 * with time to spare. "−02:15:00" would technically cover all four and would tell a
 * reader nothing about which of them they are looking at.</p>
 *
 * <p>`onExpired` fires once, the first render on which a still-open task crosses its
 * estimate. That is the moment the sprint's over-budget risk should hear about it, and
 * the board is the only thing in the system that knows it has happened.</p>
 */
export function TaskClockRow({
  task,
  onExpired,
}: {
  task: Task;
  onExpired?: (task: Task) => void;
}) {
  const clock = useTaskClock(task);
  const expired = clock.running && clock.overEstimate;

  useEffect(() => {
    if (expired) {
      onExpired?.(task);
    }
    // Keyed on the task and the crossing, not on the callback: a parent that rebuilds
    // its handler each render would otherwise re-report on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [expired, task.id]);

  const timer = clockLabel(clock);
  if (!timer) {
    return null;
  }
  const tone = clockTone(clock);

  return (
    <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
      <span className="text-[10px] tracking-[0.05em] text-muted-foreground uppercase">
        {timer.label}
      </span>
      <span
        className={cn(
          "font-mono text-[11px] font-semibold tabular-nums",
          TONE_CLASS[tone],
          tone === "danger" && "rounded bg-red-500/10 px-1 py-px",
        )}
      >
        {timer.value}
      </span>
      {clock.finished ? (
        <span
          className="inline-flex items-center gap-1 text-[10px] text-muted-foreground"
          title="The clock froze when this task was finished. Reopening it resumes from here rather than restarting."
        >
          <Pause className="size-2.5" aria-hidden />
          frozen
        </span>
      ) : null}
    </div>
  );
}

/**
 * The overdue badge — a task that was *finished* past its estimate.
 *
 * <p>Only on completion, and never on a task still in progress: that one shows a live
 * overrun instead. Flagging both the same way would mean a card that is running twenty
 * minutes late looked identical to one that shipped three days late, and the badge
 * would stop meaning anything.</p>
 */
export function OverdueBadge({ task }: { task: Task }) {
  const clock = computeTaskClock(task);
  if (!clock.overdueOnCompletion) {
    return null;
  }
  const estimateHours = ((task.estimateMinutes ?? 0) / 60).toFixed(1).replace(/\.0$/, "");
  return (
    <span
      title={`Finished ${formatCompact(clock.overrunSeconds)} after the ${estimateHours}h estimate ran out.`}
      className="inline-flex shrink-0 items-center rounded bg-red-600 px-1.5 py-px text-[9px] font-bold tracking-[0.07em] text-white uppercase"
    >
      Overdue +{formatCompact(clock.overrunSeconds)}
    </span>
  );
}

/**
 * How long a question on this card may wait before it counts as ignored: half the task's
 * own estimate.
 *
 * <p>Relative rather than a fixed hour or day, because the same silence means different
 * things at different scales — two hours of nothing on a one-hour task has already sunk
 * it, and on a three-day task is a normal morning. Null when there is no estimate: there
 * is no half of an unstated duration, and picking one would flag every unestimated card
 * the moment somebody commented on it.</p>
 */
function patienceSeconds(task: Task): number | null {
  const minutes = task.estimateMinutes;
  if (minutes == null || minutes <= 0) {
    return null;
  }
  return Math.max(60, Math.round((minutes * 60) / 2));
}

/**
 * The conversation on a card: comments, replies, and whether anything is still waiting.
 *
 * <p>Counted separately rather than as one total. "3" could be three people raising
 * three different things or one question answered twice, and those are opposite readings
 * of how well a team is talking.</p>
 *
 * <p>`onUnanswered` fires on the render where the oldest unanswered comment crosses half
 * the estimate — the moment the sprint's collaboration risk should hear about it, and one
 * only the card can see, since it is the only place that holds both the estimate and the
 * age of the question.</p>
 */
export function CommentBadge({
  task,
  onUnanswered,
}: {
  task: Task;
  onUnanswered?: (task: Task) => void;
}) {
  const comments = task.commentCount ?? 0;
  const replies = task.replyCount ?? 0;
  const patience = patienceSeconds(task);

  const askedAtMs = task.oldestUnansweredCommentAt
    ? new Date(task.oldestUnansweredCommentAt).getTime()
    : null;
  const waitingSince = askedAtMs != null && !Number.isNaN(askedAtMs) ? askedAtMs : null;

  // Ticks only while there is a pending question with a deadline to cross. A board of
  // answered cards re-rendering every second buys nothing.
  const [now, setNow] = useState(() => Date.now());
  const watching = waitingSince != null && patience != null;
  useEffect(() => {
    if (!watching) return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [watching]);

  const waitedSeconds = waitingSince == null ? 0 : Math.max(0, Math.floor((now - waitingSince) / 1000));
  const overdue = watching && patience != null && waitedSeconds >= patience;

  useEffect(() => {
    if (overdue) {
      onUnanswered?.(task);
    }
    // Keyed on the task and the crossing, not the callback: a parent that rebuilds its
    // handler every render would otherwise re-report on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [overdue, task.id]);

  if (comments <= 0) {
    return null;
  }

  return (
    <span className="inline-flex items-center gap-1">
      <span
        title={`${comments} comment${comments === 1 ? "" : "s"}, ${replies} ${replies === 1 ? "reply" : "replies"}`}
        className="inline-flex items-center gap-0.5 rounded border border-border px-1 py-px text-[10px] font-medium text-muted-foreground"
      >
        <MessageSquare className="size-2.5" aria-hidden />
        {comments}
        {replies > 0 ? (
          <>
            <CornerDownRight className="ml-0.5 size-2.5" aria-hidden />
            {replies}
          </>
        ) : null}
      </span>

      {overdue ? (
        <span
          title={`A comment has waited ${formatCompact(waitedSeconds)} with no reply — longer than half this task's estimate. Counts towards the sprint's collaboration risk.`}
          className="inline-flex items-center gap-0.5 rounded border border-amber-500/40 bg-amber-500/10 px-1 py-px text-[10px] font-semibold text-amber-700 dark:text-amber-400"
        >
          <Clock className="size-2.5" aria-hidden />
          waiting {formatCompact(waitedSeconds)}
        </span>
      ) : null}
    </span>
  );
}

/** How many times a card has come back. Hidden at zero — most cards never do. */
export function ReopenBadge({ count }: { count: number }) {
  if (count <= 0) {
    return null;
  }
  return (
    <span
      title={`Reopened ${count} time${count === 1 ? "" : "s"} — counts towards the sprint's collaboration risk`}
      className="inline-flex items-center gap-0.5 rounded border border-amber-500/40 bg-amber-500/10 px-1 py-px text-[10px] font-semibold text-amber-700 dark:text-amber-400"
    >
      <RotateCcw className="size-2.5" aria-hidden />
      {count}
    </span>
  );
}
