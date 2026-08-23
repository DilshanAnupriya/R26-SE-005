"use client";

import { Gauge, Wrench } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import type { OverallRisk, RiskSeverity } from "@/lib/api/ai-schemas";
import { cn } from "@/lib/utils";

const TONE: Record<RiskSeverity, string> = {
  LOW: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
  MEDIUM: "bg-amber-500/10 text-amber-700 dark:text-amber-400",
  HIGH: "bg-orange-500/10 text-orange-700 dark:text-orange-400",
  CRITICAL: "bg-destructive/15 text-destructive",
};

const LABEL: Record<string, string> = {
  overBudget: "over-budget",
  requirementChange: "requirement change",
  communicationCollaboration: "collaboration",
};

/**
 * The three risk models as one number.
 *
 * <p>Shown above the individual cards rather than instead of them. The single figure is
 * what a delivery lead acts on; the three underneath are what makes it arguable, and a
 * dashboard that only showed the aggregate would be asking to be trusted on faith.</p>
 *
 * <p>The weights are on display for the same reason. A model that could not be scored
 * contributes nothing and the others are renormalised — so a sprint assessed on two
 * models out of three is visibly that, rather than quietly averaging a gap as zero.</p>
 */
export function OverallRiskPanel({
  overall,
  onFix,
}: {
  overall: OverallRisk;
  onFix?: () => void;
}) {
  const pct = Math.round(overall.score * 100);
  const contributing = Object.entries(overall.weights).filter(([, weight]) => weight > 0);

  return (
    <section
      className={cn("rounded-lg border p-3", overall.degraded && "border-amber-500/40")}
      aria-labelledby="overall-risk-heading"
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3
            id="overall-risk-heading"
            className="flex items-center gap-1.5 text-sm font-medium"
          >
            <Gauge className="size-4 text-primary" aria-hidden />
            Overall sprint risk
          </h3>

          <div className="mt-1.5 flex items-baseline gap-2">
            <span className="text-2xl font-semibold tabular-nums">{pct}%</span>
            <Badge className={cn("border-0", TONE[overall.level])}>{overall.level}</Badge>
            {overall.degraded ? (
              <span className="text-2xs text-amber-600 dark:text-amber-400">
                incomplete evidence
              </span>
            ) : null}
          </div>
        </div>

        {onFix ? (
          <Button size="sm" variant="secondary" onClick={onFix}>
            <Wrench className="size-3.5" aria-hidden />
            Fix findings
          </Button>
        ) : null}
      </div>

      {/* Which models are actually behind the number, at what weight. */}
      <div className="mt-3 flex flex-wrap gap-1.5">
        {contributing.map(([key, weight]) => (
          <span
            key={key}
            className="rounded-md bg-muted px-1.5 py-0.5 text-2xs text-muted-foreground tabular-nums"
          >
            {LABEL[key] ?? key} {Math.round(weight * 100)}%
          </span>
        ))}
        {contributing.length < 3 ? (
          <span className="rounded-md bg-amber-500/10 px-1.5 py-0.5 text-2xs text-amber-700 dark:text-amber-400">
            {3 - contributing.length} model
            {3 - contributing.length === 1 ? "" : "s"} not scored
          </span>
        ) : null}
      </div>

      {overall.rationale ? (
        <p className="mt-2 text-xs leading-relaxed text-muted-foreground">
          {overall.rationale}
        </p>
      ) : null}
    </section>
  );
}
