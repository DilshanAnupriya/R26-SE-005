import { describe, expect, it } from "vitest";
import {
  entryColour,
  entryKindLabel,
  monthKey,
  monthLabel,
  shiftMonth,
  type CalendarEntry,
} from "./api";

const entry = (over: Partial<CalendarEntry> = {}): CalendarEntry => ({
  id: "3f1d2c6a-0f6e-4a3a-9a51-4d3ad9b1f001",
  kind: "STORY_DUE",
  date: "2026-08-20",
  title: "FX rate provider integration",
  overdue: false,
  ...over,
});

/**
 * Month paging.
 *
 * <p>The bug this guards is specific and famous: shifting 31 January by one month
 * with the day intact gives 2 or 3 March, so a calendar paged forward from a 31-day
 * month skips February entirely and nobody can work out why.</p>
 */
describe("shiftMonth", () => {
  it("moves whole months without skipping short ones", () => {
    expect(shiftMonth("2026-01-01", 1)).toBe("2026-02-01");
    expect(shiftMonth("2026-03-01", -1)).toBe("2026-02-01");
  });

  it("crosses a year boundary in both directions", () => {
    expect(shiftMonth("2026-12-01", 1)).toBe("2027-01-01");
    expect(shiftMonth("2026-01-01", -1)).toBe("2025-12-01");
  });

  it("survives a full year of paging without drifting", () => {
    let cursor = "2026-01-01";
    for (let step = 0; step < 12; step++) cursor = shiftMonth(cursor, 1);
    expect(cursor).toBe("2027-01-01");
  });
});

describe("monthKey", () => {
  it("normalises any date in a month to its first, zero-padded", () => {
    expect(monthKey(new Date(2026, 7, 31))).toBe("2026-08-01");
    expect(monthKey(new Date(2026, 0, 5))).toBe("2026-01-01");
  });
});

describe("monthLabel", () => {
  it("reads as a person would say it", () => {
    expect(monthLabel("2026-08-01")).toBe("August 2026");
  });
});

describe("entryColour", () => {
  it("lets overdue outrank the entry's kind", () => {
    // What colour a milestone normally is matters less than the fact that its date
    // has passed and it is still open.
    expect(entryColour(entry({ kind: "MILESTONE", overdue: true }))).toBe("var(--red)");
    expect(entryColour(entry({ kind: "MILESTONE" }))).toBe("var(--violet)");
  });

  it("gives every kind a colour and a label", () => {
    const kinds = [
      "SPRINT_START",
      "SPRINT_END",
      "MILESTONE",
      "STORY_DUE",
      "INITIATIVE_TARGET",
      "GOAL_TARGET",
    ] as const;

    for (const kind of kinds) {
      expect(entryColour(entry({ kind }))).toMatch(/^var\(--/);
      expect(entryKindLabel(kind)).toBeTruthy();
    }
  });
});
