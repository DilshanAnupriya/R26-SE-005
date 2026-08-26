"use client";

import { useState } from "react";
import { ArrowDown, ArrowUp, Check, Info, Loader2, OctagonAlert, TriangleAlert } from "lucide-react";
import { Card, CardHeader } from "@/components/design/surfaces";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import type {
  PredictionDriver,
  RiskAssessment,
  RiskRemediationStep,
  RiskType,
  SprintRisk,
} from "@/lib/api/ai-schemas";
import { cn } from "@/lib/utils";
import { RISK_MODELS, RISK_TYPE_FOR_KEY, type RiskModelKey } from "../risk-models";
import { asPercent, SEVERITY_COLOUR, SeverityBadge, type RiskSeverityLabel } from "./risk-gauge";

/** What the parent hands down so a tick can be recorded. */
export type StepToggle = (input: { riskType: RiskType; stepKey: string; done: boolean }) => void;

/**
 * Why each of the three scores reads the way it does.
 *
 * <p>The gauges above say how bad; this says on what grounds. Each model returns the
 * readings that moved its own score — per-prediction SHAP contributions from the two
 * CatBoost models, exact per-reading shares from the collaboration one — and those are
 * the closest thing to an argument a team can have with a number. A gauge nobody can
 * interrogate is either believed on faith or ignored, and both are worse than one
 * somebody can say "that reading is stale" about.</p>
 *
 * <p>One risk at a time, behind tabs, because the three explanations answer different
 * questions and stacking fifteen readings down one column turns a diagnosis into a
 * scroll. Each trigger carries its own band as a dot, so choosing which one to open does
 * not require opening all three.</p>
 */

/**
 * Four model bands, three levels of alarm.
 *
 * <p>The models band into Low/Medium/High/Critical, but somebody deciding whether to act
 * this morning is asking a coarser question: is this a note, a problem, or an emergency.
 * The tone answers that. The badge beside it keeps the model's own band on screen, so
 * the coarsening never costs the finer reading.</p>
 */
type Tone = "info" | "warning" | "critical";

const TONE_FOR_SEVERITY: Record<RiskSeverityLabel, Tone> = {
  LOW: "info",
  MEDIUM: "warning",
  HIGH: "warning",
  CRITICAL: "critical",
};

const TONE = {
  info: {
    word: "Info",
    Icon: Info,
    callout: "border-blue/30 bg-blue-soft",
    text: "text-blue",
    lead: "Worth knowing, not worth stopping for.",
  },
  warning: {
    word: "Warning",
    Icon: TriangleAlert,
    callout: "border-amber/30 bg-amber-soft",
    text: "text-amber",
    lead: "Someone should look at this during the sprint.",
  },
  critical: {
    word: "Critical",
    Icon: OctagonAlert,
    callout: "border-red/30 bg-red-soft",
    text: "text-red",
    lead: "This wants a decision now, not at the review.",
  },
} as const;

/** What the badge and the tone should read: the adjusted band wherever the board has one. */
function bandOf(assessment?: RiskAssessment | null): RiskSeverityLabel | null {
  const severity = assessment?.adjustedSeverity ?? assessment?.severity;
  return severity ? (severity.toUpperCase() as RiskSeverityLabel) : null;
}

function scoreOf(assessment?: RiskAssessment | null): number | null {
  return assessment?.adjustedScore ?? assessment?.riskScore ?? null;
}

