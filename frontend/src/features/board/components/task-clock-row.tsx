"use client";

import { useEffect, useState } from "react";
import { Pause, RotateCcw } from "lucide-react";
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
