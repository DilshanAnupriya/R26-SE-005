import { describe, expect, it } from "vitest";
import { statusForColumn } from "./api";

/**
 * A column move has to send a status, and the API rejects the request without one.
 *
 * <p>The two are not interchangeable: a project may have several columns in the same
 * category, so the workflow state says *where* the card is and the status says *what
 * that means*. Getting the mapping wrong is a 422 on every drag.</p>
 */
describe("column to task status", () => {
  it("uses the column code when it names a real status", () => {
    // The default IN_REVIEW column would otherwise flatten to its IN_PROGRESS category,
    // losing the distinction the board exists to show.
    expect(statusForColumn({ code: "IN_REVIEW", category: "IN_PROGRESS" })).toBe("IN_REVIEW");
    expect(statusForColumn({ code: "TODO", category: "TODO" })).toBe("TODO");
    expect(statusForColumn({ code: "DONE", category: "DONE" })).toBe("DONE");
  });

  it("falls back to the category for a custom column", () => {
    // "Awaiting QA" is not a TaskStatus; its category always is.
    expect(statusForColumn({ code: "AWAITING_QA", category: "IN_PROGRESS" })).toBe("IN_PROGRESS");
    expect(statusForColumn({ code: "ICEBOX", category: "TODO" })).toBe("TODO");
  });

  it("is case-insensitive about the code", () => {
    expect(statusForColumn({ code: "in_progress", category: "IN_PROGRESS" })).toBe("IN_PROGRESS");
  });

  it("always returns something the API accepts", () => {
    const accepted = ["TODO", "IN_PROGRESS", "IN_REVIEW", "BLOCKED", "DONE", "CANCELLED"];
    const columns = [
      { code: "TODO", category: "TODO" },
      { code: "Doing", category: "IN_PROGRESS" },
      { code: "Shipped", category: "DONE" },
      { code: "Dropped", category: "CANCELLED" },
    ];

    for (const column of columns) {
      expect(accepted).toContain(statusForColumn(column));
    }
  });
});