export function RiskExplanations({
  risk,
  steps,
  loading,
  onToggleStep,
  togglingStepKey,
}: {
  risk?: SprintRisk;
  steps?: RiskRemediationStep[];
  loading?: boolean;
  onToggleStep?: StepToggle;
  /** The step currently in flight, so only its own row shows a spinner. */
  togglingStepKey?: string | null;
}) {
  // Null until somebody picks a tab, so the panel opens on the loudest risk without
  // yanking the reader back to it every time the sprint is re-assessed.
  const [chosen, setChosen] = useState<RiskModelKey | null>(null);

  const loudest = [...RISK_MODELS].sort(
    (left, right) => (scoreOf(risk?.[right.key]) ?? -1) - (scoreOf(risk?.[left.key]) ?? -1),
  )[0].key;
  const scored = RISK_MODELS.filter((model) => risk?.[model.key] != null).length;

  return (
    <Card>
      <CardHeader
        title="Why each risk is on this sprint"
        subtitle={
          scored === 0
            ? "There is nothing to explain until the models have run."
            : "The readings behind each score, and what to do about the ones a team can move."
        }
      />

      {loading ? (
        <div className="space-y-3 p-4">
          <div className="h-8 animate-pulse rounded-lg bg-surface-3" />
          <div className="h-44 animate-pulse rounded-xl bg-surface-3" />
        </div>
      ) : (
        <Tabs
          value={chosen ?? loudest}
          onValueChange={(value) => setChosen(value as RiskModelKey)}
          className="p-4"
        >
          <TabsList className="w-full">
            {RISK_MODELS.map((model) => (
              <TabsTrigger key={model.key} value={model.key} className="gap-1.5">
                <SeverityDot assessment={risk?.[model.key]} />
                {model.title}
              </TabsTrigger>
            ))}
          </TabsList>

          {RISK_MODELS.map((model) => (
            <TabsContent key={model.key} value={model.key} className="mt-3">
              <RiskExplanation
                title={model.title}
                question={model.question}
                riskType={RISK_TYPE_FOR_KEY[model.key]}
                assessment={risk?.[model.key] ?? undefined}
                steps={(steps ?? []).filter(
                  (step) => step.riskType === RISK_TYPE_FOR_KEY[model.key],
                )}
                onToggleStep={onToggleStep}
                togglingStepKey={togglingStepKey}
              />
            </TabsContent>
          ))}
        </Tabs>
      )}
    </Card>
  );
}

/**
 * The band, on the trigger.
 *
 * <p>Hidden from screen readers on purpose: the panel it opens states the band in words
 * a line later, and a colour announced as "red circle" tells nobody anything.</p>
 */
function SeverityDot({ assessment }: { assessment?: RiskAssessment | null }) {
  const band = bandOf(assessment);
  return (
    <span
      aria-hidden
      className="size-2 shrink-0 rounded-full"
      style={{ background: band ? SEVERITY_COLOUR[band] : "var(--ink-3)" }}
    />
  );
}

