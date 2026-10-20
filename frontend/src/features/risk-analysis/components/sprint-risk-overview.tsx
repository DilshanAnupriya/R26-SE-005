"use client";

import { AlertTriangle, Loader2, RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/design/surfaces";
import type {
  RiskAdjustment,
  RiskAssessment,
  RiskRemediationStep,
  SprintRisk,
} from "@/lib/api/ai-schemas";
import { RISK_MODELS, WEIGHT_LABEL } from "../risk-models";
import { asPercent, RiskGauge, SeverityBadge } from "./risk-gauge";
import { RiskExplanations, type AbsorbToggle, type StepToggle } from "./risk-explanations";

function formatWhen(iso: string | null | undefined): string {
  if (!iso) return "never";
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? iso
    : date.toLocaleString("en-GB", { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" });
}

/**
 * The three gauges, the reasons behind them, and the ledger of what has happened since
 * they were last scored.
 *
 * <p>All three are shown because any one alone misleads. The gauge without the ledger is
 * a number nobody can argue with; the ledger without the gauge is a list of events with
 * no verdict; and either without the readings underneath is a verdict with no grounds.
 * Together they say "78%, here is what the model weighed to get there, and here are the
 * eleven points of it that arrived this morning" — which is the only form of this screen
 * a team will act on.</p>
 */
export function SprintRiskOverview({
  risk,
  adjustments,
  steps,
  loading,
  onAssess,
  assessing,
  onToggleStep,
  togglingStepKey,
  onAbsorb,
  absorbingId,
}: {
  risk?: SprintRisk;
  adjustments?: RiskAdjustment[];
  steps?: RiskRemediationStep[];
  loading?: boolean;
  onAssess?: () => void;
  assessing?: boolean;
  onToggleStep?: StepToggle;
  togglingStepKey?: string | null;
  onAbsorb?: AbsorbToggle;
  absorbingId?: string | null;
}) {
  const anyScored = RISK_MODELS.some((model) => risk?.[model.key] != null);

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
            <Button size="sm" variant="outline" onClick={onAssess} disabled={assessing || !onAssess}>
              {assessing ? (
                <Loader2 className="size-3.5 animate-spin" aria-hidden />
              ) : (
                <RefreshCw className="size-3.5" aria-hidden />
              )}
              Assess now
            </Button>
          }
        />

        {loading ? (
          <>
            <div className="h-50 animate-pulse border-b border-line bg-surface-3" />
            <div className="grid gap-4 p-4 sm:grid-cols-3">
              {RISK_MODELS.map((model) => (
                <div key={model.key} className="h-56 animate-pulse rounded-xl bg-surface-3" />
              ))}
            </div>
          </>
        ) : (
          <>
            <OverallBand risk={risk} scored={anyScored} />
            <div className="grid gap-4 p-4 sm:grid-cols-3">
              {RISK_MODELS.map((model) => (
                <ModelPanel
                  key={model.key}
                  title={model.title}
                  question={model.question}
                  assessment={risk?.[model.key] ?? undefined}
                />
              ))}
            </div>
          </>
        )}
      </Card>

      <RiskExplanations
        risk={risk}
        adjustments={adjustments}
        steps={steps}
        loading={loading}
        onToggleStep={onToggleStep}
        togglingStepKey={togglingStepKey}
        onAbsorb={onAbsorb}
        absorbingId={absorbingId}
      />

      <AdjustmentLedger adjustments={adjustments ?? []} />
    </div>
  );
}

/**
 * The three models as one figure, above the three that make it.
 *
 * <p>The headline a delivery lead acts on. It sits above the breakdown rather than
 * replacing it, because an aggregate on its own asks to be trusted on faith, and the
 * three panels underneath are what make it arguable.</p>
 *
 * <p>Two different verdicts live here and they are labelled rather than merged. The
 * weighted figure is what the sprint is worth overall; the loudest single model can be a
 * band higher and is the one somebody has to go and look at. Showing only the first
 * hides an emergency inside an average; showing only the second, which is what this card
 * did before as an unlabelled badge, reports every sprint at its worst corner.</p>
 *
 * <p>The weights are on show because a model that could not be scored contributes
 * nothing and the rest are renormalised. A sprint assessed on two models out of three is
 * then visibly that, rather than quietly averaging the gap as zero.</p>
 */
function OverallBand({ risk, scored }: { risk?: SprintRisk; scored: boolean }) {
  const overall = risk?.overall ?? null;
  const level = overall?.level ?? null;
  const highest = risk?.highestSeverity ?? null;
  const contributing = Object.entries(overall?.weights ?? {}).filter(([, weight]) => weight > 0);
  const missing = RISK_MODELS.length - contributing.length;

  return (
    <section
      aria-labelledby="overall-risk-heading"
      className="flex flex-col items-center gap-4 border-b border-line bg-surface-2/50 p-4 sm:flex-row sm:items-start"
    >
      <RiskGauge score={overall?.score ?? null} size={132} label="Overall" className="shrink-0" />

      <div className="min-w-0 flex-1 text-center sm:text-left">
        <div className="flex flex-wrap items-center justify-center gap-2 sm:justify-start">
          <h3 id="overall-risk-heading" className="text-sm font-semibold">
            Overall sprint risk
          </h3>
          <SeverityBadge severity={level} scored={scored && overall != null} />
          {overall?.degraded ? (
            <span className="rounded-full bg-surface-3 px-2 py-0.5 text-[11px] font-medium text-amber">
              incomplete evidence
            </span>
          ) : null}
        </div>

        {overall ? (
          <>
            <p className="mt-1 text-2xl font-semibold tabular-nums">
              {asPercent(overall.score)}%
              <span className="ml-2 align-middle text-xs font-normal text-ink-3">
                the three models weighted into one figure
              </span>
            </p>

            {/*
              Only when it disagrees. Where the worst model already sits in the overall
              band, a second line saying so is noise.
            */}
            {highest && highest !== level ? (
              <p className="mt-1 text-xs text-ink-2">
                Loudest single model reads{" "}
                <span className="font-semibold">{titleCase(highest)}</span> — the weighted figure
                sits lower because the other two are calmer.
              </p>
            ) : null}

            <div className="mt-2 flex flex-wrap justify-center gap-1.5 sm:justify-start">
              {contributing.map(([key, weight]) => (
                <span
                  key={key}
                  className="rounded-md bg-surface-3 px-1.5 py-0.5 text-[11px] text-ink-3 tabular-nums"
                >
                  {WEIGHT_LABEL[key] ?? key} {Math.round(weight * 100)}%
                </span>
              ))}
              {missing > 0 ? (
                <span className="rounded-md bg-surface-3 px-1.5 py-0.5 text-[11px] text-amber">
                  {missing} model{missing === 1 ? "" : "s"} not scored
                </span>
              ) : null}
            </div>
          </>
        ) : (
          <p className="mt-1 text-xs leading-relaxed text-ink-3">
            {scored
              ? "Not enough of the three models answered to weigh them into one figure."
              : "Assess the sprint to get a single figure across the three models."}
          </p>
        )}
      </div>
    </section>
  );
}

/** CRITICAL reads as shouting inside a sentence; Critical does not. */
function titleCase(value: string): string {
  return value.charAt(0) + value.slice(1).toLowerCase();
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
  // currently supports.
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
          {/* The model-versus-board split is not repeated here; the explanations below
              still break the adjusted figure down. */}
          {delta === 0 ? (
            <p className="mt-1 text-xs text-ink-3">Scored {formatWhen(assessment?.createdAt)}</p>
          ) : null}

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
              <span className="min-w-0 flex-1 text-sm">
                {adjustment.reason}
                {adjustment.survivesScoring ? (
                  /*
                    Said out loud, because the two kinds of entry behave differently and
                    the difference is the thing people were confused by. A board reading
                    is folded into the model's own figure by the next Assess; a logged
                    requirement change and a claimed remediation step are not in any
                    feature vector yet, so they keep counting until they are.
                  */
                  <span className="ml-2 rounded bg-surface-3 px-1.5 py-0.5 text-[11px] font-medium text-ink-3">
                    stands after Assess
                  </span>
                ) : null}
              </span>
              <span className="shrink-0 text-xs text-ink-3">{formatWhen(adjustment.createdAt)}</span>
            </li>
          ))}
        </ul>
      )}
      <p className="flex items-start gap-1.5 border-t border-line px-4 py-2 text-xs text-ink-3">
        <AlertTriangle className="mt-px size-3 shrink-0" aria-hidden />
        Points are an agreed ranking of how much each event disturbs a plan, not a measured
        quantity. Re-assessing re-scores the models and keeps everything the board has added or
        taken off since — those entries are marked <em>stands after Assess</em>, and each one
        goes when the board does, a card unblocked or a task finished. A claimed fix is
        released from here once the model stops offering that step, because the fall is then
        in its own score instead.
      </p>
    </Card>
  );
}
