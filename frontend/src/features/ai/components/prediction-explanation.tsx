"use client";

import { ArrowDown, ArrowUp, Info } from "lucide-react";
import type { PredictionDriver, PredictionEnvelope } from "@/lib/api/ai-schemas";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";

/**
 * The explanation half of every AI panel: confidence, ranked drivers, one sentence.
 *
 * <p>Shared by all four models on purpose. A number with no reasons attached is one a
 * planning meeting either believes blindly or ignores entirely, and both are worse than
 * a number the team can argue with. Making the explanation a single component means no
 * panel can ship without one.</p>
 */

export function ConfidenceBar({ confidence }: { confidence?: number | null }) {
  if (confidence == null) return null;
  const pct = Math.round(confidence * 100);

  // Three bands, not a gradient: the difference between 61% and 64% is not something a
  // colour should assert, but "thin evidence" versus "well supported" is.
  const tone =
    pct >= 70
      ? "bg-green"
      : pct >= 45
        ? "bg-amber"
        : "bg-ink-3";
  const label =
    pct >= 70 ? "Well supported" : pct >= 45 ? "Moderately supported" : "Thin evidence";

  return (
    <div className="flex items-center gap-2">
      <div
        className="h-1.5 w-24 overflow-hidden rounded-full bg-surface-3"
        role="meter"
        aria-valuenow={pct}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label={`Model confidence: ${pct} percent, ${label}`}
      >
        {/* scaleX rather than width: width is a layout property, so animating it runs
            layout on every frame, while a transform is composited. The bar also fills
            from its left edge rather than its centre, which is what transform-origin
            is doing here — the default would have it grow outward from the middle. */}
        <div
          className={cn(
            "h-full w-full origin-left rounded-full",
            "transition-transform duration-500 ease-[var(--ease-out-quart)]",
            "motion-reduce:transition-none",
            tone,
          )}
          style={{ transform: `scaleX(${pct / 100})` }}
        />
      </div>
      <span className="text-xs text-ink-2">
        <span className="tabular-nums">{pct}%</span> confidence · {label}
      </span>
    </div>
  );
}

export function DriverList({ drivers }: { drivers: PredictionDriver[] }) {
  if (drivers.length === 0) {
    return (
      <p className="text-xs text-ink-2">
        No distinguishing factors — the model found nothing notable either way.
      </p>
    );
  }

  return (
    <ul className="space-y-1.5 stagger-children">
      {drivers.slice(0, 4).map((driver) => {
        const up = driver.direction === "INCREASE";
        const Icon = up ? ArrowUp : ArrowDown;
        return (
          <li key={driver.feature} className="flex items-start gap-2 text-xs">
            <Icon
              className={cn("mt-0.5 size-3 shrink-0", up ? "text-amber" : "text-green")}
              aria-hidden
            />
            <span className="min-w-0">
              <span className="font-medium">{driver.label ?? driver.feature}</span>
              {driver.detail ? <span className="text-ink-2"> — {driver.detail}</span> : null}
            </span>
            <span className="sr-only">
              {up ? "increases" : "decreases"} the prediction
            </span>
          </li>
        );
      })}
    </ul>
  );
}

/** A range, rendered as a range. A point estimate alone reads as a commitment. */
export function BoundsLabel({
  lower,
  upper,
  format = (value: number) => String(value),
}: {
  lower?: number | null;
  upper?: number | null;
  format?: (value: number) => string;
}) {
  if (lower == null || upper == null) return null;
  return (
    <span className="text-xs text-muted-foreground tabular-nums">
      likely {format(lower)}–{format(upper)}
    </span>
  );
}

export function DegradedNotice({ prediction }: { prediction: PredictionEnvelope }) {
  if (!prediction.degraded) return null;
  return (
    <p className="flex items-start gap-1.5 rounded-md bg-amber-500/10 px-2 py-1.5 text-xs text-amber-700 dark:text-amber-400">
      <Info className="mt-0.5 size-3 shrink-0" aria-hidden />
      <span>
        The model answered slowly or with limited detail. Treat this as a starting point
        rather than an answer.
      </span>
    </p>
  );
}

/** Cache hits are shown, quietly. An estimate from four hours ago is still an estimate. */
export function PredictionMeta({ prediction }: { prediction: PredictionEnvelope }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        {/* Provenance, not a headline. In a narrow rail it truncates rather than
            wrapping and pushing the panel title onto two lines — the full string is a
            hover away, and the model code alone is enough to recognise at a glance. */}
        <span className="min-w-0 cursor-default truncate font-mono text-2xs text-ink-3">
          {prediction.modelCode}
          {prediction.cacheHit ? " · cached" : null}
        </span>
      </TooltipTrigger>
      <TooltipContent side="bottom" className="max-w-xs">
        {`${prediction.modelCode} v${prediction.modelVersion}. `}
        {prediction.cacheHit
          ? "Served from a previous run because nothing about this item has changed since."
          : `Computed in ${prediction.latencyMs ?? 0}ms.`}
      </TooltipContent>
    </Tooltip>
  );
}

/** The Fibonacci ladder story points are reported on. */
const FIB = [1, 2, 3, 5, 8, 13, 21];

/**
 * Where inside its Fibonacci band the model's own number actually fell.
 *
 * <p>Story points snap to the nearest Fibonacci value, so a continuous output of 6.8,
 * 8.5 or 9.9 all arrives as 8. That is right for planning and it hides something a
 * planner would want: two stories both shown as 8 can be half a size apart.</p>
 *
 * <p>The wording matters here and an earlier version got it wrong. A raw 6.8 snapped to
 * 8 is <em>not</em> "close to 5" — it is nearer 8 than 5, which is exactly why it
 * snapped to 8. It is a <em>low</em> 8. Saying "close to 5" invites the reader to
 * conclude the model wanted 5 and was overruled, which is the opposite of what
 * happened.</p>
 *
 * <p>Nearest-snapping means the raw value can never be more than halfway to a
 * neighbour, so this only fires in the outer third of a band — past that the number
 * would have snapped the other way.</p>
 */
export function SnapLabel({ raw, snapped }: { raw?: number | null; snapped?: number | null }) {
  if (raw == null || snapped == null) return null;

  const index = FIB.indexOf(snapped);
  if (index === -1) return null;

  const below = FIB[index - 1];
  const above = FIB[index + 1];

  // Distance to the neighbouring size, as a share of the gap to it. Near 1 means the
  // model was almost as happy with that neighbour.
  const toward = raw < snapped ? below : above;
  if (toward == null) return null;
  const share = Math.abs(raw - snapped) / Math.abs(toward - snapped);
  if (share < 0.34) return null;

  return (
    <span className="text-xs tabular-nums text-muted-foreground">
      model said {raw.toFixed(1)} — a {raw < snapped ? "low" : "high"} {snapped}
    </span>
  );
}
