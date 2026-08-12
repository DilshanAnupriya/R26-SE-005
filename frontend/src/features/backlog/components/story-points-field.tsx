"use client";

import { useState } from "react";
import { Check, Loader2, Pencil, X } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import type { Story } from "@/lib/api/delivery-schemas";
import { cn } from "@/lib/utils";
import { useEstimateStory } from "../hooks";

/** The scale planning sessions actually use, matching the model's own snapping. */
const FIBONACCI = [1, 2, 3, 5, 8, 13, 21];

/** How the number on the story got there — worth saying, because it changes its weight. */
const SOURCE_LABEL: Record<Story["estimateSource"], string> = {
  UNESTIMATED: "Not sized yet",
  MANUAL: "Set by your team",
  AI_ACCEPTED: "AI suggestion, accepted",
  AI_OVERRIDDEN: "AI suggestion, overridden",
};

/**
 * The story's points, set by a person.
 *
 * <p>Deliberately independent of the AI panel below it. A team must be able to size a
 * story without consulting a model at all — and the one place that could previously set
 * points, "Override", only appeared once a suggestion existed, which made the model a
 * gatekeeper for a number the team owns.</p>
 *
 * <p>The quick picks are the Fibonacci scale rather than a free number first, because
 * the argument in a planning session is "is this a 5 or an 8", not "is this a 6.5". The
 * free input is still there underneath for the teams that use a different scale.</p>
 */
export function StoryPointsField({ story, projectId }: { story: Story; projectId: string }) {
  const estimate = useEstimateStory(projectId);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState("");

  const points = story.storyPoints;

  function save(value: number) {
    if (!Number.isFinite(value) || value < 0) {
      toast.error("Points have to be a number, and not a negative one.");
      return;
    }
    estimate.mutate(
      { storyId: story.id, storyPoints: value },
      {
        onSuccess: () => {
          setEditing(false);
          toast.success(`${story.itemKey} is now ${value} points.`);
        },
        onError: (error) => {
          // Without this the row silently keeps its old number and the click looks
          // like it did nothing at all.
          toast.error(error instanceof Error ? error.message : "Could not save the estimate.");
        },
      },
    );
  }

  return (
    <div className="rounded-lg border p-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-sm font-medium">Story points</p>
          <p className="text-xs text-muted-foreground">{SOURCE_LABEL[story.estimateSource]}</p>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-2xl font-semibold tabular-nums">{points ?? "—"}</span>
          {!editing ? (
            <Button
              size="sm"
              variant="ghost"
              onClick={() => {
                setDraft(points != null ? String(points) : "");
                setEditing(true);
              }}
            >
              <Pencil className="size-3.5" aria-hidden />
              {points != null ? "Change" : "Set"}
            </Button>
          ) : (
            <Button size="sm" variant="ghost" onClick={() => setEditing(false)}>
              <X className="size-3.5" aria-hidden />
              Cancel
            </Button>
          )}
        </div>
      </div>

      {editing ? (
        <div className="mt-3 space-y-2">
          <div
            className="flex flex-wrap gap-1.5"
            role="group"
            aria-label={`Story points for ${story.itemKey}`}
          >
            {FIBONACCI.map((value) => (
              <Button
                key={value}
                size="sm"
                variant={points === value ? "default" : "outline"}
                disabled={estimate.isPending}
                aria-pressed={points === value}
                className={cn("min-w-9 tabular-nums")}
                onClick={() => save(value)}
              >
                {value}
              </Button>
            ))}
          </div>

          <form
            className="flex items-end gap-2"
            onSubmit={(event) => {
              event.preventDefault();
              save(Number(draft));
            }}
          >
            <div className="flex-1">
              <Label htmlFor={`points-${story.id}`} className="text-xs">
                Or another number
              </Label>
              <Input
                id={`points-${story.id}`}
                type="number"
                min={0}
                step="0.5"
                inputMode="decimal"
                value={draft}
                onChange={(event) => setDraft(event.target.value)}
                className="mt-1 h-8"
              />
            </div>
            <Button type="submit" size="sm" disabled={estimate.isPending || draft.trim() === ""}>
              {estimate.isPending ? (
                <Loader2 className="size-3.5 animate-spin" aria-hidden />
              ) : (
                <Check className="size-3.5" aria-hidden />
              )}
              Save
            </Button>
          </form>
        </div>
      ) : null}
    </div>
  );
}
