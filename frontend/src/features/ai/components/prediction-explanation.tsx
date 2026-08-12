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
      ? "bg-emerald-500"
      : pct >= 45
        ? "bg-amber-500"
        : "bg-muted-foreground";
  const label =
    pct >= 70 ? "Well supported" : pct >= 45 ? "Moderately supported" : "Thin evidence";

  return (
    <div className="flex items-center gap-2">
      <div
        className="h-1.5 w-20 overflow-hidden rounded-full bg-muted"
        role="meter"
        aria-valuenow={pct}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label={`Model confidence: ${pct} percent, ${label}`}
      >
        <div className={cn("h-full rounded-full transition-[width]", tone)} style={{ width: `${pct}%` }} />
      </div>
      <span className="text-xs text-muted-foreground">
        {pct}% confidence · {label}
      </span>
    </div>
  );
}

export function DriverList({ drivers }: { drivers: PredictionDriver[] }) {
  if (drivers.length === 0) {
    return (
      <p className="text-xs text-muted-foreground">
        No distinguishing factors — the model found nothing notable either way.
      </p>
    );
  }

  return (
    <ul className="space-y-1.5">
      {drivers.slice(0, 4).map((driver) => {
        const up = driver.direction === "INCREASE";
        const Icon = up ? ArrowUp : ArrowDown;
        return (
          <li key={driver.feature} className="flex items-start gap-2 text-xs">
            <Icon
              className={cn("mt-0.5 size-3 shrink-0", up ? "text-amber-600" : "text-emerald-600")}
              aria-hidden
            />
            <span className="min-w-0">
              <span className="font-medium">{driver.label ?? driver.feature}</span>
              {driver.detail ? (
                <span className="text-muted-foreground"> — {driver.detail}</span>
              ) : null}
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
        <span className="cursor-default text-[11px] text-muted-foreground">
          {prediction.modelCode} v{prediction.modelVersion}
          {prediction.cacheHit ? " · cached" : null}
        </span>
      </TooltipTrigger>
      <TooltipContent side="bottom" className="max-w-xs">
        {prediction.cacheHit
          ? "Served from a previous run because nothing about this item has changed since."
          : `Computed in ${prediction.latencyMs ?? 0}ms.`}
      </TooltipContent>
    </Tooltip>
  );
}
