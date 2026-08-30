import { describe, expect, it } from "vitest";
import type { OverallRisk } from "@/lib/api/ai-schemas";
import { pointsToHigh, riskPercent, sprintRiskTone } from "./sprint-risk-bands";

function overall(score: number | null, level: OverallRisk["level"], degraded = false): OverallRisk {
  return { score: score as number, level, degraded, weights: {}, rationale: null };
}

/**
 * The four card states, at the boundaries that decide them.
 *
 * <p>Worth a test rather than a look at the screen: the seeded database has one
 * assessed sprint, so three of these four states cannot be produced by clicking
 * around, and the one rule anybody would get wrong — 57 is "near high", 56 is not —
 * is exactly the one no fixture exercises.</p>
 */
describe("sprint risk tone", () => {
  it("bands a comfortable sprint green", () => {
    expect(sprintRiskTone(overall(0.12, "LOW"))).toBe("low");
    expect(sprintRiskTone(overall(0.34, "LOW"))).toBe("low");
  });

  it("bands a mid-range sprint yellow", () => {
    expect(sprintRiskTone(overall(0.35, "MEDIUM"))).toBe("medium");
    expect(sprintRiskTone(overall(0.5, "MEDIUM"))).toBe("medium");
  });

  it("turns a medium sprint red once it is within three points of high", () => {
    // 57, 58 and 59 are the whole of the alarming band: at 60 the server says HIGH.
    expect(sprintRiskTone(overall(0.57, "MEDIUM"))).toBe("near-high");
    expect(sprintRiskTone(overall(0.58, "MEDIUM"))).toBe("near-high");
    expect(sprintRiskTone(overall(0.599, "MEDIUM"))).toBe("near-high");
  });

  it("leaves a medium sprint yellow four points out", () => {
    expect(sprintRiskTone(overall(0.56, "MEDIUM"))).toBe("medium");
  });

  it("decides on the same rounded figure the card prints", () => {
    // The point of rounding once. 0.565 is 56.4999… in binary floating point, so it
    // prints 56% — and a card reading "56%" must not be in a band written about 57.
    expect(riskPercent(0.565)).toBe(56);
    expect(sprintRiskTone(overall(0.565, "MEDIUM"))).toBe("medium");
    // 0.567 does reach 57 and flips both the printed figure and the band together.
    expect(riskPercent(0.567)).toBe(57);
    expect(sprintRiskTone(overall(0.567, "MEDIUM"))).toBe("near-high");
  });

  it("bands an already-high sprint as high, which does not blink", () => {
    expect(sprintRiskTone(overall(0.6, "HIGH"))).toBe("high");
    expect(sprintRiskTone(overall(0.74, "HIGH"))).toBe("high");
    expect(sprintRiskTone(overall(0.91, "CRITICAL"))).toBe("high");
  });

  it("follows the server's bumped level rather than re-banding the score", () => {
    // Incomplete evidence bumps the band up one on the server. A 58% figure the models
    // could not fully score is already presented as HIGH everywhere else, so it gets the
    // steady red card, not the blinking one that means "about to tip over".
    expect(sprintRiskTone(overall(0.58, "HIGH", true))).toBe("high");
    // ...and a bumped LOW is an ordinary medium, not a near-high one.
    expect(sprintRiskTone(overall(0.2, "MEDIUM", true))).toBe("medium");
  });

  it("says so when a sprint was never assessed", () => {
    expect(sprintRiskTone(null)).toBe("unscored");
    expect(sprintRiskTone(undefined)).toBe("unscored");
    expect(sprintRiskTone(overall(null, "LOW"))).toBe("unscored");
  });

  it("reports the gap to the high band, and nothing once it is crossed", () => {
    expect(pointsToHigh(overall(0.57, "MEDIUM"))).toBe(3);
    expect(pointsToHigh(overall(0.42, "MEDIUM"))).toBe(18);
    expect(pointsToHigh(overall(0.6, "HIGH"))).toBeNull();
    expect(pointsToHigh(overall(0.83, "CRITICAL"))).toBeNull();
  });
});
