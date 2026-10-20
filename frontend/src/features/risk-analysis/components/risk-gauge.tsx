"use client";

import { useId } from "react";
import { cn } from "@/lib/utils";

export type RiskSeverityLabel = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";

/** One colour per band, so the gauge, the badge and the bar cannot disagree. */
export const SEVERITY_COLOUR: Record<RiskSeverityLabel, string> = {
  LOW: "var(--green, oklch(0.65 0.17 150))",
  MEDIUM: "var(--amber, oklch(0.75 0.16 75))",
  HIGH: "oklch(0.68 0.19 45)",
  CRITICAL: "var(--red, oklch(0.62 0.22 25))",
};

export function severityFor(score: number | null | undefined): RiskSeverityLabel {
  const value = score ?? 0;
  if (value >= 0.8) return "CRITICAL";
  if (value >= 0.6) return "HIGH";
  if (value >= 0.35) return "MEDIUM";
  return "LOW";
}

/** Scores are 0–1 probabilities; every reader thinks in percent. */
export function asPercent(score: number | null | undefined): number | null {
  return score == null ? null : Math.round(score * 100);
}

/**
 * A model's own figure as text, keeping "barely any" apart from "none".
 *
 * <p>A fine-tuned classifier that is sure a sprint is Low gives High a fraction of a
 * percent. Rounded, that reads "0%", which looks like the model never answered.
 * Anything above nought that rounds away is written "<1" instead.</p>
 */
export function modelPercentLabel(score: number | null | undefined): string {
  if (score == null) return "—";
  return score > 0 && score < 0.005 ? "<1" : String(Math.round(score * 100));
}

/**
 * A three-quarter arc, drawn as SVG.
 *
 * <p>Hand-drawn for the same reason the rest of the charts here are: it is one fixed
 * shape, and a gauge library would add a dependency to every page that shows a risk
 * score in exchange for configurability nothing needs.</p>
 *
 * <p>The arc reads as a dial, which is the point — a bare percentage invites the reader
 * to treat two decimal places as precision the model does not have, whereas a needle
 * three-fifths of the way round says "high" without pretending to say how high.</p>
 */
export function RiskGauge({
  score,
  size = 120,
  label,
  className,
}: {
  /** 0–1, or null when the model has not answered. */
  score: number | null | undefined;
  size?: number;
  label?: string;
  className?: string;
}) {
  const titleId = useId();
  const scored = score != null;
  const value = Math.max(0, Math.min(1, score ?? 0));
  const severity = severityFor(score);

  const stroke = Math.max(7, Math.round(size * 0.09));
  const radius = (size - stroke) / 2;
  const centre = size / 2;
  // Three quarters of a circle, opening at the bottom — the shape a dial has.
  const sweep = 0.75;
  const circumference = 2 * Math.PI * radius;
  const track = circumference * sweep;
  const filled = track * value;

  return (
    <figure className={cn("inline-flex flex-col items-center gap-1", className)}>
      <svg
        width={size}
        height={size}
        viewBox={`0 0 ${size} ${size}`}
        role="img"
        aria-labelledby={titleId}
        className="-rotate-[225deg]"
      >
        <title id={titleId}>
          {label ? `${label}: ` : ""}
          {scored ? `${asPercent(score)} percent, ${severity.toLowerCase()} risk` : "not scored yet"}
        </title>
        <circle
          cx={centre}
          cy={centre}
          r={radius}
          fill="none"
          stroke="var(--surface-3, oklch(0.92 0.01 260))"
          strokeWidth={stroke}
          strokeLinecap="round"
          strokeDasharray={`${track} ${circumference}`}
        />
        {scored ? (
          <circle
            cx={centre}
            cy={centre}
            r={radius}
            fill="none"
            stroke={SEVERITY_COLOUR[severity]}
            strokeWidth={stroke}
            strokeLinecap="round"
            strokeDasharray={`${filled} ${circumference}`}
            className="transition-[stroke-dasharray] duration-500 ease-out"
          />
        ) : null}
      </svg>

      <figcaption
        className="pointer-events-none -mt-[62%] flex flex-col items-center"
        style={{ height: size * 0.4 }}
      >
        <span
          className="font-semibold tabular-nums"
          style={{ fontSize: size * 0.24, color: scored ? SEVERITY_COLOUR[severity] : undefined }}
        >
          {scored ? asPercent(score) : "—"}
        </span>
        {label ? (
          <span className="mt-0.5 text-center text-[11px] leading-tight text-ink-3">{label}</span>
        ) : null}
      </figcaption>
    </figure>
  );
}

/** The severity badge. Falls back to a neutral "Not scored" rather than to LOW. */
export function SeverityBadge({
  severity,
  scored = true,
}: {
  severity: string | null | undefined;
  scored?: boolean;
}) {
  if (!scored || !severity) {
    return (
      <span className="rounded-full bg-surface-3 px-2.5 py-1 text-xs font-semibold text-ink-3">
        Not scored
      </span>
    );
  }
  const key = severity.toUpperCase() as RiskSeverityLabel;
  return (
    <span
      className="rounded-full px-2.5 py-1 text-xs font-semibold text-white"
      style={{ background: SEVERITY_COLOUR[key] ?? SEVERITY_COLOUR.LOW }}
    >
      {key.charAt(0) + key.slice(1).toLowerCase()}
    </span>
  );
}
