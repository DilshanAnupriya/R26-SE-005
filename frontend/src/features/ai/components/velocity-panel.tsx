"use client";

import { Gauge } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { CommitmentVerdict, VelocityPrediction } from "@/lib/api/ai-schemas";
import {
  BoundsLabel,
  ConfidenceBar,
  DegradedNotice,
  DriverList,
  PredictionMeta,
} from "./prediction-explanation";

/**
 * What each verdict means, in the words a planning session would use.
 *
 * <p>Lives here rather than beside one screen because the verdict is the point of the
 * whole prediction and two screens already needed it. A velocity number on its own
 * invites a team to congratulate itself; "34 committed against a predicted 22" is the
 * sentence that changes what happens next.</p>
 */
export const VERDICT_COPY: Record<CommitmentVerdict, { label: string; tone: string; hint: string }> = {
  OVER_COMMITTED: {
    label: "Over-committed",
    tone: "bg-destructive/15 text-destructive",
    hint: "The commitment is well above what this team usually delivers.",
  },
  STRETCHED: {
    label: "Stretched",
    tone: "bg-amber-500/10 text-amber-700 dark:text-amber-400",
    hint: "Achievable, but there is no room for the discovery that always happens.",
  },
  BALANCED: {
    label: "Balanced",
    tone: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
    hint: "Committed a little under the prediction, which is the healthy place to be.",
  },
  UNDER_COMMITTED: {
    label: "Under-committed",
    tone: "bg-sky-500/10 text-sky-700 dark:text-sky-400",
    hint: "There is room for more, if the backlog has anything ready.",
  },
  UNKNOWN: {
    label: "Not enough history",
    tone: "bg-muted text-muted-foreground",
    hint: "No verdict until this team has closed a sprint or two.",
  },
};

/**
 * The next-sprint velocity prediction, with what drove it.
 *
 * <p>The commitment sits beside the prediction rather than under it, because the
 * comparison is the decision: a team reads "26 predicted" and moves on, but "you have
 * committed 34 against a predicted 26" is an argument worth having before the sprint
 * starts rather than at the review.</p>
 *
 * <p>Bounds and confidence are shown for the same reason they are on the story-point
 * panel. This model is a point regressor banded by its own held-out error, so a range
 * is the honest presentation — and a team that has never closed a sprint is being
 * predicted almost entirely from the training set, which the explanation says outright.</p>
 */
export function VelocityPanel({
  velocity,
  className,
}: {
  velocity: VelocityPrediction;
  className?: string;
}) {
  const verdict = VERDICT_COPY[velocity.commitmentVerdict];
  const prediction = velocity.prediction;
  const predicted = velocity.predictedVelocity;
  const committed = velocity.committedPoints;

  return (
    <section
      className={cn("rounded-lg border p-3", className)}
      aria-labelledby="velocity-heading"
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 id="velocity-heading" className="flex items-center gap-1.5 text-sm font-medium">
            <Gauge className="size-4 text-primary" aria-hidden />
            Predicted velocity
          </h3>

          <div className="mt-1.5 flex items-baseline gap-2">
            <span className="text-2xl font-semibold tabular-nums">{predicted ?? "—"}</span>
            <span className="text-xs text-muted-foreground">points next sprint</span>
            {prediction ? (
              <BoundsLabel lower={prediction.lowerBound} upper={prediction.upperBound} />
            ) : null}
          </div>

          <div className="mt-1.5">
            <ConfidenceBar confidence={prediction?.confidence} />
          </div>
        </div>

        {prediction ? <PredictionMeta prediction={prediction} /> : null}
      </div>

      {/* The comparison, whenever there is a commitment to compare against. */}
      {committed != null && predicted != null ? (
        <div className="mt-3 grid grid-cols-2 gap-2 rounded-md bg-muted/50 p-2 text-center">
          <div>
            <p className="text-[11px] uppercase tracking-wide text-muted-foreground">Committed</p>
            <p className="text-lg font-semibold tabular-nums">{committed}</p>
          </div>
          <div>
            <p className="text-[11px] uppercase tracking-wide text-muted-foreground">Predicted</p>
            <p className="text-lg font-semibold tabular-nums">{predicted}</p>
          </div>
        </div>
      ) : null}

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <Badge className={cn("border-0", verdict.tone)}>{verdict.label}</Badge>
        {velocity.rollingAverage != null && velocity.rollingAverage > 0 ? (
          <span className="text-xs text-muted-foreground tabular-nums">
            rolling average {velocity.rollingAverage}
          </span>
        ) : null}
      </div>

      <p className="mt-2 text-xs leading-relaxed text-muted-foreground">{verdict.hint}</p>

      {prediction?.explanation ? (
        <p className="mt-2 text-xs leading-relaxed text-muted-foreground">
          {prediction.explanation}
        </p>
      ) : null}

      {prediction ? (
        <>
          <div className="mt-3">
            <DriverList drivers={prediction.drivers} />
          </div>
          <div className="mt-2">
            <DegradedNotice prediction={prediction} />
          </div>
        </>
      ) : null}
    </section>
  );
}
