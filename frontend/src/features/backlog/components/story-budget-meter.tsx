"use client";

import { AlertTriangle, Clock } from "lucide-react";
import type { StoryTaskBudget } from "@/lib/api/delivery-schemas";
import { cn } from "@/lib/utils";

/** "90" reads worse than "1h 30m" for a number people are about to reason with. */
export function formatMinutes(minutes: number): string {
  const sign = minutes < 0 ? "-" : "";
  const total = Math.abs(Math.round(minutes));
  const hours = Math.floor(total / 60);
  const rest = total % 60;
  if (hours === 0) return `${sign}${rest}m`;
  return rest === 0 ? `${sign}${hours}h` : `${sign}${hours}h ${rest}m`;
}

/**
 * What is left of a story's estimated time, shown where a task is about to spend it.
 *
 * <p>The point is the order of events. A planner types an estimate and only afterwards
 * discovers the breakdown has outgrown the story — by which time the sprint has already
 * been charged for it. Showing the remainder first, and what this particular task would
 * do to it, moves the decision to before the mistake.</p>
 *
 * <p>An unsized story renders as an invitation to size it, not as a full or empty bar.
 * A story with no points has no budget, and drawing one would be inventing a number.</p>
 */
export function StoryBudgetMeter({
  budget,
  pendingMinutes,
  className,
}: {
  budget: StoryTaskBudget;
  /** The estimate being typed right now, if any — folded in as a preview. */
  pendingMinutes?: number | null;
  className?: string;
}) {
  if (budget.budgetMinutes == null || budget.budgetMinutes <= 0) {
    return (
      <p className={cn("text-2xs text-muted-foreground", className)}>
        {budget.itemKey} has no estimate yet, so there is no time budget to spend against.
        Give the story points to track this.
      </p>
    );
  }

  const pending = pendingMinutes && pendingMinutes > 0 ? pendingMinutes : 0;
  const consumed = budget.consumedMinutes + pending;
  const remaining = budget.budgetMinutes - consumed;
  const over = remaining < 0;

  // Clamped so an overrun does not draw a bar wider than its track; the number beside
  // it carries the magnitude, which a bar past 100% cannot.
  const filled = Math.min(100, (consumed / budget.budgetMinutes) * 100);

  return (
    <div className={cn("space-y-1", className)}>
      <div className="flex items-center justify-between gap-2 text-2xs">
        <span className="flex items-center gap-1 text-muted-foreground">
          <Clock className="size-3" aria-hidden />
          {budget.itemKey} · {budget.storyPoints} pts = {formatMinutes(budget.budgetMinutes)}
        </span>
        <span
          className={cn("font-medium tabular-nums", over ? "text-red" : "text-muted-foreground")}
        >
          {over
            ? `${formatMinutes(Math.abs(remaining))} over`
            : `${formatMinutes(remaining)} left`}
        </span>
      </div>

      <div
        className="h-1.5 overflow-hidden rounded-full bg-muted"
        role="meter"
        aria-valuenow={Math.round(consumed)}
        aria-valuemin={0}
        aria-valuemax={budget.budgetMinutes}
        aria-label={`${budget.itemKey} task budget: ${formatMinutes(consumed)} of ${formatMinutes(budget.budgetMinutes)} spent`}
      >
        <div
          className={cn("h-full rounded-full transition-[width]", over ? "bg-red" : "bg-primary")}
          style={{ width: `${filled}%` }}
        />
      </div>

      {over ? (
        <p className="flex items-start gap-1 text-2xs text-red">
          <AlertTriangle className="mt-0.5 size-3 shrink-0" aria-hidden />
          <span>
            This breakdown is {formatMinutes(Math.abs(remaining))} more than the story&rsquo;s
            estimate allows. You can still save it — the sprint&rsquo;s over-budget risk will
            rise until the story is re-pointed or a task re-estimated.
          </span>
        </p>
      ) : null}

      {budget.unestimatedTasks > 0 ? (
        <p className="text-2xs text-muted-foreground">
          {budget.unestimatedTasks} task{budget.unestimatedTasks === 1 ? "" : "s"} under this
          story {budget.unestimatedTasks === 1 ? "has" : "have"} no estimate, so the remainder
          is a ceiling rather than a measurement.
        </p>
      ) : null}
    </div>
  );
}
