"use client";

import { useState } from "react";
import { Check, Pencil, RefreshCw, Sparkles, X } from "lucide-react";
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
  ConfidenceBar,
  DegradedNotice,
  DriverList,
  PredictionMeta,
} from "./prediction-explanation";

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
    <section className="rounded-lg border p-3" aria-labelledby={`ai-estimate-${story.id}`}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3
            id={`ai-estimate-${story.id}`}
            className="flex items-center gap-1.5 text-sm font-medium"
          >
            <Sparkles className="size-3.5 text-primary" aria-hidden />
            Suggested estimate
            {estimate.isPending ? (
              <span className="text-xs font-normal text-muted-foreground">re-estimating…</span>
            ) : null}
          </h3>

          <div className="mt-1.5 flex items-baseline gap-2">
            <span className="text-2xl font-semibold tabular-nums">{suggested ?? "—"}</span>
            <span className="text-xs text-muted-foreground">points</span>
            {prediction ? (
              <BoundsLabel lower={prediction.lowerBound} upper={prediction.upperBound} />
            ) : story.aiEstimate?.lowerBound != null ? (
              <BoundsLabel
                lower={story.aiEstimate.lowerBound}
                upper={story.aiEstimate.upperBound}
              />
            ) : null}
          </div>

          <div className="mt-1.5">
            <ConfidenceBar
              confidence={prediction?.confidence ?? story.aiEstimate?.confidence}
            />
          </div>
        </div>

        {prediction ? <PredictionMeta prediction={prediction} /> : null}
      </div>

      {/* Compare: the team's number beside the model's, whenever they differ. */}
      {disagrees ? (
        <div className="mt-3 grid grid-cols-2 gap-2 rounded-md bg-muted/50 p-2 text-center">
          <div>
            <p className="text-[11px] uppercase tracking-wide text-muted-foreground">Your team</p>
            <p className="text-lg font-semibold tabular-nums">{human}</p>
          </div>
          <div>
            <p className="text-[11px] uppercase tracking-wide text-muted-foreground">Model</p>
            <p className="text-lg font-semibold tabular-nums">{suggested}</p>
          </div>
        </div>
      ) : null}

      {prediction?.explanation ? (
        <p className="mt-3 text-xs leading-relaxed text-muted-foreground">
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
    </section>
  );
}
