"use client";

import { useState } from "react";
import { ArrowRight, Check, Pencil, RefreshCw, Sparkles, X } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";
import type { Story } from "@/lib/api/delivery-schemas";
import type { StoryPointEstimate } from "@/lib/api/ai-schemas";
import { useEstimateStory, useSubmitFeedback } from "../hooks";
import {
  BoundsLabel,
  SnapLabel,
  ConfidenceBar,
  DegradedNotice,
  DriverList,
  PredictionMeta,
} from "./prediction-explanation";
import { SizingContextLine } from "./sizing-context";

type FeedbackVerdict = "ACCEPTED" | "OVERRIDDEN" | "DISMISSED";

/** What this session did with the suggestion, so the panel can say it happened. */
type Verdict = { action: FeedbackVerdict; points: number | null };

/**
 * Model 1's story-point panel: Accept, Override, Compare.
 *
 * <p>The suggestion never becomes the estimate on its own. Until someone presses
 * Accept, the number lives beside the story's own points rather than in them — a
 * planning session where numbers change by themselves is one nobody trusts twice.</p>
 *
 * <p>Compare is the third option because the interesting case is disagreement. Showing
 * the team's number and the model's side by side, with the reasons underneath, turns
 * "the AI says 8" into a conversation about which of them is missing something.</p>
 */