function RiskExplanation({
  title,
  question,
  riskType,
  assessment,
  steps,
  onToggleStep,
  togglingStepKey,
}: {
  title: string;
  question: string;
  riskType: RiskType;
  assessment?: RiskAssessment | null;
  steps: RiskRemediationStep[];
  onToggleStep?: StepToggle;
  togglingStepKey?: string | null;
}) {
  const band = bandOf(assessment);

  if (!assessment || !band) {
    return (
      <div className="rounded-xl border border-dashed border-line px-4 py-8 text-center">
        <p className="text-sm font-medium">{title} has not been scored</p>
        <p className="mx-auto mt-1 max-w-md text-xs leading-relaxed text-ink-3">
          {question} There is not enough on the board for the model to answer yet, so there is
          nothing here to explain.
        </p>
      </div>
    );
  }

  const tone = TONE[TONE_FOR_SEVERITY[band]];
  const displayed = scoreOf(assessment);
  const delta = assessment.adjustmentDelta ?? 0;
  const drivers = [...assessment.drivers].sort(
    (left, right) => Math.abs(right.contribution) - Math.abs(left.contribution),
  );
  const strongest = drivers.reduce((max, driver) => Math.max(max, Math.abs(driver.contribution)), 0);
  /*
    Steps whose reading this scoring does not list. Normally there are none — a step only
    exists because a driver raised the score. But a claim outlives the scoring it was made
    against, so after a re-assessment that dropped the reading, or one the model could not
    be reached to price, the claim is still on the ledger with nowhere to be drawn. Left
    out, the gauge goes on paying for a step with no checkbox anywhere on screen: nothing
    to see the credit by, and no way to give it back.
  */
  const unplaced = steps.filter(
    (step) => !drivers.some((driver) => driver.feature === step.driverFeature),
  );

  return (
    <div className="space-y-3">
      <div className={cn("flex items-start gap-3 rounded-xl border p-3", tone.callout)}>
        <tone.Icon className={cn("mt-0.5 size-4 shrink-0", tone.text)} aria-hidden />
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <span className={cn("text-sm font-semibold", tone.text)}>{tone.word}</span>
            <SeverityBadge severity={band} />
            <span className="text-xs text-ink-2 tabular-nums">
              {asPercent(displayed)}% likelihood
              {assessment.confidence != null
                ? ` · model ${asPercent(assessment.confidence)}% sure`
                : null}
            </span>
          </div>
          <p className="mt-1 text-xs leading-relaxed text-ink-2">
            {assessment.explanation ?? tone.lead}
          </p>
        </div>
      </div>

      <StepProgress steps={steps} />

      {/*
        The gap between the model's own figure and the one on the gauge is itself part of
        the answer to "why is this risk here now" — where a score has been adjusted, some
        of the reason is not in the readings below at all.
      */}
      {delta !== 0 ? (
        <p className="rounded-lg border border-line bg-surface-2 px-3 py-2 text-xs leading-relaxed text-ink-2">
          The readings below scored it {asPercent(assessment.riskScore)}%.{" "}
          <span className={delta > 0 ? "font-semibold text-red" : "font-semibold text-green"}>
            {delta > 0 ? "+" : ""}
            {delta} points
          </span>{" "}
          of board activity since then {delta > 0 ? "raised" : "lowered"} it to{" "}
          {asPercent(displayed)}% — itemised under &ldquo;Since the last assessment&rdquo; below.
        </p>
      ) : null}

      <section>
        <h4 className="text-[11px] font-semibold tracking-[0.05em] text-ink-3 uppercase">
          What the model weighed, and what would move it
        </h4>

        {drivers.length === 0 && unplaced.length === 0 ? (
          <p className="mt-2 rounded-xl border border-dashed border-line px-4 py-6 text-center text-xs leading-relaxed text-ink-3">
            The model returned a score without a breakdown. It still stands, but nothing here can
            say which reading produced it — re-assess once the sprint has more history.
          </p>
        ) : (
          <>
            {drivers.length > 0 ? (
              <ul className="mt-2 divide-y divide-line overflow-hidden rounded-xl border border-line">
                {drivers.map((driver) => (
                  <DriverRow
                    key={driver.feature}
                    driver={driver}
                    strongest={strongest}
                    riskType={riskType}
                    steps={steps.filter((step) => step.driverFeature === driver.feature)}
                    onToggleStep={onToggleStep}
                    togglingStepKey={togglingStepKey}
                  />
                ))}
              </ul>
            ) : null}

            {unplaced.length > 0 ? (
              <div className="mt-2 overflow-hidden rounded-xl border border-line">
                <p className="border-b border-line bg-surface-2 px-3 py-2 text-[11px] leading-relaxed text-ink-3">
                  Claimed earlier, against a reading this scoring does not list. The points are
                  still coming off the score, so the step stays here — untick it to give them
                  back.
                </p>
                <ul className="space-y-1.5 p-3">
                  {unplaced.map((step) => (
                    <StepRow
                      key={step.stepKey}
                      step={step}
                      riskType={riskType}
                      onToggle={onToggleStep}
                      busy={togglingStepKey === step.stepKey}
                    />
                  ))}
                </ul>
              </div>
            ) : null}
            <p className="mt-2 flex items-start gap-1.5 text-[11px] leading-relaxed text-ink-3">
              <Info className="mt-px size-3 shrink-0" aria-hidden />
              Each bar is drawn against the strongest reading in this list, and the arrow says
              which way that reading pushed. The sizes explain this score against this model only
              — they do not compare across the three tabs. A step&rsquo;s points are the fall the
              model itself reports when that reading is moved to the stated value, so a reading
              with no step is one the model does not reward changing. Ticking takes the points
              off straight away and they stay off: each assessment re-prices the claim against
              the fresh reading, and releases it once the model reports the fall on its own.
            </p>
          </>
        )}
      </section>
    </div>
  );
}

/**
 * One reading, how far it pushed, and what would push it back.
 *
 * <p>The steps sit under the driver they answer rather than in a checklist of their own.
 * A list of remedies detached from the readings that produced them is generic advice —
 * every sprint's version looks the same, and the first team to notice that stops reading
 * it. Under the driver, "clear the blockers" is visibly the answer to <em>this</em>
 * sprint's four blocked tasks.</p>
 */
