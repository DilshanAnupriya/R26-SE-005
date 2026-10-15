"use client";

import { useState } from "react";
import { ArrowDown, ArrowUp, Check, Info, Loader2, OctagonAlert, TriangleAlert } from "lucide-react";
import { Card, CardHeader } from "@/components/design/surfaces";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import type {
  PredictionDriver,
  RiskAdjustment,
  RiskAssessment,
  RiskRemediationStep,
  RiskType,
  ShapFix,
  SprintRisk,
} from "@/lib/api/ai-schemas";
import { cn } from "@/lib/utils";
import { boardEventsFor, type BoardEvent } from "../over-budget-guidance";
import { RISK_MODELS, RISK_TYPE_FOR_KEY, type RiskModelKey } from "../risk-models";
import { asPercent, SEVERITY_COLOUR, SeverityBadge, type RiskSeverityLabel } from "./risk-gauge";

/** What the parent hands down so a tick can be recorded. */
export type StepToggle = (input: { riskType: RiskType; stepKey: string; done: boolean }) => void;

/** The same, for the one finding that is a logged change rather than a board reading. */
export type AbsorbToggle = (input: { adjustmentId: string; absorbed: boolean }) => void;

/** The ledger key a logged requirement change is written under. Mirrors the server’s. */
const REGISTER_PREFIX = "REQ_CHANGE:";

/**
 * One logged change, and the claim answering it if somebody has made one.
 *
 * <p>Paired on the charge’s own source key rather than on its row id, because that is
 * the pairing the server writes — and the one withdrawing the change takes apart.</p>
 */
function loggedChangesFor(
  adjustments: RiskAdjustment[],
  riskType: RiskType,
): { charge: RiskAdjustment; credit?: RiskAdjustment }[] {
  return adjustments
    .filter(
      (entry) =>
        entry.riskType === riskType &&
        entry.survivesScoring &&
        entry.sourceKey?.startsWith(REGISTER_PREFIX),
    )
    .map((charge) => ({
      charge,
      credit: adjustments.find(
        (entry) => entry.sourceKey === `ABSORBED:${charge.sourceKey}`,
      ),
    }));
}

/**
 * What ticking one is worth, before the server has priced it.
 *
 * <p>Mirrors {@code SprintRiskAbsorptionService.reliefFor}: five-eighths of the charge,
 * bounded so a point always stays standing. A preview, not the decision — the server
 * recomputes it from the entry it actually loads.</p>
 */
