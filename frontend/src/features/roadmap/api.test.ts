import { describe, expect, it } from "vitest";
import { paceLabel, quarterLabel, statusLabel, statusTone, type Initiative } from "./api";

const base: Initiative = {
  id: "3f1d2c6a-0f6e-4a3a-9a51-4d3ad9b1f001",
  title: "Enterprise SSO",
  status: "IN_PROGRESS",
  confidence: "MEDIUM",
  progressPct: 80,
  expectedProgressPct: 44,
  overdue: false,
  position: 0,
  items: [
    {
      id: "3f1d2c6a-0f6e-4a3a-9a51-4d3ad9b1f002",
      entityType: "EPIC",
      entityId: "3f1d2c6a-0f6e-4a3a-9a51-4d3ad9b1f003",
      itemKey: "PAY-1",
      title: "SSO and authentication",
      exists: true,
      totalItems: 4,
      completedItems: 3,
      progressPct: 80,
    },
  ],
};

/**
 * Pace is the sentence a stakeholder actually reads.
 *
 * <p>"80% done" is a fact about the work; whether that is good enough depends on the
 * date, and nobody does that arithmetic in their head — they look at the colour and
 * move on. Which makes it important that the words never contradict the colour.</p>
 */
describe("paceLabel", () => {
  it("says how far ahead or behind the calendar the work is", () => {
    expect(paceLabel(base)).toBe("36 ahead of pace");
    expect(paceLabel({ ...base, progressPct: 12, expectedProgressPct: 66 })).toBe(
      "54 behind pace",
    );
  });

  it("calls a small gap on pace rather than reporting noise", () => {
    // A straight line is a reference, not a prediction. Announcing "3 behind" invites
    // a conversation about nothing.
    expect(paceLabel({ ...base, progressPct: 41, expectedProgressPct: 44 })).toBe("On pace");
  });

  it("says nothing when there is no work linked", () => {
    // The backend refuses to call such an initiative at risk. "15 behind pace" beside
    // "Planned" would contradict that, and the reader believes the accusation.
    expect(paceLabel({ ...base, progressPct: 0, items: [] })).toBeNull();
  });

  it("ignores links whose target was deleted", () => {
    const orphaned = { ...base.items[0], exists: false };
    expect(paceLabel({ ...base, progressPct: 0, items: [orphaned] })).toBeNull();
  });

  it("says nothing when the initiative has no dates to pace against", () => {
    expect(paceLabel({ ...base, expectedProgressPct: null })).toBeNull();
  });
});

describe("status presentation", () => {
  it("reserves green for shipped so in-progress does not read as finished", () => {
    expect(statusTone("SHIPPED")).toBe("green");
    expect(statusTone("IN_PROGRESS")).toBe("accent");
    expect(statusTone("AT_RISK")).toBe("red");
    expect(statusTone("PLANNED")).toBe("neutral");
  });

  it("turns the enum into a sentence", () => {
    expect(statusLabel("IN_PROGRESS")).toBe("In progress");
    expect(statusLabel("AT_RISK")).toBe("At risk");
  });
});

describe("quarterLabel", () => {
  it("reads the way people say it, though it is stored the way it sorts", () => {
    // Stored "2026-Q3" so string sorting orders the columns; shown "Q3 2026".
    expect(quarterLabel("2026-Q3")).toBe("Q3 2026");
    expect(["2027-Q1", "2026-Q4", "2026-Q3"].sort()).toEqual([
      "2026-Q3",
      "2026-Q4",
      "2027-Q1",
    ]);
  });
});