export function EstimatePanel({
  story,
  onEstimateApplied,
}: {
  story: Story;
  onEstimateApplied?: (points: number) => void;
}) {
  const estimate = useEstimateStory();
  const feedback = useSubmitFeedback();
  const [overriding, setOverriding] = useState(false);
  const [overrideValue, setOverrideValue] = useState("");
  const [verdict, setVerdict] = useState<Verdict | null>(null);

  // A mutation result outlives the story it was produced for, so it has to be checked
  // against the story on screen. Without this the panel showed the previously sized
  // story's suggestion under this story's title, and Accept then wrote those points
  // back to the *other* story — the request succeeded, and nothing here changed.
  const fresh = estimate.data?.storyId === story.id ? estimate.data : null;

  // The freshest suggestion wins: the mutation result if one has just run for this
  // story, otherwise whatever was stored against the story last time.
  const suggestion: StoryPointEstimate | null =
    fresh ??
    (story.aiEstimate?.points != null
      ? {
          storyId: story.id,
          itemKey: story.itemKey,
          title: story.title,
          currentPoints: story.storyPoints ?? null,
          suggestedPoints: story.aiEstimate.points,
          prediction: null,
          // Only a live run carries it. A stored suggestion records the number, not the
          // project as it stood when the number was made, and the project's fields may
          // have been filled in since — so reading them now would caption an old
          // estimate with context it was never scored against.
          context: null,
        }
      : null);

  const predictionId = fresh?.prediction?.predictionId ?? story.aiEstimate?.predictionId;
  const busy = estimate.isPending || feedback.isPending;

  /**
   * Runs the model again on the story as it stands now.
   *
   * <p>The reason to want this is that the estimate is a function of the story text
   * and its structure, both of which change while a story is being refined. A
   * suggestion recorded before the acceptance criteria were written is stale, and
   * there was previously no way to ask for a fresh one short of reloading.</p>
   */
  function reEstimate() {
    // The old verdict described the old number, so it stops being true here.
    setVerdict(null);
    setOverriding(false);
    estimate.mutate(story.id);
  }

  async function respond(action: FeedbackVerdict, value?: number) {
    if (!predictionId) {
      // Nothing to record a verdict against. Saying so beats a button that swallows
      // the click, which is indistinguishable from a broken one.
      toast.error("This suggestion is no longer on file. Run it again to accept it.");
      return;
    }

    try {
      await feedback.mutateAsync({
        predictionId,
        action,
        finalValue: value ?? null,
        storyId: story.id,
      });
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Could not record that.");
      return;
    }

    setOverriding(false);
    if (action === "ACCEPTED" && suggestion?.suggestedPoints != null) {
      setVerdict({ action, points: suggestion.suggestedPoints });
      onEstimateApplied?.(suggestion.suggestedPoints);
      toast.success(`${story.itemKey} is now ${suggestion.suggestedPoints} points.`);
    }
    if (action === "OVERRIDDEN" && value != null) {
      setVerdict({ action, points: value });
      onEstimateApplied?.(value);
      toast.success(`${story.itemKey} is now ${value} points.`);
    }
    if (action === "DISMISSED") {
      setVerdict({ action, points: null });
      toast.info("Suggestion dismissed. It will not be applied.");
    }
  }

  if (!suggestion) {
    return (
      <div className="rounded-lg border border-dashed p-3">
        <div className="flex items-center justify-between gap-3">
          <div className="min-w-0">
            <p className="text-sm font-medium">Estimate with AI</p>
            <p className="text-xs text-muted-foreground">
              Sized against this project&rsquo;s own scale, with the reasons shown.
            </p>
          </div>
          <Button
            size="sm"
            variant="secondary"
            disabled={busy}
            onClick={() => estimate.mutate(story.id)}
          >
            <Sparkles className="size-3.5" aria-hidden />
            {estimate.isPending ? "Estimating…" : "Suggest"}
          </Button>
        </div>
        {estimate.isPending ? <Skeleton className="mt-3 h-16 w-full" /> : null}
      </div>
    );
  }

  const prediction = fresh?.prediction;
  const human = story.storyPoints;
  const suggested = suggestion.suggestedPoints;
  const disagrees = human != null && suggested != null && human !== suggested;

  return (
    <section
      className="rounded-lg border border-line bg-surface"
      aria-labelledby={`ai-estimate-${story.id}`}
    >
      {/* Header band on surface-2 so the estimate below reads as the panel's content
          rather than one more row in a stack of equal-weight lines. */}
      <div className="flex items-center justify-between gap-3 rounded-t-lg border-b border-line bg-surface-2 px-3 py-2">
        <h3
          id={`ai-estimate-${story.id}`}
          className="flex shrink-0 items-center gap-1.5 whitespace-nowrap text-xs font-medium text-ink-2"
        >
          <Sparkles className="size-3.5 shrink-0 text-accent" aria-hidden />
          Suggested estimate
          {estimate.isPending ? (
            <span className="font-normal text-ink-3">· re-estimating…</span>
          ) : null}
        </h3>
      </div>

      <div className="p-3">
        <div
          // Keyed on the value so a re-estimate that lands somewhere new replays the
          // entrance. Without it React reuses the node, the animation never re-runs,
          // and the number changes with nothing to mark that it did.
          key={`estimate-${suggested ?? "none"}-${prediction?.predictionId ?? "stored"}`}
          className="flex items-start justify-between gap-3 animate-fade-up"
        >
          <div className="min-w-0">
            {/* The number leads. Everything under it is qualification, and it was all
                previously set at the same weight as the answer itself. */}
            <div className="flex items-baseline gap-2">
              <span className="text-5xl font-semibold leading-none tabular-nums tracking-tight text-ink">
                {suggested ?? "—"}
              </span>
              <span className="text-xs text-ink-3">points</span>
              {prediction ? (
                <BoundsLabel lower={prediction.lowerBound} upper={prediction.upperBound} />
              ) : story.aiEstimate?.lowerBound != null ? (
                <BoundsLabel
                  lower={story.aiEstimate.lowerBound}
                  upper={story.aiEstimate.upperBound}
                />
              ) : null}
            </div>

            {/* Only appears when the rounding actually hid something. Most estimates say
                nothing here, which is what makes the ones that do say something worth
                reading. */}
            <div className="mt-1.5">
              {/* Falls back to the stored value, so the line does not vanish on reload
                  — the same story showing "a low 8" and then nothing was the reason
                  this looked inconsistent between one story and the next. */}
              <SnapLabel
                raw={prediction?.rawValue ?? story.aiEstimate?.rawPoints}
                snapped={suggested}
              />
            </div>

            <div className="mt-2">
              <ConfidenceBar
                confidence={prediction?.confidence ?? story.aiEstimate?.confidence}
              />
            </div>
          </div>
        </div>

      {/* The team's own number, always — not only when it differs.
          Showing this block solely on disagreement made agreement invisible: the card
          said "8" and never said what you had, so a reader could not tell whether the
          model had confirmed their estimate or never seen one. Agreement is a result,
          and a result the model reached independently is worth stating out loud. */}
      <div className="mt-3 flex items-center gap-2 rounded-md bg-surface-2 px-2.5 py-2 text-xs">
        {human == null ? (
          <span className="text-ink-2">
            Your team has not sized this story yet.
          </span>
        ) : disagrees ? (
          <>
            <span className="tabular-nums font-semibold text-ink">{human}</span>
            <span className="text-ink-2">yours</span>
            <ArrowRight className="size-3 shrink-0 text-ink-3" aria-hidden />
            <span className="tabular-nums font-semibold text-ink">{suggested}</span>
            <span className="text-ink-2">the model&rsquo;s</span>
            <span className="ml-auto shrink-0 rounded bg-amber-soft px-1.5 py-0.5 text-2xs font-medium text-amber">
              {Math.abs(human - (suggested ?? 0))} apart
            </span>
          </>
        ) : (
          <>
            <Check className="size-3.5 shrink-0 text-green" aria-hidden />
            <span className="text-ink-2">
              Matches your estimate of{" "}
              <span className="tabular-nums font-semibold text-ink">{human}</span>
            </span>
          </>
        )}
      </div>

      {prediction?.explanation ? (
        <p className="mt-3 text-xs leading-relaxed text-ink-2">{prediction.explanation}</p>
      ) : null}

      {/* Before the drivers rather than after them, because this is the frame they sit
          in. Drivers are a per-story breakdown, so the project-level inputs — team
          size above all — can never appear there however much they moved the number;
          a reader scanning that list for their team and not finding it concludes the
          model never saw it. Answering first is what stops the search. */}
      {fresh?.context ? (
        <div className="mt-3">
          <SizingContextLine context={fresh.context} />
        </div>
      ) : null}

      {prediction ? (
        <>
          <div className="mt-3">
            <DriverList drivers={prediction.drivers} />
          </div>
          <div className="mt-2">
            <DegradedNotice prediction={prediction} />
          </div>
          {/* Provenance sits at the foot rather than the header: it is what you check
              after reading the estimate, not before, and down here it has the width to
              say so without truncating. */}
          <div className="mt-2 border-t border-line pt-2">
            <PredictionMeta prediction={prediction} />
          </div>
        </>
      ) : null}

      {/* What just happened, in the panel rather than only in a toast that has already
          faded. Accepting a suggestion that matches the story's current points changes
          nothing else on screen, so without this the button looks broken. */}
      {verdict ? (
        <div className="mt-3 flex flex-wrap items-center gap-2 rounded-md border border-dashed p-2">
          <Check className="size-3.5 text-primary" aria-hidden />
          <p className="flex-1 text-xs" role="status">
            {verdict.action === "DISMISSED"
              ? "Dismissed. The story keeps its own estimate."
              : `Applied — ${story.itemKey} is ${verdict.points} points.`}
          </p>
          <Button
            size="sm"
            variant="ghost"
            disabled={busy}
            onClick={() => {
              setVerdict(null);
              setOverrideValue(String(verdict.points ?? suggested ?? ""));
              setOverriding(true);
            }}
          >
            <Pencil className="size-3.5" aria-hidden />
            Change
          </Button>
          <Button size="sm" variant="ghost" disabled={busy} onClick={reEstimate}>
            <RefreshCw className={cn("size-3.5", estimate.isPending && "animate-spin")} aria-hidden />
            Re-estimate
          </Button>
        </div>
      ) : null}

      {predictionId ? (
        overriding ? (
          <form
            className="mt-3 flex items-end gap-2"
            onSubmit={(event) => {
              event.preventDefault();
              const parsed = Number(overrideValue);
              if (!Number.isFinite(parsed) || parsed < 0) {
                toast.error("Enter a number of points.");
                return;
              }
              void respond("OVERRIDDEN", parsed);
            }}
          >
            <div className="flex-1">
              <Label htmlFor={`override-${story.id}`} className="text-xs">
                Your estimate
              </Label>
              <Input
                id={`override-${story.id}`}
                type="number"
                min={0}
                step="0.5"
                inputMode="decimal"
                autoFocus
                value={overrideValue}
                onChange={(event) => setOverrideValue(event.target.value)}
                className="mt-1 h-8"
              />
            </div>
            <Button type="submit" size="sm" disabled={busy}>
              Save
            </Button>
            <Button type="button" size="sm" variant="ghost" onClick={() => setOverriding(false)}>
              Cancel
            </Button>
          </form>
        ) : verdict ? null : (
          <div className="mt-3 flex flex-wrap gap-2">
            <Button size="sm" disabled={busy} onClick={() => void respond("ACCEPTED")}>
              <Check className="size-3.5" aria-hidden />
              Accept {suggested}
            </Button>
            <Button
              size="sm"
              variant="secondary"
              disabled={busy}
              onClick={() => {
                setOverrideValue(String(human ?? suggested ?? ""));
                setOverriding(true);
              }}
            >
              <Pencil className="size-3.5" aria-hidden />
              Override
            </Button>
            <Button
              size="sm"
              variant="outline"
              disabled={busy}
              onClick={reEstimate}
            >
              <RefreshCw className={cn("size-3.5", estimate.isPending && "animate-spin")} aria-hidden />
              Re-estimate
            </Button>
            <Button
              size="sm"
              variant="ghost"
              disabled={busy}
              onClick={() => void respond("DISMISSED")}
            >
              <X className="size-3.5" aria-hidden />
              Dismiss
            </Button>
          </div>
        )
      ) : (
        <p className="mt-3 text-xs text-muted-foreground">
          This suggestion was recorded earlier. Re-run it to accept or override.
        </p>
      )}
      </div>
    </section>
  );
}
