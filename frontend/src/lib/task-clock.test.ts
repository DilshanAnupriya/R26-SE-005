import { describe, expect, it } from "vitest";
import type { Task } from "@/lib/api/delivery-schemas";
import { clockLabel, clockTone, computeTaskClock, formatCompact, formatDuration } from "./task-clock";

const NOW = Date.parse("2026-08-22T12:00:00Z");

function task(overrides: Partial<Task> = {}): Task {
  return {
    id: "00000000-0000-0000-0000-000000000001",
    projectId: "00000000-0000-0000-0000-000000000002",
    itemKey: "ACME-1",
    title: "A task",
    type: "TASK",
    status: "IN_PROGRESS",
    priority: "MEDIUM",
    requiredSkills: [],
    blocked: false,
    reopenedCount: 0,
    elapsedSeconds: 0,
    runningSince: null,
    createdAt: "2026-08-22T10:00:00Z",
    ...overrides,
  } as Task;
}

describe("computeTaskClock", () => {
  it("adds the current run to the banked total", () => {
    const clock = computeTaskClock(
      task({ elapsedSeconds: 600, runningSince: "2026-08-22T11:30:00Z" }),
      NOW,
    );
    // 10 minutes banked plus 30 running.
    expect(clock.elapsedSeconds).toBe(600 + 1800);
    expect(clock.running).toBe(true);
  });

  it("freezes on a finished task even if runningSince was left set", () => {
    // Defensive: the server clears runningSince on completion, but a card rendered from
    // an optimistic update may not have caught up, and a Done card visibly counting up
    // is the exact bug the stored clock exists to prevent.
    const clock = computeTaskClock(
      task({ status: "DONE", elapsedSeconds: 3600, runningSince: "2026-08-22T11:00:00Z" }),
      NOW,
    );
    expect(clock.running).toBe(false);
    expect(clock.finished).toBe(true);
    expect(clock.elapsedSeconds).toBe(3600);
  });

  it("does not count time spent in a finished column", () => {
    const finished = task({ status: "DONE", elapsedSeconds: 3600, runningSince: null });
    const now = computeTaskClock(finished, NOW).elapsedSeconds;
    const aWeekLater = computeTaskClock(finished, NOW + 7 * 24 * 3600 * 1000).elapsedSeconds;
    expect(aWeekLater).toBe(now);
  });

  it("reports no countdown for a task with no estimate", () => {
    const clock = computeTaskClock(task({ elapsedSeconds: 99_999 }), NOW);
    expect(clock.estimateSeconds).toBeNull();
    expect(clock.remainingSeconds).toBeNull();
    expect(clock.overEstimate).toBe(false);
    expect(clockLabel(clock)).toBeNull();
  });

  it("shows no countdown for a row that has no clock", () => {
    // The board renders stories through the task shape. They carry an estimate derived
    // from story points but no clock, and a "Time left" equal to the whole estimate
    // that never moves is a countdown that is visibly a lie.
    const story = task({ estimateMinutes: 480, elapsedSeconds: 0, runningSince: null });
    const clock = computeTaskClock(story, NOW);
    expect(clock.tracked).toBe(false);
    expect(clockLabel(clock)).toBeNull();
  });

  it("counts down inside the estimate", () => {
    const clock = computeTaskClock(task({ estimateMinutes: 60, elapsedSeconds: 900 }), NOW);
    expect(clock.remainingSeconds).toBe(2700);
    expect(clock.overrunSeconds).toBe(0);
    expect(clock.fraction).toBeCloseTo(0.25);
    expect(clockTone(clock)).toBe("muted");
  });

  it("goes amber in the last quarter", () => {
    const clock = computeTaskClock(task({ estimateMinutes: 60, elapsedSeconds: 2760 }), NOW);
    expect(clockTone(clock)).toBe("warning");
  });

  it("reports an overrun once the estimate is gone", () => {
    const clock = computeTaskClock(task({ estimateMinutes: 60, elapsedSeconds: 5400 }), NOW);
    expect(clock.remainingSeconds).toBe(0);
    expect(clock.overrunSeconds).toBe(1800);
    expect(clock.overEstimate).toBe(true);
    expect(clockTone(clock)).toBe("danger");
    expect(clockLabel(clock)).toEqual({ label: "Over by", value: "00:30:00" });
  });

  describe("the overdue badge", () => {
    it("is not shown for a task still in progress past its estimate", () => {
      // It shows a live overrun instead — running late and having finished late are
      // different situations and must not share a badge.
      const clock = computeTaskClock(task({ estimateMinutes: 60, elapsedSeconds: 7200 }), NOW);
      expect(clock.overdueOnCompletion).toBe(false);
    });

    it("is not shown for a task finished inside its estimate", () => {
      const clock = computeTaskClock(
        task({ status: "DONE", estimateMinutes: 60, elapsedSeconds: 1800 }),
        NOW,
      );
      expect(clock.overdueOnCompletion).toBe(false);
      expect(clockTone(clock)).toBe("positive");
      expect(clockLabel(clock)).toEqual({ label: "Finished with", value: "00:30:00 to spare" });
    });

    it("is shown for a task finished after its estimate ran out", () => {
      const clock = computeTaskClock(
        task({ status: "DONE", estimateMinutes: 60, elapsedSeconds: 9000 }),
        NOW,
      );
      expect(clock.overdueOnCompletion).toBe(true);
      expect(clockLabel(clock)).toEqual({ label: "Overdue by", value: "01:30:00" });
    });
  });
});

describe("formatting", () => {
  it("pads to HH:MM:SS", () => {
    expect(formatDuration(0)).toBe("00:00:00");
    expect(formatDuration(61)).toBe("00:01:01");
    expect(formatDuration(3661)).toBe("01:01:01");
  });

  it("lets hours grow past two digits for a long overrun", () => {
    expect(formatDuration(360_000)).toBe("100:00:00");
  });

  it("never renders a negative duration", () => {
    expect(formatDuration(-5)).toBe("00:00:00");
  });

  it("drops the units that would read as zero", () => {
    expect(formatCompact(45)).toBe("45s");
    expect(formatCompact(600)).toBe("10m");
    expect(formatCompact(9000)).toBe("2h 30m");
  });
});