function DriverRow({
  driver,
  strongest,
  riskType,
  steps,
  onToggleStep,
  togglingStepKey,
}: {
  driver: PredictionDriver;
  strongest: number;
  riskType: RiskType;
  steps: RiskRemediationStep[];
  onToggleStep?: StepToggle;
  togglingStepKey?: string | null;
}) {
  const up = driver.direction === "INCREASE";
  const magnitude = Math.abs(driver.contribution);
  const share = strongest > 0 ? magnitude / strongest : 0;
  const Icon = up ? ArrowUp : ArrowDown;
  // Two driver shapes reach here. The models now send the label and the reading in
  // their own fields; assessments stored before they did have the reading baked into
  // the label as "points committed (34)", and those rows are still on screen until the
  // sprint is next assessed.
  const { name, reading } = splitReading(driver.label ?? driver.feature);
  const detail = driver.detail?.trim() || null;
  // A bare reading sits in the chip beside the name; a sentence gets its own line.
  const chip = reading ?? (detail && detail.length <= 12 && !detail.includes(" ") ? detail : null);
  const sentence = chip === detail ? null : detail;

  return (
    <li className="bg-surface px-3 py-2.5">
      <div className="flex items-center gap-3">
        <Icon className={cn("size-3.5 shrink-0", up ? "text-red" : "text-green")} aria-hidden />

        <span className="min-w-0 flex-1">
          <span className="text-sm">{name}</span>
          {chip ? (
            <span className="ml-1.5 rounded bg-surface-3 px-1.5 py-0.5 text-[11px] text-ink-3 tabular-nums">
              {chip}
            </span>
          ) : null}
          {sentence ? <span className="block text-[11px] text-ink-3">{sentence}</span> : null}
          <span className="sr-only">
            {up ? " raises this risk" : " lowers this risk"}, weight {magnitude.toFixed(2)}
          </span>
        </span>

        <span
          className="relative hidden h-2 w-32 shrink-0 rounded-full bg-surface-3 sm:block"
          aria-hidden
        >
          <span className="absolute inset-y-0 left-1/2 w-px -translate-x-1/2 bg-line-strong" />
          <span
            className="absolute inset-y-0 rounded-full transition-[width] duration-500 ease-out"
            style={
              up
                ? { left: "50%", width: `${share * 50}%`, background: "var(--red)" }
                : { right: "50%", width: `${share * 50}%`, background: "var(--green)" }
            }
          />
        </span>

        <span
          className="w-12 shrink-0 text-right text-xs text-ink-3 tabular-nums"
          title={`Contribution ${driver.contribution}`}
          aria-hidden
        >
          {magnitude === 0 ? "0.00" : `${up ? "+" : "−"}${magnitude.toFixed(2)}`}
        </span>
      </div>

      {steps.length > 0 ? (
        <ul className="mt-2 ml-6 space-y-1.5">
          {steps.map((step) => (
            <StepRow
              key={step.stepKey}
              step={step}
              riskType={riskType}
              onToggle={onToggleStep}
              busy={togglingStepKey === step.stepKey}
            />
          ))}
        </ul>
      ) : up ? (
        /*
          Only for a driver arguing the sprint is in trouble. Saying "nothing to do here"
          under a reading that is already helping would read as a complaint about it.
        */
        <p className="mt-1.5 ml-6 text-[11px] leading-relaxed text-ink-3">
          No step here: either the sprint cannot move this reading — effort already spent, a
          fact about the team — or the model was asked and moving it did not lower the score.
        </p>
      ) : null}
    </li>
  );
}

/**
 * One move, with what it is currently worth.
 *
 * <p>A native checkbox rather than a button that looks like one: this is a list of
 * things that are or are not done, and the platform control is what carries that meaning
 * to a keyboard and a screen reader for free.</p>
 *
 * <p>The points are stated on the row before it is ticked. A control that changes a risk
 * score without saying by how much is one people click once and then stop trusting — as
 * is one whose effect the next press of <em>Assess</em> quietly takes back, which is why
 * a claim now survives a re-scoring rather than expiring with it.</p>
 */
