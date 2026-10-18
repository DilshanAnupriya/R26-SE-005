"use client";

import {
  AlertTriangle,
  CheckCircle2,
  CircleDashed,
  Coins,
  Users,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { useCostRiskCrossCheck } from "@/features/ai/hooks";
import type { CostRiskAgreement, CostRiskCrossCheck } from "@/lib/api/ai-schemas";
import { cn } from "@/lib/utils";

/**
 * Two independent readings of the same question, side by side.
 *
 * <p>SprintIQ provides two readings of budget pressure. The risk model scores delivery
 * signals while the cost model estimates final labor cost from effort and rates.
 * Mid-sprint cost forecasts also use delivery signals, so agreement is contextual
 * evidence rather than an independent validation.</p>
 *
 * <p>The comparison helps reviewers inspect disagreement without assigning a cause from
 * model output alone.</p>
 */

const PRESENTATION: Record<
  CostRiskAgreement,
  { icon: LucideIcon; tone: string; badge: string; label: string }
> = {
  BOTH_HIGH: {
    icon: AlertTriangle,
    tone: "border-destructive/40 bg-destructive/5",
    badge: "bg-destructive/15 text-destructive",
    label: "Models agree · at risk",
  },
  BOTH_LOW: {
    icon: CheckCircle2,
    tone: "border-emerald-500/40 bg-emerald-500/5",
    badge: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
    label: "No budget alert",
  },
  COST_ONLY: {
    icon: Coins,
    tone: "border-amber-500/40 bg-amber-500/5",
    badge: "bg-amber-500/10 text-amber-700 dark:text-amber-400",
    label: "Cost forecast flagged",
  },
  RISK_ONLY: {
    icon: Users,
    tone: "border-orange-500/40 bg-orange-500/5",
    badge: "bg-orange-500/10 text-orange-700 dark:text-orange-400",
    label: "Risk score flagged",
  },
  UNKNOWN: {
    icon: CircleDashed,
    tone: "border-muted",
    badge: "bg-muted text-muted-foreground",
    label: "Not comparable",
  },
};

function money(amount: number | null | undefined, currency: string | null | undefined) {
  if (amount == null) return "—";
  const fractionDigits = Math.round(Math.abs(amount) * 100) % 100 === 0 ? 0 : 2;
  return new Intl.NumberFormat(undefined, {
    style: "currency",
    currency: currency ?? "USD",
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: 2,
  }).format(amount);
}

export function CostRiskCrossCheckPanel({ sprintId }: { sprintId: string | undefined }) {
  const { data, isPending, isError } = useCostRiskCrossCheck(sprintId);

  if (!sprintId) return null;
  if (isPending) {
    return <Skeleton className="h-40 w-full rounded-lg" />;
  }
  if (isError || !data) {
    return (
      <section className="rounded-lg border p-3 text-sm text-muted-foreground">
        The cost/risk cross-check is unavailable right now.
      </section>
    );
  }

  return <CrossCheck data={data} />;
}

function CrossCheck({ data }: { data: CostRiskCrossCheck }) {
  const view = PRESENTATION[data.agreement];
  const Icon = view.icon;
  const riskPct = data.riskScore == null ? null : Math.round(data.riskScore * 100);

  return (
    <section
      className={cn("rounded-lg border p-3", view.tone)}
      aria-labelledby="cost-risk-cross-check-heading"
    >
      <div className="flex items-start justify-between gap-3">
        <h3
          id="cost-risk-cross-check-heading"
          className="flex items-center gap-1.5 text-sm font-medium"
        >
          <Icon className="size-4" aria-hidden />
          Cost vs risk
        </h3>
        <Badge className={cn("border-0 shrink-0", view.badge)}>{view.label}</Badge>
      </div>

      <p className="mt-2 text-sm font-medium">{data.headline}</p>

      {/* The two readings, kept visibly separate — the panel's claim is that these are
          two measurements and not one number shown twice. */}
      <dl className="mt-3 grid grid-cols-2 gap-3">
        <div className="rounded-md border bg-background/60 p-2">
          <dt className="flex items-center gap-1.5 text-xs text-muted-foreground">
            <Coins className="size-3.5" aria-hidden />
            Cost model
          </dt>
          <dd className="mt-1 text-sm font-semibold tabular-nums">
            {money(data.forecastAmount, data.currency)}
          </dd>
          <dd className="text-xs text-muted-foreground">
            {data.budgetAmount == null
              ? "no budget set"
              : `vs ${money(data.budgetAmount, data.currency)} budget`}
            {data.variancePct != null && (
              <>
                {" · "}
                <span className={cn(data.costCharged && "text-destructive font-medium")}>
                  {data.variancePct > 0 ? "+" : ""}
                  {data.variancePct.toFixed(1)}%
                </span>
              </>
            )}
          </dd>
        </div>

        <div className="rounded-md border bg-background/60 p-2">
          <dt className="flex items-center gap-1.5 text-xs text-muted-foreground">
            <Users className="size-3.5" aria-hidden />
            Risk model
          </dt>
          <dd className="mt-1 text-sm font-semibold tabular-nums">
            {riskPct == null ? "—" : `${riskPct}%`}
          </dd>
          <dd className="text-xs text-muted-foreground">
            {data.riskSeverity
              ? `${data.riskSeverity.toLowerCase()} · chance of going over`
              : "not assessed yet"}
          </dd>
        </div>
      </dl>

      <p className="mt-3 text-xs leading-relaxed text-muted-foreground">{data.detail}</p>

      {/* Said out loud rather than left for the reader to infer from a colour. A variance
          smaller than the model's own error rate is the model being imprecise, not the
          sprint overspending, and a panel that coloured it red would be lying. */}
      {data.variancePct != null && data.variancePct > 0 && !data.costCharged && (
        <p className="mt-2 text-xs text-muted-foreground">
          Under the cost model&rsquo;s {data.marginPct.toFixed(1)}% average error, so this
          variance is not treated as evidence of an overrun.
        </p>
      )}
    </section>
  );
}
