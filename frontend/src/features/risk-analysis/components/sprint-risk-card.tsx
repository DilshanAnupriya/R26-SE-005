"use client";

import { AlertTriangle, CircleHelp, ShieldCheck, TrendingUp } from "lucide-react";
import type { SprintRisk } from "@/lib/api/ai-schemas";
import type { Sprint } from "@/lib/api/delivery-schemas";
import { cn } from "@/lib/utils";
import {
  HIGH_BAND_PCT,
  TONE_META,
  pointsToHigh,
  riskPercent,
  sprintRiskTone,
  type RiskTone,
} from "../sprint-risk-bands";

const TONE_ICON: Record<RiskTone, typeof AlertTriangle> = {
  unscored: CircleHelp,
  low: ShieldCheck,
  medium: AlertTriangle,
  "near-high": TrendingUp,
  high: AlertTriangle,
};

const STATUS_LABEL: Record<string, string> = {
  PLANNED: "Planned",
  ACTIVE: "Active",
  COMPLETED: "Completed",
  CANCELLED: "Cancelled",
};

/**
 * One sprint's overall risk, coloured by band.
 *
 * <p>The colour is never the only carrier: each card also names its band in words and
 * prints the figure. A board that says "this one is about to become a problem" only in
 * red would say nothing at all to a red-green colourblind reader, who is roughly one man
 * in twelve on any given team.</p>
 */
export function SprintRiskCard({
  sprint,
  risk,
  isLoading,
}: {
  sprint: Sprint;
  risk: SprintRisk | undefined;
  isLoading: boolean;
}) {
  const overall = risk?.overall;
  const tone = isLoading ? "unscored" : sprintRiskTone(overall);
  const meta = TONE_META[tone];
  const Icon = TONE_ICON[tone];
  const scored = !isLoading && tone !== "unscored";
  const pct = riskPercent(overall?.score);
  const gap = pointsToHigh(overall);

  return (
    <article
      // The tone is one custom property rather than four class variants, so every
      // surface on the card — ring, bar, icon, glow — is guaranteed to move together.
      style={{ "--tone": meta.accent, "--tone-soft": meta.soft } as React.CSSProperties}
      className={cn(
        "group relative flex flex-col overflow-hidden rounded-xl border bg-surface p-4 shadow-card",
        "transition-transform duration-200 hover:-translate-y-0.5",
        tone === "near-high"
          ? "animate-risk-blink border-[var(--tone)]"
          : tone === "high"
            ? "border-[color-mix(in_oklab,var(--tone)_45%,var(--line))] bg-[color-mix(in_oklab,var(--tone-soft)_60%,var(--surface))]"
            : "border-line",
      )}
    >
      {/* The band as a colour stripe, so a wall of cards reads at a glance. */}
      <span aria-hidden className="absolute inset-x-0 top-0 h-[3px] bg-[var(--tone)]" />

      <header className="mb-3 flex items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 className="truncate text-base font-semibold text-ink" title={sprint.name}>
            {sprint.name}
          </h3>
          <p className="mt-0.5 text-xs text-ink-3">
            {STATUS_LABEL[sprint.status] ?? sprint.status}
            {sprint.storyCount != null ? ` · ${sprint.storyCount} stories` : null}
          </p>
        </div>
        <span
          aria-hidden
          className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-[var(--tone-soft)] text-[var(--tone)]"
        >
          <Icon className="size-4" />
        </span>
      </header>

      {scored ? (
        <>
          <div className="flex items-baseline gap-1.5">
            <span className="text-4xl leading-none font-semibold tracking-[-0.02em] text-[var(--tone)]">
              {pct}
              <span className="text-xl">%</span>
            </span>
          </div>
          <p className="mt-1 text-xs font-semibold text-[var(--tone)]">{meta.label}</p>

          <RiskMeter pct={pct} />

          <p className="mt-2 text-xs leading-snug text-ink-3">
            {tone === "near-high" && gap != null ? (
              <span className="font-semibold text-[var(--tone)]">
                {gap} {gap === 1 ? "point" : "points"} from high risk
              </span>
            ) : tone === "high" ? (
              "At or above the high-risk threshold"
            ) : gap != null ? (
              `${gap} points below high risk`
            ) : null}
          </p>
        </>
      ) : (
        <div className="flex flex-1 flex-col justify-center py-3">
          <p className="text-2xl leading-none font-semibold text-ink-3">—</p>
          <p className="mt-1.5 text-xs text-ink-3">
            {isLoading ? "Reading risk…" : "Never assessed"}
          </p>
        </div>
      )}
    </article>
  );
}

/**
 * The figure against the scale it is banded on, with the high boundary marked.
 *
 * <p>The marker is the reason this is a bar rather than another number. "58%" and "3
 * points from high" are the same fact stated twice; a filled bar stopping just short of
 * a labelled line is the fact shown, and it is the one people actually read.</p>
 */
function RiskMeter({ pct }: { pct: number }) {
  return (
    <div className="mt-3">
      <div className="relative h-1.5 w-full rounded-full bg-surface-3">
        <div
          className="h-full rounded-full bg-[var(--tone)] transition-[width] duration-500"
          style={{ width: `${Math.min(100, Math.max(0, pct))}%` }}
        />
        {/* The HIGH boundary, drawn on the track the figure is measured against. */}
        <span
          aria-hidden
          className="absolute -top-0.5 h-2.5 w-px bg-ink-3"
          style={{ left: `${HIGH_BAND_PCT}%` }}
        />
      </div>
      <div className="relative mt-1 h-3">
        <span
          className="absolute -translate-x-1/2 text-2xs font-medium text-ink-3"
          style={{ left: `${HIGH_BAND_PCT}%` }}
        >
          {HIGH_BAND_PCT}%
        </span>
      </div>
    </div>
  );
}