function StepRow({
  step,
  riskType,
  onToggle,
  busy,
}: {
  step: RiskRemediationStep;
  riskType: RiskType;
  onToggle?: StepToggle;
  busy?: boolean;
}) {
  const points = Math.round(step.points);

  return (
    <li>
      <label
        className={cn(
          "flex cursor-pointer items-start gap-2 rounded-lg border px-2.5 py-2 transition-colors",
          step.done
            ? "border-green/30 bg-green-soft/60"
            : "border-line bg-surface-2 hover:border-line-strong",
          (busy || !onToggle) && "cursor-default opacity-70",
        )}
      >
        <input
          type="checkbox"
          className="mt-0.5 size-3.5 shrink-0 accent-[var(--green)]"
          checked={step.done}
          disabled={busy || !onToggle}
          onChange={(event) =>
            onToggle?.({ riskType, stepKey: step.stepKey, done: event.target.checked })
          }
        />

        <span className="min-w-0 flex-1">
          <span className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
            <span className={cn("text-xs font-medium", step.done && "text-ink-2")}>
              {step.title}
            </span>
            <span
              className={cn(
                "shrink-0 rounded px-1.5 py-0.5 text-[10px] font-semibold tabular-nums",
                step.done ? "bg-green-soft text-green" : "bg-surface-3 text-ink-3",
              )}
            >
              {step.done ? `−${points} pts applied` : `−${points} pts`}
            </span>
            {step.baselineScore != null && step.projectedScore != null ? (
              /*
                The measurement itself, on the row. The points beside it are this
                subtraction — showing where they came from is what separates a figure
                the model produced from one the interface asserted.
              */
              <span className="shrink-0 text-[10px] text-ink-3 tabular-nums">
                model: {asPercent(step.baselineScore)}% &rarr; {asPercent(step.projectedScore)}%
              </span>
            ) : null}
            {busy ? <Loader2 className="size-3 animate-spin text-ink-3" aria-hidden /> : null}
            {step.done && !busy ? (
              <Check className="size-3 shrink-0 text-green" aria-hidden />
            ) : null}
          </span>
          {/*
            The method, where one is written, and the model's own sentence where it is
            not. Never both: the measurement is already on the row as "53% → 35%" and the
            points beside it, so repeating it in prose would push the useful half — how
            to actually go about this — below the fold of a dense list.
          */}
          <span className="mt-1 block text-[11px] leading-relaxed text-ink-2">
            {step.how ? (
              <>
                <span className="font-semibold text-ink-3">How: </span>
                {step.how}
              </>
            ) : (
              <span className="text-ink-3">{step.detail}</span>
            )}
          </span>
        </span>
      </label>
    </li>
  );
}

/**
 * How much of this risk's checklist has been claimed, and what it is holding off.
 *
 * <p>Sits above the readings because it answers the question a lead asks after ticking
 * something: did that actually do anything. The wording is careful about which half of
 * the number is a measurement — the points are real and currently applied, and what they
 * bought stays a claim, held across assessments, until the model reads the change off
 * the board itself and the fall moves into its own score.</p>
 */
function StepProgress({ steps }: { steps: RiskRemediationStep[] }) {
  if (steps.length === 0) {
    return null;
  }
  const done = steps.filter((step) => step.done);
  const applied = done.reduce((total, step) => total + step.points, 0);

  return (
    <div className="flex flex-wrap items-center gap-x-2 gap-y-1 rounded-lg border border-line bg-surface-2 px-3 py-2 text-xs">
      <span className="font-medium tabular-nums">
        {done.length} of {steps.length} step{steps.length === 1 ? "" : "s"} done
      </span>
      {applied > 0 ? (
        <span className="font-semibold text-green tabular-nums">
          {"−"}
          {Math.round(applied)} pts off this risk
        </span>
      ) : (
        <span className="text-ink-3">Nothing claimed against this risk yet.</span>
      )}
      <span className="text-ink-3">
        {applied > 0
          ? "Still held after a re-assessment, until the model reads the change for itself."
          : "Each step below is priced by re-running the model with that one reading moved."}
      </span>
    </div>
  );
}

/**
 * Drivers arrive as "points committed (34)" — one string holding two facts.
 *
 * <p>Split so the reading can sit in its own chip. The name is what the model looked at
 * and the number is what it found there, and running them together as prose makes a list
 * of five of them harder to scan than it needs to be.</p>
 */
function splitReading(feature: string): { name: string; reading: string | null } {
  const match = /^(.*\S)\s*\(([^()]*)\)\s*$/.exec(feature);
  return match ? { name: match[1], reading: match[2] } : { name: feature, reading: null };
}
