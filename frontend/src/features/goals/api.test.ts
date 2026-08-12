import { describe, expect, it } from "vitest";
import { formatMetric, keyResultSchema, statusLabel, statusTone } from "./api";

/**
 * How a key result's number is rendered.
 *
 * <p>The metric type is the only thing that says what the digits mean, and getting it
 * wrong produces text that is confidently wrong rather than obviously broken —
 * "1 / 1" for a thing that shipped, "40 / 10" reading as a shortfall when 40 was the
 * starting point of a countdown.</p>
 */
describe("formatMetric", () => {
  it("renders a boolean as an outcome, not as a number", () => {
    expect(formatMetric(1, "BOOLEAN")).toBe("Done");
    expect(formatMetric(0, "BOOLEAN")).toBe("Not yet");
  });

  it("puts the sign on a percent and the unit after a number", () => {
    expect(formatMetric(60, "PERCENT")).toBe("60%");
    expect(formatMetric(18, "NUMBER", "min")).toBe("18 min");
    expect(formatMetric(18, "NUMBER")).toBe("18");
  });

  it("keeps whole numbers whole and trims the rest to one place", () => {
    // 42.857142… as a target reads as noise; 42 should not become 42.0.
    expect(formatMetric(42, "NUMBER")).toBe("42");
    expect(formatMetric(42.857142, "NUMBER")).toBe("42.9");
  });
});

describe("status presentation", () => {
  it("gives every status a tone", () => {
    const statuses = [
      "DRAFT",
      "ON_TRACK",
      "AT_RISK",
      "OFF_TRACK",
      "ACHIEVED",
      "MISSED",
    ] as const;
    for (const status of statuses) {
      expect(statusTone(status)).toBeTruthy();
    }
  });

  it("reserves green for achieved so on-track does not read as finished", () => {
    expect(statusTone("ACHIEVED")).toBe("green");
    expect(statusTone("ON_TRACK")).toBe("accent");
    expect(statusTone("MISSED")).toBe("red");
  });

  it("turns the enum into a sentence", () => {
    expect(statusLabel("OFF_TRACK")).toBe("Off track");
    expect(statusLabel("ON_TRACK")).toBe("On track");
  });
});

describe("key result schema", () => {
  const base = {
    id: "3f1d2c6a-0f6e-4a3a-9a51-4d3ad9b1f001",
    goalId: "3f1d2c6a-0f6e-4a3a-9a51-4d3ad9b1f002",
    title: "Activation rate",
    metricType: "PERCENT",
    startValue: 31,
    currentValue: 40,
    targetValue: 60,
    direction: "INCREASE",
    status: "ON_TRACK",
    progressPct: 31.03,
    achieved: false,
    position: 0,
  };

  it("accepts a key result with no owner, unit or links", () => {
    // The backend omits these rather than sending nulls for an unowned result, and a
    // schema that insisted on them would blank the whole screen over a missing name.
    const parsed = keyResultSchema.parse(base);
    expect(parsed.linkedItems).toEqual([]);
    expect(parsed.unit ?? null).toBeNull();
  });

  it("rejects a payload whose progress is missing", () => {
    const without: Record<string, unknown> = { ...base };
    delete without.progressPct;
    expect(() => keyResultSchema.parse(without)).toThrow();
  });
});
