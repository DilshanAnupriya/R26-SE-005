"use client";

import { useNowSeconds } from "@/lib/client-state";

/**
 * The header's running time-tracking clock.
 *
 * <p>Elapsed time is derived from a start timestamp rather than accumulated by the
 * interval. An interval that ticks a counter drifts — it is throttled in background
 * tabs and skipped when the machine sleeps — so a timer left running over lunch would
 * report an hour less than it should. Recomputing from the start instant is immune to
 * both.</p>
 *
 * <p>Phase 2 built the backend for this: a persisted TimeEntry with startedAt and a
 * null stoppedAt is a running timer. Wiring the display to that endpoint is a small
 * follow-up; the shape here already matches it.</p>
 */
export function WorkTimer({ startedAt }: { startedAt?: Date | null }) {
  const now = useNowSeconds();

  // Derived, not stored. Elapsed time is a function of two instants, and keeping a
  // copy in state only creates a second thing that can be wrong.
  const elapsed =
    startedAt && now !== null ? Math.max(0, now * 1000 - startedAt.getTime()) : null;
  const running = elapsed !== null;

  return (
    <div
      className="flex h-8 items-center gap-[7px] rounded-md border border-line bg-surface-2 px-2.5 font-mono text-sm font-medium"
      title={running ? "Timer running" : "No timer running"}
    >
      <span
        className={running ? "size-2 rounded-full bg-green shadow-[0_0_0_3px_var(--green-soft)]" : "size-2 rounded-full bg-ink-3"}
        aria-hidden
      />
      {/* tabular-nums so the digits do not jitter as the seconds change. */}
      <span className="tabular-nums">{elapsed === null ? "00:00:00" : format(elapsed)}</span>
      <span className="sr-only">{running ? "Timer running" : "No timer running"}</span>
    </div>
  );
}

function format(ms: number): string {
  const total = Math.floor(ms / 1000);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  return [hours, minutes, seconds].map((part) => String(part).padStart(2, "0")).join(":");
}
