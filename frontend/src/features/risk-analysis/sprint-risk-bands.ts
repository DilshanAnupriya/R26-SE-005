import type { OverallRisk, RiskSeverity } from "@/lib/api/ai-schemas";
import { severityFor } from "./components/risk-gauge";

/**
 * How a sprint's overall risk should be coloured on a card.
 *
 * <p>Four presentation states over three severity bands. The extra one is
 * {@link RiskTone `"near-high"`}: a sprint still labelled MEDIUM whose figure has
 * climbed to within a few points of the HIGH boundary. It is the only state that
 * moves, because it is the only one describing something about to change — a sprint
 * already HIGH is bad news that has finished happening, and one comfortably MEDIUM
 * is not news at all.</p>
 */

/**
 * The score at which the server bands a sprint HIGH, as a percentage.
 *
 * <p>Mirrors `RiskSeverity.forScore` on the backend, which is the only place the
 * bands are decided. It is repeated here as a number to measure *distance from* —
 * the banding itself is never redone on the client, it is read off `level`.</p>
 */
export const HIGH_BAND_PCT = 60;

/** How close to {@link HIGH_BAND_PCT} counts as "about to tip over". */
export const NEAR_HIGH_MARGIN_PCT = 3;

export type RiskTone = "unscored" | "low" | "medium" | "near-high" | "high";

/**
 * Which of the four states a sprint's overall figure falls into.
 *
 * <p>Severity is taken from the server's `level` rather than re-derived from `score`,
 * and the difference is not cosmetic: an overall assembled from incomplete evidence is
 * deliberately bumped one band up by `RiskService.aggregateScores`. Re-banding the raw
 * score here would quietly undo that bump and put a yellow card next to the red badge
 * the same sprint shows everywhere else in the app.</p>
 *
 * <p>`score` is therefore used for one thing only — measuring how far a MEDIUM sprint
 * is from the HIGH boundary — which is a question `level` cannot answer.</p>
 */
export function sprintRiskTone(overall: OverallRisk | null | undefined): RiskTone {
  if (!overall || overall.score == null) {
    return "unscored";
  }

  // `level` is optional on the wire only in the sense that older payloads may omit it;
  // banding the score is the same answer whenever it is present and unbumped.
  const level: RiskSeverity = overall.level ?? severityFor(overall.score);

  if (level === "HIGH" || level === "CRITICAL") {
    return "high";
  }
  if (level === "LOW") {
    return "low";
  }

  // MEDIUM. A bumped LOW lands here too and is treated as any other MEDIUM — the bump
  // says "trust this less", not "this is about to become HIGH".
  return riskPercent(overall.score) >= HIGH_BAND_PCT - NEAR_HIGH_MARGIN_PCT
    ? "near-high"
    : "medium";
}

/**
 * A 0–1 probability as whole percent.
 *
 * <p>Rounded once, here, because the same number is both shown to the reader and
 * compared against the margin. Comparing the unrounded score while displaying the
 * rounded one puts a card that reads "57%" outside a rule written about 57.</p>
 */
export function riskPercent(score: number | null | undefined): number {
  return score == null ? 0 : Math.round(score * 100);
}

/** How many points a MEDIUM sprint has left before it bands HIGH. Null once it has. */
export function pointsToHigh(overall: OverallRisk | null | undefined): number | null {
  if (!overall || overall.score == null) return null;
  const gap = HIGH_BAND_PCT - riskPercent(overall.score);
  return gap > 0 ? gap : null;
}

/** What each state is called, and the token that colours it. */
export const TONE_META: Record<
  RiskTone,
  { label: string; accent: string; soft: string; pill: "green" | "amber" | "red" | "neutral" }
> = {
  unscored: {
    label: "Not assessed",
    accent: "var(--ink-3)",
    soft: "var(--surface-3)",
    pill: "neutral",
  },
  low: { label: "Low risk", accent: "var(--risk-low)", soft: "var(--green-soft)", pill: "green" },
  medium: {
    label: "Medium risk",
    accent: "var(--risk-medium)",
    soft: "var(--amber-soft)",
    pill: "amber",
  },
  "near-high": {
    label: "Nearly high",
    accent: "var(--risk-high)",
    soft: "var(--red-soft)",
    pill: "red",
  },
  high: { label: "High risk", accent: "var(--risk-high)", soft: "var(--red-soft)", pill: "red" },
};