function reliefFor(charge: RiskAdjustment): number {
  return Math.max(0, Math.min(Math.round(charge.delta * 0.625), charge.delta - 1));
}

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
  adjustments,
  steps,
  loading,
  onToggleStep,
  togglingStepKey,
  onAbsorb,
  absorbingId,
}: {
  risk?: SprintRisk;
  /** The whole ledger, so each tab can show the logged changes charged to its risk. */
  adjustments?: RiskAdjustment[];
  steps?: RiskRemediationStep[];
  loading?: boolean;
  onToggleStep?: StepToggle;
  /** The step currently in flight, so only its own row shows a spinner. */
  togglingStepKey?: string | null;
  onAbsorb?: AbsorbToggle;
  /** The logged change currently in flight, for the same reason. */
  absorbingId?: string | null;
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
                changes={loggedChangesFor(adjustments ?? [], RISK_TYPE_FOR_KEY[model.key])}
                /*
                  Over-budget only, for now: it is the risk the board charges most
                  directly — blocked cards, reopens, tasks past their estimate — and the
                  one whose readings had the most reasons with no remedy beside them.
                */
                boardEvents={
                  RISK_TYPE_FOR_KEY[model.key] === "OVER_BUDGET"
                    ? boardEventsFor(
                        adjustments ?? [],
                        RISK_TYPE_FOR_KEY[model.key],
                        risk?.[model.key]?.createdAt,
                      )
                    : undefined
                }
                advise={RISK_TYPE_FOR_KEY[model.key] === "OVER_BUDGET"}
                onToggleStep={onToggleStep}
                togglingStepKey={togglingStepKey}
                onAbsorb={onAbsorb}
                absorbingId={absorbingId}
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
  changes,
  boardEvents,
  advise,
  onToggleStep,
  togglingStepKey,
  onAbsorb,
  absorbingId,
}: {
  title: string;
  question: string;
  riskType: RiskType;
  assessment?: RiskAssessment | null;
  steps: RiskRemediationStep[];
  changes: { charge: RiskAdjustment; credit?: RiskAdjustment }[];
  /** The board events counting toward this score, where the tab itemises them itself. */
  boardEvents?: { raised: BoardEvent[]; lowered: BoardEvent[] };
  /** Split the readings by direction and write a fix under every one raising the score. */
  advise?: boolean;
  onToggleStep?: StepToggle;
  togglingStepKey?: string | null;
  onAbsorb?: AbsorbToggle;
  absorbingId?: string | null;
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
          {asPercent(displayed)}% —{" "}
          {boardEvents
            ? "itemised just below."
            : "itemised under “Since the last assessment” below."}
        </p>
      ) : null}

      {boardEvents ? <BoardEvents {...boardEvents} /> : null}

      {/*
        Directly under the note about the adjustment, because for this risk these entries
        usually *are* the adjustment. They lived only in the ledger card at the foot of
        the page, which mixes all three risks together — so the one finding somebody had
        gone to the trouble of writing down was the one that never appeared beside the
        score it moved.
      */}
      {changes.length > 0 ? (
        <section>
          <h4 className="text-[11px] font-semibold tracking-[0.05em] text-ink-3 uppercase">
            Logged changes the model cannot see
          </h4>
          <p className="mt-1 text-[11px] leading-relaxed text-ink-3">
            Written down by hand and charged to this sprint. No reading below carries a
            logged change, so re-assessing cannot fold these away — ticking one is the only
            thing that brings it down, and it never brings it down in full.
          </p>
          <ul className="mt-2 space-y-1.5">
            {changes.map(({ charge, credit }) => (
              <LoggedChangeRow
                key={charge.id}
                charge={charge}
                credit={credit}
                onAbsorb={onAbsorb}
                busy={absorbingId === charge.id}
              />
            ))}
          </ul>
        </section>
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
            {/*
              Split by direction where the tab advises, so "what pushed it up" and "what
              is holding it down" are two lists a reader can take in separately instead
              of one ranked list with arrows to decode. The bars stay drawn against the
              same strongest reading, so a short bar means the same in both.
            */}
            {(advise
              ? [
                  {
                    heading: "Raising the risk",
                    items: drivers.filter((driver) => driver.direction === "INCREASE"),
                  },
                  {
                    heading: "Lowering the risk",
                    items: drivers.filter((driver) => driver.direction !== "INCREASE"),
                  },
                ]
              : [{ heading: null, items: drivers }]
            )
              .filter((group) => group.items.length > 0)
              .map((group) => (
                <div key={group.heading ?? "all"} className="mt-2">
                  {group.heading ? (
                    <p className="mb-1 text-[11px] font-medium text-ink-2">
                      {group.heading}{" "}
                      <span className="text-ink-3 tabular-nums">({group.items.length})</span>
                    </p>
                  ) : null}
                  <ul className="divide-y divide-line overflow-hidden rounded-xl border border-line">
                    {group.items.map((driver) => (
                      <DriverRow
                        key={driver.feature}
                        driver={driver}
                        strongest={strongest}
                        riskType={riskType}
                        steps={steps.filter((step) => step.driverFeature === driver.feature)}
                        advise={advise}
                        onToggleStep={onToggleStep}
                        togglingStepKey={togglingStepKey}
                      />
                    ))}
                  </ul>
                </div>
              ))}

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
              {advise
                ? " A SHAP fix is measured with only its one change made, and several can name the same change — so their effects overlap rather than add up."
                : null}
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
  advise,
  onToggleStep,
  togglingStepKey,
}: {
  driver: PredictionDriver;
  strongest: number;
  riskType: RiskType;
  steps: RiskRemediationStep[];
  /** Show the model's SHAP fix under a raising reading, or say why there is none. */
  advise?: boolean;
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
      ) : up && advise && driver.fix ? (
        <ShapFixNote fix={driver.fix} />
      ) : up && advise ? (
        /*
          Said rather than papered over with written advice: a fix on this tab is the
          model's own measurement, and a score from a model with no SHAP values has none.
        */
        <p className="mt-1.5 ml-6 text-[11px] leading-relaxed text-ink-3">
          No SHAP fix for this reading: this score came from a model that doesn&rsquo;t
          report SHAP values, so no fix could be measured. Re-assess with the trained
          over-budget model running to get one.
        </p>
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
 * The fix the model measured for one raising reading, from its own SHAP values.
 *
 * <p>The move and the two probabilities sit on the first line because they are the
 * measurement — what to change, to what, and what the model said when it was re-run with
 * that change. The sentence under it is the service's own account of how it got there:
 * whether the reading was moved itself, or was history and had to be offset by something
 * the team can change.</p>
 *
 * <p>No tick. A step's checkbox takes points off the gauge, and these were measured one
 * at a time — two fixes can name the same move — so crediting each would count one
 * change twice. The next assessment is what says whether it was done.</p>
 */
function ShapFixNote({ fix }: { fix: ShapFix }) {
  const move = fix.move;
  const measured = move != null && fix.probability_now != null && fix.probability_after != null;

  return (
    <div className="mt-1.5 ml-6 rounded-lg border border-line bg-surface-2 px-2.5 py-2 text-[11px] leading-relaxed">
      <span className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
        <span className="rounded bg-blue-soft px-1.5 py-0.5 text-[10px] font-semibold text-blue">
          {fix.kind === "offset" ? "Offset from SHAP" : "Fix from SHAP"}
        </span>
        {measured ? (
          <>
            <span className="font-medium text-ink">
              {move.label ?? move.feature}: {formatValue(move.from)} &rarr; {formatValue(move.to)}
            </span>
            <span className="text-[10px] text-ink-3 tabular-nums">
              model: {asPercent(fix.probability_now)}% &rarr; {asPercent(fix.probability_after)}%
            </span>
          </>
        ) : null}
      </span>
      <span className={cn("mt-1 block", measured ? "text-ink-2" : "text-ink-3")}>{fix.summary}</span>
    </div>
  );
}

/** A reading as the model saw it: whole numbers bare, the rest to two places. */
function formatValue(value: number | string | null | undefined): string {
  if (value == null) return "—";
  if (typeof value === "string") return value;
  return Number.isInteger(value) ? `${value}` : `${Math.round(value * 100) / 100}`;
}

/**
 * What the board has done to this score: every charge and credit still counting.
 *
 * <p>The model's readings describe the sprint as it was planned. These describe what has
 * happened on the board since — a card blocked, a task past its estimate, one finished
 * on time — and they move the gauge just as much, so leaving them to a ledger at the foot
 * of the page meant the tab explaining over-budget left out half of its own score.</p>
 *
 * <p>Charges and credits are listed apart. A charge carries how to clear it, because that
 * is what somebody reading it wants next; a credit carries why it counts in the sprint's
 * favour, because there is nothing to fix about a task finishing on time.</p>
 */
function BoardEvents({ raised, lowered }: { raised: BoardEvent[]; lowered: BoardEvent[] }) {
  const sum = (events: BoardEvent[]) =>
    Math.round(events.reduce((total, { entry }) => total + Math.abs(entry.delta), 0));

  return (
    <section>
      <h4 className="text-[11px] font-semibold tracking-[0.05em] text-ink-3 uppercase">
        What the board has done to this score
      </h4>

      {raised.length === 0 && lowered.length === 0 ? (
        <p className="mt-2 rounded-xl border border-dashed border-line px-4 py-4 text-center text-xs leading-relaxed text-ink-3">
          No board activity is moving this score right now. Blocked cards, reopens and tasks
          past their estimate will show up here as they happen.
        </p>
      ) : (
        <div className="mt-2 space-y-3">
          {raised.length > 0 ? (
            <div>
              <p className="mb-1 text-[11px] font-medium text-ink-2">
                Raised it{" "}
                <span className="font-semibold text-red tabular-nums">+{sum(raised)} pts</span>
              </p>
              <ul className="space-y-1.5">
                {raised.map((event) => (
                  <BoardEventRow key={event.entry.id} event={event} up />
                ))}
              </ul>
            </div>
          ) : null}

          {lowered.length > 0 ? (
            <div>
              <p className="mb-1 text-[11px] font-medium text-ink-2">
                Lowered it{" "}
                <span className="font-semibold text-green tabular-nums">
                  {"−"}
                  {sum(lowered)} pts
                </span>
              </p>
              <ul className="space-y-1.5">
                {lowered.map((event) => (
                  <BoardEventRow key={event.entry.id} event={event} up={false} />
                ))}
              </ul>
            </div>
          ) : null}
        </div>
      )}
    </section>
  );
}

function BoardEventRow({ event, up }: { event: BoardEvent; up: boolean }) {
  const points = Math.round(Math.abs(event.entry.delta) * 10) / 10;
  const Icon = up ? ArrowUp : ArrowDown;

  return (
    <li className="flex items-start gap-2 rounded-lg border border-line bg-surface-2 px-2.5 py-2">
      <Icon
        className={cn("mt-0.5 size-3.5 shrink-0", up ? "text-red" : "text-green")}
        aria-hidden
      />
      <span className="min-w-0 flex-1">
        <span className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
          <span className="text-xs font-medium">{event.entry.reason}</span>
          <span
            className={cn(
              "shrink-0 rounded px-1.5 py-0.5 text-[10px] font-semibold tabular-nums",
              up ? "bg-red-soft text-red" : "bg-green-soft text-green",
            )}
          >
            {up ? "+" : "−"}
            {points} pts
          </span>
        </span>
        <span className="mt-0.5 block text-[11px] leading-relaxed text-ink-2">
          {up ? <span className="font-semibold text-ink-3">How to fix: </span> : null}
          <span className={up ? undefined : "text-ink-3"}>{event.note}</span>
        </span>
      </span>
    </li>
  );
}

/**
 * One logged requirement change, and the claim that the sprint was re-planned around it.
 *
 * <p>The tick says something narrower than "fixed", because a requirement change cannot
 * be unmade. It says the plan was adjusted to take it — something was moved out, or
 * re-negotiated — which is a claim about the sprint no model can read, so it is recorded
 * the way a claimed step is and reversed the same way.</p>
 *
 * <p>A change that <em>lowered</em> the risk is stated rather than offered. There is
 * nothing to plan around in a sprint getting easier, and a checkbox beside one would
 * invite somebody to try.</p>
 */
function LoggedChangeRow({
  charge,
  credit,
  onAbsorb,
  busy,
}: {
  charge: RiskAdjustment;
  credit?: RiskAdjustment;
  onAbsorb?: AbsorbToggle;
  busy?: boolean;
}) {
  const charged = Math.round(Math.abs(charge.delta));

  if (charge.delta <= 0) {
    return (
      <li className="flex items-start gap-2 rounded-lg border border-line bg-surface-2 px-2.5 py-2">
        <ArrowDown className="mt-0.5 size-3.5 shrink-0 text-green" aria-hidden />
        <span className="min-w-0 flex-1 text-xs">{charge.reason}</span>
        <span className="shrink-0 rounded bg-green-soft px-1.5 py-0.5 text-[10px] font-semibold text-green tabular-nums">
          −{charged} pts
        </span>
      </li>
    );
  }

  const absorbed = credit != null;
  // Magnitude first, then round. `Math.round(-2.5)` is -2 — rounding a negative half
  // goes toward zero, so rounding the signed credit made the row say it had applied one
  // point fewer than the preview beside it had just promised.
  const relief = absorbed ? Math.round(Math.abs(credit.delta)) : reliefFor(charge);

  return (
    <li>
      <label
        className={cn(
          "flex cursor-pointer items-start gap-2 rounded-lg border px-2.5 py-2 transition-colors",
          absorbed
            ? "border-green/30 bg-green-soft/60"
            : "border-line bg-surface-2 hover:border-line-strong",
          (busy || !onAbsorb) && "cursor-default opacity-70",
        )}
      >
        <input
          type="checkbox"
          className="mt-0.5 size-3.5 shrink-0 accent-[var(--green)]"
          checked={absorbed}
          disabled={busy || !onAbsorb}
          onChange={(event) =>
            onAbsorb?.({ adjustmentId: charge.id, absorbed: event.target.checked })
          }
        />

        <span className="min-w-0 flex-1">
          <span className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
            <span className={cn("text-xs font-medium", absorbed && "text-ink-2")}>
              {charge.reason}
            </span>
            <span
              className={cn(
                "shrink-0 rounded px-1.5 py-0.5 text-[10px] font-semibold tabular-nums",
                absorbed ? "bg-green-soft text-green" : "bg-red-soft text-red",
              )}
            >
              {absorbed ? `+${charged}, −${relief} applied` : `+${charged} pts`}
            </span>
          </span>
          <span className="mt-0.5 block text-[11px] leading-relaxed text-ink-3">
            {absorbed
              ? `Re-planned for — ${relief} of its ${charged} points are off. The rest stands, because the requirement did move.`
              : `Tick once the plan has been adjusted around it — worth ${relief} of its ${charged} points.`}
          </span>
        </span>

        {busy ? (
          <Loader2 className="mt-0.5 size-3.5 shrink-0 animate-spin text-ink-3" aria-hidden />
        ) : null}
      </label>
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
