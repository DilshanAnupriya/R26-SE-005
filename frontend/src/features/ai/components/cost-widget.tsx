"use client";

import { Minus, TrendingDown, TrendingUp, Wallet } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import type { CostForecast } from "@/lib/api/ai-schemas";
import { BoundsLabel, ConfidenceBar, DriverList } from "./prediction-explanation";
import { cn } from "@/lib/utils";

function money(amount: number, currency: string) {
  const fractionDigits = Math.round(Math.abs(amount) * 100) % 100 === 0 ? 0 : 2;
  return new Intl.NumberFormat(undefined, {
    style: "currency",
    currency,
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: 2,
  }).format(amount);
}

/**
 * Model 4's planning forecast.
 *
 * <p>The margin is rendered as prominently as the figure. A forecast of $48,000 ± $2,000
 * and one of $48,000 ± $19,000 support completely different decisions, and a widget that
 * showed only the point estimate would invite someone to commit to the second as if it
 * were the first.</p>
 *
 * <p>It says "planning forecast" in the heading, every time. This is not billing, not
 * payroll and not an invoice, and the one thing that must never happen is a screenshot
 * of this number being forwarded as an actual.</p>
 */
export function CostWidget({ forecast }: { forecast: CostForecast }) {
  const variance = forecast.varianceVsBudgetPct;
  const overBudget = variance != null && variance > 0;

  const TrendIcon =
    forecast.trend === "UP" ? TrendingUp : forecast.trend === "DOWN" ? TrendingDown : Minus;

  return (
    <article className="rounded-lg border p-3" aria-labelledby={`cost-${forecast.id}`}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 id={`cost-${forecast.id}`} className="flex items-center gap-1.5 text-sm font-medium">
            <Wallet className="size-4 text-primary" aria-hidden />
            Planning cost forecast
          </h3>
          <p className="text-[11px] text-muted-foreground">
            {forecast.stage === "MID_SPRINT"
              ? "Extrapolated from effort logged so far"
              : "Priced from estimated task effort"}
          </p>
        </div>
        <Badge variant="outline" className="gap-1 shrink-0">
          <TrendIcon className="size-3" aria-hidden />
          {forecast.trend ?? "FLAT"}
        </Badge>
      </div>

      <div className="mt-2 flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <span className="text-2xl font-semibold tabular-nums">
          {money(forecast.forecastAmount, forecast.currency)}
        </span>
        {forecast.errorMarginAmount != null ? (
          <span className="text-sm text-muted-foreground tabular-nums">
            ± {money(forecast.errorMarginAmount, forecast.currency)}
          </span>
        ) : null}
        <BoundsLabel
          lower={forecast.lowerBound}
          upper={forecast.upperBound}
          format={(value) => money(value, forecast.currency)}
          prefix="Estimated range"
        />
      </div>

      <div className="mt-2">
        <ConfidenceBar confidence={forecast.confidence} />
      </div>

      {variance != null ? (
        <p
          className={cn(
            "mt-2 text-xs font-medium tabular-nums",
            overBudget ? "text-destructive" : "text-emerald-600 dark:text-emerald-400",
          )}
        >
          {overBudget ? "+" : ""}
          {variance}% against budget
          {forecast.budgetAmount != null
            ? ` (${money(forecast.budgetAmount, forecast.currency)})`
            : null}
        </p>
      ) : (
        <p className="mt-2 text-xs text-muted-foreground">
          Set a sprint budget to see variance.
        </p>
      )}

      {forecast.explanation ? (
        <p className="mt-2 text-xs leading-relaxed text-muted-foreground">{forecast.explanation}</p>
      ) : null}

      <div className="mt-3">
        <DriverList drivers={forecast.drivers} />
      </div>
    </article>
  );
}
