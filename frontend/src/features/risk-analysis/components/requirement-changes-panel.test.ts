import { describe, expect, it } from "vitest";
import { measuredLength, parseCriteria, priorityLevelsMoved } from "./requirement-changes-panel";

/** One criterion per non-blank line — the count the server charges from. */
describe("parseCriteria", () => {
  it("splits on lines and drops blanks, as the server counts", () => {
    expect(parseCriteria("Admins can require SSO\n\n  Members are signed out  \r\nAPI tokens need SSO")).toEqual([
      "Admins can require SSO",
      "Members are signed out",
      "API tokens need SSO",
    ]);
  });

  it("has nothing for a story without criteria", () => {
    expect(parseCriteria(null)).toEqual([]);
    expect(parseCriteria("  \n ")).toEqual([]);
  });
});

/** Signed levels — the charge is the per-level weight times this. */
describe("priorityLevelsMoved", () => {
  it("counts a raise as positive, more for a bigger jump", () => {
    expect(priorityLevelsMoved("LOW", "MEDIUM")).toBe(1);
    expect(priorityLevelsMoved("LOW", "HIGH")).toBe(2);
    expect(priorityLevelsMoved("MEDIUM", "HIGH")).toBe(1);
  });

  it("counts a drop as negative, more for a bigger drop", () => {
    expect(priorityLevelsMoved("MEDIUM", "LOW")).toBe(-1);
    expect(priorityLevelsMoved("HIGH", "MEDIUM")).toBe(-1);
    expect(priorityLevelsMoved("HIGH", "LOW")).toBe(-2);
  });

  it("reads an unset priority as Medium, as the server does", () => {
    expect(priorityLevelsMoved(null, "HIGH")).toBe(1);
    expect(priorityLevelsMoved(undefined, "MEDIUM")).toBe(0);
  });
});

/**
 * The form's preview has to agree with `RequirementChangeImpact.lengthOf` on the server,
 * or it would promise a charge for an edit the server then calls a cut.
 */
describe("measuredLength", () => {
  it("counts nothing for a missing or blank description", () => {
    expect(measuredLength(null)).toBe(0);
    expect(measuredLength(undefined)).toBe(0);
    expect(measuredLength("   \n\t ")).toBe(0);
  });

  it("collapses runs of whitespace and trims the ends, as the server does", () => {
    expect(measuredLength("  Admins   can\n\nrequire SSO  ")).toBe("Admins can require SSO".length);
  });

  it("so reflowing the same words is not a change", () => {
    expect(measuredLength("one two three")).toBe(measuredLength("one\n  two\tthree"));
  });
});
