"use client";

import { useState } from "react";
import { ArrowDown, ArrowUp, Info, OctagonAlert, TriangleAlert } from "lucide-react";
import { Card, CardHeader } from "@/components/design/surfaces";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import type { PredictionDriver, RiskAssessment, SprintRisk } from "@/lib/api/ai-schemas";
import { cn } from "@/lib/utils";
import { RISK_MODELS, type RiskModelKey } from "../risk-models";
import { asPercent, SEVERITY_COLOUR, SeverityBadge, type RiskSeverityLabel } from "./risk-gauge";

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

export function RiskExplanations({ risk, loading }: { risk?: SprintRisk; loading?: boolean }) {
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
            : "The readings behind each score, ranked by how far each one moved it."
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
                assessment={risk?.[model.key] ?? undefined}
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
  assessment,
}: {
  title: string;
  question: string;
  assessment?: RiskAssessment | null;
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
          What the model weighed
        </h4>

        {drivers.length === 0 ? (
          <p className="mt-2 rounded-xl border border-dashed border-line px-4 py-6 text-center text-xs leading-relaxed text-ink-3">
            The model returned a score without a breakdown. It still stands, but nothing here can
            say which reading produced it — re-assess once the sprint has more history.
          </p>
        ) : (
          <>
            <ul className="mt-2 divide-y divide-line overflow-hidden rounded-xl border border-line">
              {drivers.map((driver) => (
                <DriverRow key={driver.feature} driver={driver} strongest={strongest} />
              ))}
            </ul>
            <p className="mt-2 flex items-start gap-1.5 text-[11px] leading-relaxed text-ink-3">
              <Info className="mt-px size-3 shrink-0" aria-hidden />
              Each bar is drawn against the strongest reading in this list, and the arrow says
              which way that reading pushed. The sizes explain this score against this model only
              — they do not compare across the three tabs.
            </p>
          </>
        )}
      </section>
    </div>
  );
}

/**
 * One reading, and how far it pushed.
 *
 * <p>A diverging bar from a centre line rather than a plain one, because the
 * contributions are signed and some of them argue the sprint is fine. A list where
 * "carry-over rate 0.4" and "acceptance criteria per story 3.1" both grow rightwards
 * reads as five reasons to worry, when two of them are the model's reasons not to.</p>
 */
function DriverRow({ driver, strongest }: { driver: PredictionDriver; strongest: number }) {
  const up = driver.direction === "INCREASE";
  const magnitude = Math.abs(driver.contribution);
  const share = strongest > 0 ? magnitude / strongest : 0;
  const { name, reading } = splitReading(driver.label ?? driver.feature);
  const Icon = up ? ArrowUp : ArrowDown;

  return (
    <li className="flex items-center gap-3 bg-surface px-3 py-2.5">
      <Icon className={cn("size-3.5 shrink-0", up ? "text-red" : "text-green")} aria-hidden />

      <span className="min-w-0 flex-1">
        <span className="text-sm">{name}</span>
        {reading ? (
          <span className="ml-1.5 rounded bg-surface-3 px-1.5 py-0.5 text-[11px] text-ink-3 tabular-nums">
            {reading}
          </span>
        ) : null}
        {driver.detail ? <span className="block text-xs text-ink-3">{driver.detail}</span> : null}
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
    </li>
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
