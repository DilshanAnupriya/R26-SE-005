"use client";

import { AlertTriangle, Loader2, RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/design/surfaces";
import type { RiskAdjustment, RiskAssessment, SprintRisk } from "@/lib/api/ai-schemas";
import { asPercent, RiskGauge, SeverityBadge } from "./risk-gauge";

const MODELS = [
  {
    key: "overBudget",
    title: "Over-budget",
    question: "Will this sprint cost more effort than it was given?",
  },
  {
    key: "requirementChange",
    title: "Requirement change",
    question: "Are the requirements behind this sprint still moving?",
  },
  {
    key: "communicationCollaboration",
    title: "Collaboration",
    question: "Is the team still coordinating — replies, discussion, blockers, rework?",
  },
] as const;

function formatWhen(iso: string | null | undefined): string {
  if (!iso) return "never";
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? iso
    : date.toLocaleString("en-GB", { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" });
}

/**
 * The three gauges, plus the ledger of what has happened since they were last scored.
 *
 * <p>Both halves are shown because either alone misleads. The gauge without the ledger
 * is a number nobody can argue with; the ledger without the gauge is a list of events
 * with no verdict. Together they say "78%, and here are the eleven points of that which
 * arrived this morning" — which is the only form of this screen a team will act on.</p>
 */
export function SprintRiskOverview({
  risk,
  adjustments,
  loading,
  onAssess,
  assessing,
}: {
  risk?: SprintRisk;
  adjustments?: RiskAdjustment[];
  loading?: boolean;
  onAssess?: () => void;
  assessing?: boolean;
}) {
  const anyScored = MODELS.some((model) => risk?.[model.key] != null);

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader
          title="Sprint risk"
          subtitle={
            anyScored
              ? "Model scores with the board activity since each one folded in."
              : "This sprint has not been assessed yet."
          }
          action={
            <div className="flex items-center gap-3">
              {risk?.highestSeverity ? (
                <SeverityBadge severity={risk.highestSeverity} scored={anyScored} />
              ) : null}
              <Button size="sm" variant="outline" onClick={onAssess} disabled={assessing || !onAssess}>
                {assessing ? (
                  <Loader2 className="size-3.5 animate-spin" aria-hidden />
                ) : (
                  <RefreshCw className="size-3.5" aria-hidden />
                )}
                Assess now
              </Button>
            </div>
          }
        />

        {loading ? (
          <div className="grid gap-4 p-4 sm:grid-cols-3">
            {MODELS.map((model) => (
              <div key={model.key} className="h-56 animate-pulse rounded-xl bg-surface-3" />
            ))}
          </div>
        ) : (
          <div className="grid gap-4 p-4 sm:grid-cols-3">
            {MODELS.map((model) => (
              <ModelPanel
                key={model.key}
                title={model.title}
                question={model.question}
                assessment={risk?.[model.key] ?? undefined}
              />
            ))}
          </div>
        )}
      </Card>

      <AdjustmentLedger adjustments={adjustments ?? []} />
    </div>
  );
}

function ModelPanel({
  title,
  question,
  assessment,
}: {
  title: string;
  question: string;
  assessment?: RiskAssessment | null;
}) {
  const scored = assessment != null;
  // The adjusted score is what the badge and the gauge read: it is the number the board
  // currently supports. The model's own figure stays visible underneath, because a
  // dashboard that cannot be reconciled with the prediction record gets distrusted.
  const displayed = assessment?.adjustedScore ?? assessment?.riskScore ?? null;
  const delta = assessment?.adjustmentDelta ?? 0;

  return (
    <div className="flex flex-col items-center rounded-xl border border-line bg-surface-2 p-4 text-center">
      <div className="mb-1 flex items-center gap-2">
        <span className="text-sm font-semibold">{title}</span>
        <SeverityBadge severity={assessment?.adjustedSeverity ?? assessment?.severity} scored={scored} />
      </div>

      <RiskGauge score={displayed} size={116} />

      {scored ? (
        <>
          {delta !== 0 ? (
            <p className="mt-1 text-xs text-ink-3">
              Model said {asPercent(assessment?.riskScore)}%
              <span className={delta > 0 ? "ml-1 font-semibold text-red" : "ml-1 font-semibold text-green"}>
                {delta > 0 ? "+" : ""}
                {delta} from the board
              </span>
            </p>
          ) : (
            <p className="mt-1 text-xs text-ink-3">Scored {formatWhen(assessment?.createdAt)}</p>
          )}

          {assessment?.explanation ? (
            <p className="mt-2 text-xs leading-relaxed text-ink-2">{assessment.explanation}</p>
          ) : null}

          {assessment?.mitigations?.length ? (
            <ul className="mt-2 space-y-1 text-left text-xs text-ink-2">
              {assessment.mitigations.slice(0, 3).map((mitigation) => (
                <li key={mitigation} className="flex gap-1.5">
                  <span aria-hidden className="text-ink-3">
                    •
                  </span>
                  {mitigation}
                </li>
              ))}
            </ul>
          ) : null}
        </>
      ) : (
        <p className="mt-2 text-xs leading-relaxed text-ink-3">
          {question}
          <span className="mt-1 block">Not enough to read yet.</span>
        </p>
      )}
    </div>
  );
}

/**
 * What the board has recorded since each model last ran.
 *
 * <p>This is the audit trail for the difference between the model's figure and the one
 * on screen. Without it the adjustment is an unexplained number, and an unexplained
 * number on a risk dashboard is one people learn to ignore.</p>
 */
function AdjustmentLedger({ adjustments }: { adjustments: RiskAdjustment[] }) {
  return (
    <Card>
      <CardHeader
        title="Since the last assessment"
        subtitle={
          adjustments.length === 0
            ? "Nothing has moved the score since the models last ran."
            : `${adjustments.length} recorded event${adjustments.length === 1 ? "" : "s"}`
        }
      />
      {adjustments.length === 0 ? (
        <p className="px-4 py-6 text-center text-sm text-ink-3">
          Blockers, rework and logged requirement changes appear here as they happen, and move the
          gauges above without waiting for a re-assessment.
        </p>
      ) : (
        <ul className="divide-y divide-line">
          {adjustments.map((adjustment) => (
            <li key={adjustment.id} className="flex items-center gap-3 px-4 py-2.5">
              <span
                className={
                  adjustment.delta >= 0
                    ? "w-14 shrink-0 text-right text-sm font-semibold text-red tabular-nums"
                    : "w-14 shrink-0 text-right text-sm font-semibold text-green tabular-nums"
                }
              >
                {adjustment.delta > 0 ? "+" : ""}
                {adjustment.delta}
              </span>
              <span className="min-w-0 flex-1 text-sm">{adjustment.reason}</span>
              <span className="shrink-0 text-xs text-ink-3">{formatWhen(adjustment.createdAt)}</span>
            </li>
          ))}
        </ul>
      )}
      <p className="flex items-start gap-1.5 border-t border-line px-4 py-2 text-xs text-ink-3">
        <AlertTriangle className="mt-px size-3 shrink-0" aria-hidden />
        Points are an agreed ranking of how much each event disturbs a plan, not a measured
        quantity. Re-assessing folds them into the model&rsquo;s own figure and starts this list again.
      </p>
    </Card>
  );
}
