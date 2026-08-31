"use client";

import { useState } from "react";
import { Check, Loader2, Plus, Tag } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { useCreateLabel, useProjectLabels } from "@/features/projects/hooks";
import { cn } from "@/lib/utils";

/**
 * Picks the labels a new backlog item is created with, and invents one if it needs to.
 *
 * <p>Labels have been in the schema, the API and the story payload since the backlog
 * module landed, and there has never been a screen that sets one. The detail panel
 * renders them; nothing produced them, so that block was dead code on every story in
 * the product.</p>
 *
 * <p>They are not decoration here. The estimator's {@code num_components} is the sum of
 * a story's acceptance criteria, its child tasks and its labels, and it is the strongest
 * structural driver the story-point model has — the "components touched" that leads
 * almost every explanation. A backlog where nothing is labelled gives every story the
 * same value for the one input that is supposed to tell them apart.</p>
 *
 * <p>Toggleable pills rather than a popover combobox. The set is small — a project's own
 * labels plus the organization's — and this is already inside a dialog, where a nested
 * popover costs a second layer of focus trapping to solve a problem a list of six chips
 * does not have.</p>
 */

/**
 * Colours for labels created here, so a new one is not born grey.
 *
 * <p>Hex rather than the palette's CSS variables: the backend validates the column
 * against {@code ^#[0-9a-fA-F]{6}$}, and it has to mean the same thing when the label
 * is read back by something that is not this stylesheet. These are the light-theme
 * values of the accent hues, which stay legible against the pill's own 14% tint in
 * both themes.</p>
 */
const LABEL_COLOURS = [
  "#5b5cf0",
  "#0d9488",
  "#7c3aed",
  "#d97706",
  "#2563eb",
  "#16a34a",
] as const;

/** Same name, same colour, without a round trip or a stored counter. */
function colourFor(name: string): string {
  let hash = 0;
  for (let index = 0; index < name.length; index += 1) {
    hash = (hash * 31 + name.charCodeAt(index)) | 0;
  }
  return LABEL_COLOURS[Math.abs(hash) % LABEL_COLOURS.length];
}

export function LabelPicker({
  projectId,
  selected,
  onChange,
  disabled,
}: {
  projectId: string;
  selected: string[];
  onChange: (labelIds: string[]) => void;
  disabled?: boolean;
}) {
  const labels = useProjectLabels(projectId);
  const createLabel = useCreateLabel(projectId);
  const [draft, setDraft] = useState("");

  const available = labels.data ?? [];
  const chosen = new Set(selected);

  // Trailing whitespace would create "payments " as a distinct label from "payments",
  // and the two are indistinguishable in every list that shows them.
  const name = draft.trim();
  const duplicate = available.some(
    (existing) => existing.name.toLowerCase() === name.toLowerCase(),
  );

  function toggle(labelId: string) {
    onChange(
      chosen.has(labelId) ? selected.filter((id) => id !== labelId) : [...selected, labelId],
    );
  }

  function addDraft() {
    if (name === "" || createLabel.isPending) return;

    // Typing a name that already exists selects it rather than failing. Somebody
    // reaching for the box has not necessarily scanned the pills above it, and
    // "already exists" is a worse answer than doing the obvious thing.
    const existing = available.find(
      (label) => label.name.toLowerCase() === name.toLowerCase(),
    );
    if (existing) {
      if (!chosen.has(existing.id)) onChange([...selected, existing.id]);
      setDraft("");
      return;
    }

    createLabel.mutate(
      { name, color: colourFor(name) },
      {
        onSuccess: (label) => {
          onChange([...selected, label.id]);
          setDraft("");
        },
      },
    );
  }

  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between gap-2">
        <Label className="flex items-center gap-1.5">
          <Tag className="size-3.5 text-muted-foreground" aria-hidden />
          Labels
        </Label>
        {selected.length > 0 ? (
          <span className="text-2xs tabular-nums text-muted-foreground">
            {selected.length} selected
          </span>
        ) : null}
      </div>

      {labels.isPending ? (
        <Skeleton className="h-8 w-full" />
      ) : available.length > 0 ? (
        <div className="flex flex-wrap gap-1.5" role="group" aria-label="Labels">
          {available.map((label) => {
            const active = chosen.has(label.id);
            const colour = label.color ?? "var(--ink-3)";
            return (
              <button
                key={label.id}
                type="button"
                disabled={disabled}
                aria-pressed={active}
                onClick={() => toggle(label.id)}
                className={cn(
                  "inline-flex items-center gap-1 rounded-sm border px-1.5 py-0.5 text-2xs font-semibold",
                  "transition-colors focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none",
                  "disabled:pointer-events-none disabled:opacity-50",
                  active ? "border-transparent" : "border-dashed border-line",
                )}
                // Selected pills carry the label's own colour; unselected ones are
                // outlined in it at a fraction of the strength, so the set still reads
                // as one palette and the chosen ones still stand out at a glance.
                style={
                  active
                    ? {
                        color: colour,
                        background: `color-mix(in srgb, ${colour} 14%, transparent)`,
                      }
                    : { color: "var(--ink-3)" }
                }
              >
                {active ? <Check className="size-3 shrink-0" aria-hidden /> : null}
                {label.name}
              </button>
            );
          })}
        </div>
      ) : (
        <p className="text-2xs text-muted-foreground">
          No labels on this project yet. Add the first one below.
        </p>
      )}

      <div className="flex items-center gap-2">
        <Input
          aria-label="New label name"
          placeholder="New label, e.g. payments"
          value={draft}
          disabled={disabled || createLabel.isPending}
          onChange={(event) => setDraft(event.target.value)}
          // Enter creates the label rather than submitting the story: this input sits
          // inside the create form, and the default would file a half-written item.
          onKeyDown={(event) => {
            if (event.key === "Enter") {
              event.preventDefault();
              addDraft();
            }
          }}
          className="h-8 flex-1"
        />
        <Button
          type="button"
          size="sm"
          variant="secondary"
          disabled={disabled || name === "" || createLabel.isPending}
          onClick={addDraft}
        >
          {createLabel.isPending ? (
            <Loader2 className="size-3.5 animate-spin" aria-hidden />
          ) : (
            <Plus className="size-3.5" aria-hidden />
          )}
          {duplicate ? "Select" : "Add"}
        </Button>
      </div>

      {createLabel.isError ? (
        <p className="text-2xs text-danger">Could not create that label. Try another name.</p>
      ) : (
        <p className="text-2xs leading-relaxed text-muted-foreground">
          The estimator counts labels alongside acceptance criteria and child tasks as
          the story&rsquo;s component signal, so labelling what a story touches sharpens
          its size.
        </p>
      )}
    </div>
  );
}
