"use client";

import { useState } from "react";
import { ChevronDown, Gauge } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { DailyRiskTrend } from "@/features/risk-analysis/components/daily-risk-trend";
import { useDailyRiskTrend } from "@/features/risk-analysis/hooks";
import { useSprintRisk } from "@/features/ai/hooks";
import type { RiskSeverity } from "@/lib/api/ai-schemas";
import { cn } from "@/lib/utils";

const TONE: Record<RiskSeverity, string> = {
  LOW: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
  MEDIUM: "bg-amber-500/10 text-amber-700 dark:text-amber-400",
  HIGH: "bg-orange-500/10 text-orange-700 dark:text-orange-400",
  CRITICAL: "bg-destructive/15 text-destructive",
};

/**
 * The selected sprint's risk, on the board where the work causing it lives.
 *
 * <p>Collapsed by default. The board's job is the cards, and a chart that pushed them
 * below the fold would be paid for on every visit to buy something most visits do not
 * need. The headline — the number and its band — is always visible in the summary row,
 * which is the part worth glancing at; the shape over time is one click away.</p>
 *
 * <p>Reuses the Risk Analysis chart rather than drawing a second one. Two charts of the
 * same series would eventually disagree about a colour, a rounding or an unscored day,
 * and the board's version would be the one people saw most.</p>
 */
export function SprintRiskStrip({ sprintId }: { sprintId?: string }) {
  const [open, setOpen] = useState(false);

  // Both are fetched only once a sprint is chosen and the panel is opened for the
  // chart, so the board's default view costs nothing extra.
  const risk = useSprintRisk(sprintId);
  const trend = useDailyRiskTrend(open ? sprintId : undefined);

  if (!sprintId) {
    return null;
  }

  const overall = risk.data?.overall;
  const severity = overall?.level;

  return (
    <section className="rounded-lg border" aria-labelledby="board-risk-heading">
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        className="flex w-full items-center justify-between gap-3 p-3 text-left transition-colors hover:bg-muted/50 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
      >
        <span className="flex min-w-0 items-center gap-2">
          <Gauge className="size-4 shrink-0 text-primary" aria-hidden />
          <span id="board-risk-heading" className="text-sm font-medium">
            Sprint risk
          </span>

          {overall ? (
            <>
              <span className="text-sm font-semibold tabular-nums">
                {Math.round(overall.score * 100)}%
              </span>
              {severity ? (
                <Badge className={cn("border-0", TONE[severity])}>{severity}</Badge>
              ) : null}
              {overall.degraded ? (
                <span className="text-2xs text-amber-600 dark:text-amber-400">
                  incomplete evidence
                </span>
              ) : null}
            </>
          ) : (
            <span className="text-xs text-muted-foreground">
              {risk.isPending ? "Loading…" : "Not assessed yet — run one from the Sprints page."}
            </span>
          )}
        </span>

        <ChevronDown
          className={cn("size-4 shrink-0 text-muted-foreground transition-transform",
            open && "rotate-180")}
          aria-hidden
        />
      </button>

      {open ? (
        <div className="border-t p-3">
          <DailyRiskTrend points={trend.data ?? []} loading={trend.isPending} compact />
        </div>
      ) : null}
    </section>
  );
}
