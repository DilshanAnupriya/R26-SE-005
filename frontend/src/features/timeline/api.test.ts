import { describe, expect, it } from "vitest";
import {
  barGeometry,
  dateSourceNote,
  daysBetween,
  isInferredSpan,
  todayOffset,
  type Bar,
} from "./api";

const bar = (start: string, end: string): { start: string; end: string } => ({ start, end });

/**
 * Bar placement.
 *
 * <p>An off-by-one here draws every bar a day wide of where it belongs, and nobody
 * notices until somebody reads a deadline off the chart. The inclusive end is the
 * trap: a story due on the 20th occupies the 20th, so its span is one day, not zero.</p>
 */
describe("barGeometry", () => {
  const from = "2026-08-03";
  const to = "2026-08-30"; // 28 days inclusive

  it("places a bar at its offset with an inclusive span", () => {
    const geometry = barGeometry(bar("2026-08-10", "2026-08-16"), from, to);

    expect(geometry.left).toBe(`${(7 / 28) * 100}%`);
    expect(geometry.width).toBe(`${(7 / 28) * 100}%`);
  });

  it("gives a single-day item a full day of width, not zero", () => {
    // A due-date marker would otherwise be a zero-width sliver and invisible.
    const geometry = barGeometry(bar("2026-08-10", "2026-08-10"), from, to);
    expect(geometry.width).toBe(`${(1 / 28) * 100}%`);
  });

  it("starts at the left edge for a bar that began before the window", () => {
    const geometry = barGeometry(bar("2026-07-01", "2026-08-10"), from, to);
    expect(geometry.left).toBe("0%");
  });

  it("clamps a bar that runs past the window to its edge", () => {
    // Otherwise the row stretches and the whole chart scrolls sideways.
    const geometry = barGeometry(bar("2026-08-24", "2026-12-31"), from, to);
    const left = parseFloat(geometry.left);
    const width = parseFloat(geometry.width);

    expect(left + width).toBeLessThanOrEqual(100.001);
  });
});

describe("daysBetween", () => {
  it("counts calendar days without a clock to get wrong", () => {
    expect(daysBetween("2026-08-01", "2026-08-08")).toBe(7);
    expect(daysBetween("2026-08-01", "2026-08-01")).toBe(0);
  });

  it("crosses a month and a leap day correctly", () => {
    expect(daysBetween("2026-08-30", "2026-09-02")).toBe(3);
    expect(daysBetween("2028-02-28", "2028-03-01")).toBe(2);
  });

  it("is unaffected by daylight saving, because these are dates not instants", () => {
    // Parsing these as local Date objects would give 0.958… days across a DST
    // boundary, and Math.round would hide it until the one case where it does not.
    expect(daysBetween("2026-03-28", "2026-03-30")).toBe(2);
    expect(daysBetween("2026-10-24", "2026-10-26")).toBe(2);
  });
});

describe("todayOffset", () => {
  it("is null when today falls outside the window", () => {
    expect(todayOffset("2026-01-01", "2026-08-03", "2026-08-30")).toBeNull();
    expect(todayOffset("2027-01-01", "2026-08-03", "2026-08-30")).toBeNull();
  });

  it("places the now-line inside the window", () => {
    expect(todayOffset("2026-08-10", "2026-08-03", "2026-08-30")).toBe(`${(7 / 28) * 100}%`);
  });
});

describe("dateSourceNote", () => {
  const base: Bar = {
    title: "OAuth2 login",
    kind: "STORY",
    start: "2026-08-10",
    end: "2026-08-24",
    dateSource: "EXPLICIT",
    blocked: false,
    overdue: false,
  };

  it("says nothing when the dates are the item's own", () => {
    expect(dateSourceNote(base)).toBeNull();
  });

  it("explains every inherited case, so a guess is never read as a commitment", () => {
    expect(dateSourceNote({ ...base, dateSource: "SPRINT" })).toMatch(/sprint/i);
    expect(dateSourceNote({ ...base, dateSource: "DUE_DATE_ONLY" })).toMatch(/due date/i);
    expect(dateSourceNote({ ...base, dateSource: "DERIVED" })).toMatch(/inferred/i);
  });

  it("draws only a truly inferred span as uncertain", () => {
    // A sprint window is as real as an explicit one — somebody agreed to it in
    // planning. Dashing it would cast doubt on a commitment, and a plan whose firm
    // dates look tentative gets ignored just as fast as one that overstates them.
    expect(isInferredSpan({ ...base, dateSource: "SPRINT" })).toBe(false);
    expect(isInferredSpan({ ...base, dateSource: "EXPLICIT" })).toBe(false);
    expect(isInferredSpan({ ...base, dateSource: "DERIVED" })).toBe(true);
  });
});
