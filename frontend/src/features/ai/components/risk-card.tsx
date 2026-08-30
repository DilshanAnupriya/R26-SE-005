"use client";

import { AlertTriangle, Check, ShieldCheck } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import type { RiskAssessment, RiskSeverity } from "@/lib/api/ai-schemas";
import { DriverList } from "./prediction-explanation";
import { cn } from "@/lib/utils";

const SEVERITY_TONE: Record<RiskSeverity, string> = {
  LOW: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
  MEDIUM: "bg-amber-500/10 text-amber-700 dark:text-amber-400",
  HIGH: "bg-orange-500/10 text-orange-700 dark:text-orange-400",
  CRITICAL: "bg-destructive/15 text-destructive",
};

const RISK_LABEL = {
  OVER_BUDGET: "Over-budget risk",
  REQUIREMENT_CHANGE: "Requirement change risk",
  COMMUNICATION_COLLABORATION: "Communication & collaboration risk",
} as const;

/**
 * What each model is actually reading, for the tooltip on the heading.
 *
 * <p>Over-budget and requirement change are self-describing; the third is not. "High
 * collaboration risk" invites the reading that somebody has judged the team, when what
 * happened is that five things were counted.</p>
 */
const RISK_BASIS: Record<keyof typeof RISK_LABEL, string> = {
  OVER_BUDGET: "Planned effort against capacity, velocity history and carry-over.",
  REQUIREMENT_CHANGE: "Story detail, acceptance criteria and this project's churn history.",
  COMMUNICATION_COLLABORATION:
    "Reply time on task comments, comments per task, days with nothing recorded, " +
    "blocked tasks and reopened tasks.",
};

/**
 * One Model 2 verdict.
 *
 * <p>The probability is shown as a percentage next to the severity band rather than
 * instead of it. A raw 0.62 invites false precision from an uncalibrated classifier;
 * the band is the part a team should act on, and the number is there so nobody has to
 * take the band on faith.</p>
 *
 * <p>Acknowledging does not change the assessment — it records that a human has seen
 * it. A warning nobody has read and one the team has read and accepted look identical
 * on a dashboard otherwise, and only one of them should keep shouting.</p>
 */
export function RiskCard({
  assessment,
  onAcknowledge,
  acknowledging,
}: {
  assessment: RiskAssessment;
  onAcknowledge?: (assessmentId: string) => void;
  acknowledging?: boolean;
}) {
  // The adjusted score, not the model's own. `latest` folds the board's ledger into
  // every assessment it returns, and reading `riskScore` here showed the figure from
  // whenever Assess was last pressed — so this card and the risk analysis page
  // disagreed about the same sprint. Same precedence the analysis page uses.
  const displayed = assessment.adjustedScore ?? assessment.riskScore;
  const severity = assessment.adjustedSeverity ?? assessment.severity;
  const delta = assessment.adjustmentDelta ?? 0;
  const pct = Math.round(displayed * 100);
  const acknowledged = Boolean(assessment.acknowledgedAt);
  // `atRisk` is the model's own binary call on its own score, so the board pushing a
  // sprint past the threshold has to be honoured here too — the alternative is a green
  // all-clear beside a red percentage.
  const atRisk = assessment.atRisk || displayed >= 0.5;
  const Icon = atRisk ? AlertTriangle : ShieldCheck;

  return (
    <article
      className={cn("rounded-lg border p-3", atRisk && "border-amber-500/40")}
      aria-labelledby={`risk-${assessment.id}`}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 id={`risk-${assessment.id}`} className="flex items-center gap-1.5 text-sm font-medium">
            <Icon
              className={cn("size-4", atRisk ? "text-amber-600" : "text-emerald-600")}
              aria-hidden
            />
            <span title={RISK_BASIS[assessment.riskType]}>
              {RISK_LABEL[assessment.riskType]}
            </span>
          </h3>
          <div className="mt-1.5 flex items-center gap-2">
            <span className="text-2xl font-semibold tabular-nums">{pct}%</span>
            <Badge className={cn("border-0", SEVERITY_TONE[severity])}>
              {severity}
            </Badge>
            {acknowledged ? (
              <Badge variant="outline" className="gap-1">
                <Check className="size-3" aria-hidden />
                Acknowledged
              </Badge>
            ) : null}
          </div>
          {delta !== 0 ? (
            // The model's own figure stays visible beneath the adjusted one. A
            // dashboard that cannot be reconciled with the prediction record gets
            // distrusted — same line the risk analysis page carries.
            <p className="mt-1 text-xs text-muted-foreground">
              Model said {Math.round(assessment.riskScore * 100)}%
              <span
                className={cn(
                  "ml-1 font-semibold",
                  delta > 0 ? "text-destructive" : "text-emerald-600",
                )}
              >
                {delta > 0 ? "+" : ""}
                {delta} from the board
              </span>
            </p>
          ) : null}
        </div>
      </div>

      {assessment.explanation ? (
        <p className="mt-2 text-xs leading-relaxed text-muted-foreground">
          {assessment.explanation}
        </p>
      ) : null}

      <div className="mt-3">
        <DriverList drivers={assessment.drivers} />
      </div>

      {assessment.mitigations.length > 0 ? (
        <div className="mt-3">
          <p className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
            What would help
          </p>
          <ul className="mt-1 space-y-1">
            {assessment.mitigations.map((mitigation) => (
              <li key={mitigation} className="text-xs text-muted-foreground">
                • {mitigation}
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {!acknowledged && onAcknowledge ? (
        <Button
          size="sm"
          variant="ghost"
          className="mt-3"
          disabled={acknowledging}
          onClick={() => onAcknowledge(assessment.id)}
        >
          <Check className="size-3.5" aria-hidden />
          Acknowledge
        </Button>
      ) : null}
    </article>
  );
}
