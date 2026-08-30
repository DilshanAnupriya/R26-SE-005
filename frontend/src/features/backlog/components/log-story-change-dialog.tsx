"use client";

import { useState } from "react";
import { ArrowDown, ArrowUp, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";
import { REQUIREMENT_CHANGE_TYPES, type RequirementChangeType } from "@/features/risk-analysis/api";
import type { Priority, Story } from "@/lib/api/delivery-schemas";
import { cn } from "@/lib/utils";
import { useApplyRequirementChange, useStory } from "../hooks";
import {
  AcceptanceCriteriaList,
  joinCriteria,
  parseCriteria,
} from "./acceptance-criteria-list";

/**
 * Change a requirement on one story, and put it on the record in the same action.
 *
 * <p>The two used to be separate — edit the story on one screen, remember to log the
 * change on another — and the register drifted from the backlog it was supposed to
 * describe. Here the edit <em>is</em> the log entry, so a requirement cannot move without
 * the sprint's risk hearing about it.</p>
 *
 * <p>Nothing asks which way the change pushed. Raising a priority, adding a criterion or
 * writing a longer description is more to deliver; the reverse is less. The server reads
 * that out of the two versions, which is what stops the same edit costing +5 or −3
 * depending on which option somebody picked from a dropdown.</p>
 */

const TYPE_LABEL: Record<RequirementChangeType, string> = {
  SCOPE_ADDED: "Scope added",
  SCOPE_REMOVED: "Scope removed",
  ACCEPTANCE_CRITERIA_CHANGED: "Acceptance criteria changed",
  PRIORITY_CHANGED: "Priority changed",
  DESCRIPTION_UPDATED: "Description updated",
  OTHER: "Other",
};

/** What the form edits for each type, and therefore what it reads the direction from. */
const TYPE_FIELD: Record<RequirementChangeType, string> = {
  SCOPE_ADDED: "storyPoints",
  SCOPE_REMOVED: "storyPoints",
  ACCEPTANCE_CRITERIA_CHANGED: "acceptanceCriteria",
  PRIORITY_CHANGED: "priority",
  DESCRIPTION_UPDATED: "description",
  OTHER: "none",
};

/** The two fields the form shows whatever the change type says, and can retarget it. */
type PermanentField = "priority" | "acceptanceCriteria";

/** High to low, so the list reads the way people rank things. */
const PRIORITIES: Priority[] = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "LOWEST"];
const PRIORITY_WEIGHT: Record<Priority, number> = {
  LOWEST: 1,
  LOW: 2,
  MEDIUM: 3,
  HIGH: 4,
  CRITICAL: 5,
};

/** A priority as somebody would say it: CRITICAL reads as "Critical". */
function priorityLabel(priority: Priority): string {
  return priority.charAt(0) + priority.slice(1).toLowerCase();
}

export function LogStoryChangeDialog({
  storyId,
  open,
  onOpenChange,
}: {
  storyId: string | undefined;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const story = useStory(open ? storyId : undefined);
  const loaded = story.data;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {/*
        Wider than the default, because this form is now two columns of paired controls
        rather than one tall stack — and the criteria list needs room for a sentence per
        row without wrapping every one of them.
      */}
      <DialogContent className="sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>Log a change</DialogTitle>
          <DialogDescription>
            Edits the story and records the change against the sprint&rsquo;s
            requirement-change risk. Which way the risk moves is read from the edit itself.
          </DialogDescription>
        </DialogHeader>

        {story.isLoading || !loaded ? (
          <div className="space-y-3 py-2">
            <Skeleton className="h-9 w-full" />
            <Skeleton className="h-9 w-full" />
            <Skeleton className="h-24 w-full" />
          </div>
        ) : (
          /*
            Keyed on the story, so opening the dialog on a different row mounts a fresh
            form rather than carrying the last one's half-typed edit across. The key is
            also what lets the fields start at the story's current values without an
            effect copying them in afterwards — the state initialises from the story this
            instance was built for.
          */
          <ChangeForm key={loaded.id} story={loaded} onDone={() => onOpenChange(false)} />
        )}
      </DialogContent>
    </Dialog>
  );
}

function ChangeForm({ story, onDone }: { story: Story; onDone: () => void }) {
  const apply = useApplyRequirementChange(story.id);

  const [changeType, setChangeType] = useState<RequirementChangeType>("SCOPE_ADDED");
  /**
   * The type the author last chose, and which field's edit pushed it aside.
   *
   * <p>Editing one of the permanent fields switches the type to the one that field owns,
   * because that is what the edit has become. Setting the field back has to undo that
   * too, or the form is stranded on a change type nobody picked, describing a field that
   * no longer moved — which the server then refuses as a no-op.</p>
   */
  const [displaced, setDisplaced] =
    useState<{ type: RequirementChangeType; by: PermanentField } | null>(null);
  // Starting where the story already is, so what gets submitted is a diff the user made
  // rather than a blank they retyped.
  const [priority, setPriority] = useState<Priority>(story.priority);
  const [description, setDescription] = useState(story.description ?? "");
  // Normalised on the way in, so "moved" means the criteria actually differ rather than
  // that a stray bullet or blank line was tidied up on first edit.
  const originalCriteria = joinCriteria(parseCriteria(story.acceptanceCriteria));
  const [acceptanceCriteria, setAcceptanceCriteria] = useState(originalCriteria);
  const [storyPoints, setStoryPoints] = useState(
    story.storyPoints == null ? "" : String(story.storyPoints),
  );
  const [note, setNote] = useState("");
  const [requestedByName, setRequestedByName] = useState("");
  const [attachToSprint, setAttachToSprint] = useState(true);

  const loaded = story;

  /** The dropdown's own choice, which supersedes anything a field switched to. */
  function chooseType(next: RequirementChangeType) {
    setDisplaced(null);
    setChangeType(next);
  }

  /**
   * Points the change type at the field somebody just edited, and back again when they
   * undo it.
   *
   * <p>The server writes only the field the chosen type owns, so criteria edited while
   * the type still reads "Scope added" would be dropped on the floor and the sprint
   * charged for something else entirely. Switching the type is what makes these controls
   * mean what they look like they mean.</p>
   */
  function retarget(by: PermanentField, moved: boolean, target: RequirementChangeType) {
    if (moved) {
      if (changeType !== target) {
        setDisplaced({ type: changeType, by });
        setChangeType(target);
      }
      return;
    }
    // Put back only what this field displaced. Undoing the criteria must not steal the
    // type back from a priority edit that is still standing.
    if (displaced?.by === by && changeType === target) {
      setChangeType(displaced.type);
      setDisplaced(null);
    }
  }

  function choosePriority(next: Priority) {
    setPriority(next);
    retarget("priority", next !== story.priority, "PRIORITY_CHANGED");
  }

  function chooseCriteria(next: string) {
    setAcceptanceCriteria(next);
    retarget("acceptanceCriteria", next !== originalCriteria, "ACCEPTANCE_CRITERIA_CHANGED");
  }

  function submit(event: React.FormEvent) {
    event.preventDefault();
    const field = TYPE_FIELD[changeType];
    apply.mutate(
      {
        changeType,
        newPriority: field === "priority" ? priority : null,
        newDescription: field === "description" ? description : null,
        newAcceptanceCriteria: field === "acceptanceCriteria" ? acceptanceCriteria : null,
        newStoryPoints:
          field === "storyPoints" && storyPoints.trim() !== "" ? Number(storyPoints) : null,
        description: note.trim() === "" ? null : note,
        requestedByName: requestedByName.trim() === "" ? null : requestedByName,
        attachToSprint: attachToSprint && Boolean(loaded.sprintId),
      },
      { onSuccess: onDone },
    );
  }

  return (
    /*
      Bounded rather than allowed to run off the screen. The dialog is centred on the
      viewport, so an over-tall form clipped at both ends with no way to reach either —
      the submit button included.
    */
    <form onSubmit={submit} className="max-h-[72dvh] space-y-3 overflow-y-auto pr-1">
      {/*
        The story first, and stated rather than chosen. The dialog is opened from a
        row, so a picker here could only ever disagree with the row that opened it.
      */}
      {/*
        One line at this width. The key, the title and the facts that matter read across
        rather than stacking, which is a row of dialog height back.
      */}
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5 rounded-lg border border-line bg-surface-2 px-3 py-2">
        <span className="font-mono text-2xs text-ink-3">{loaded.itemKey}</span>
        <span className="min-w-0 flex-1 truncate text-sm font-medium">{loaded.title}</span>
        <span className="text-xs text-ink-3">
          {priorityLabel(loaded.priority)} priority
          {loaded.storyPoints != null ? ` · ${loaded.storyPoints} points` : " · unestimated"}
          {loaded.sprintId ? "" : " · not in a sprint"}
        </span>
      </div>

      {/*
        Two selects, one row. They belong together at this width: the left one names what
        is being logged, and moving the right one is what sets it.
      */}
      <div className="grid gap-3 sm:grid-cols-2">
      <div className="space-y-1.5">
        <Label htmlFor="story-change-type">What changed?</Label>
        <Select
          value={changeType}
          onValueChange={(value) => chooseType(value as RequirementChangeType)}
        >
          <SelectTrigger id="story-change-type" className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {REQUIREMENT_CHANGE_TYPES.map((type) => (
              <SelectItem key={type} value={type}>
                {TYPE_LABEL[type]}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/*
        Priority is a permanent field rather than one the change-type dropdown reveals.
        It is the property people actually open this dialog to move, and a control you
        have to go looking for is one that gets changed on the board instead — which is
        the drift between the backlog and the register this dialog exists to close.
      */}
      <div
        className={cn(
          "space-y-1.5 rounded-lg border p-3 transition-colors",
          changeType === "PRIORITY_CHANGED"
            ? "border-accent bg-accent-soft/40"
            : "border-line bg-surface-2/50",
        )}
      >
        <div className="flex items-baseline justify-between gap-2">
          <Label htmlFor="story-change-priority">Priority</Label>
          <span className="text-2xs text-ink-3">
            {priority === loaded.priority ? "Unchanged" : `Was ${priorityLabel(loaded.priority)}`}
          </span>
        </div>
        <Select value={priority} onValueChange={(value) => choosePriority(value as Priority)}>
          <SelectTrigger id="story-change-priority" className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {PRIORITIES.map((value) => (
              <SelectItem key={value} value={value}>
                {priorityLabel(value)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        {/*
          Only once it has actually moved. A permanent field carrying a permanent
          "nothing has moved yet" would train people to stop reading the one line here
          that matters when it does.
        */}
        {priority !== loaded.priority ? (
          <DirectionPreview
            field="priority"
            story={loaded}
            priority={priority}
            description={description}
            acceptanceCriteria={acceptanceCriteria}
            storyPoints={storyPoints}
            changeType="PRIORITY_CHANGED"
          />
        ) : null}
      </div>
      </div>

      {/*
        Acceptance criteria, permanent for the same reason priority is: it is one of the
        two things people open this dialog to change, and a control the change-type
        dropdown has to reveal is one that gets missed. The count is what the register
        reads, so adding a row here raises the risk and deleting one lowers it.
      */}
      <div
        className={cn(
          "space-y-1.5 rounded-lg border p-3 transition-colors",
          changeType === "ACCEPTANCE_CRITERIA_CHANGED"
            ? "border-accent bg-accent-soft/40"
            : "border-line bg-surface-2/50",
        )}
      >
        <div className="flex items-baseline justify-between gap-2">
          <Label>Acceptance criteria</Label>
          <span className="text-2xs text-ink-3">
            {acceptanceCriteria === originalCriteria
              ? `${countCriteria(originalCriteria)} unchanged`
              : `Was ${countCriteria(originalCriteria)}, now ${countCriteria(acceptanceCriteria)}`}
          </span>
        </div>
        <AcceptanceCriteriaList
          value={originalCriteria}
          onChange={chooseCriteria}
          idPrefix="story-change-criterion"
        />
        {acceptanceCriteria !== originalCriteria ? (
          <DirectionPreview
            field="acceptanceCriteria"
            story={loaded}
            priority={priority}
            description={description}
            acceptanceCriteria={acceptanceCriteria}
            storyPoints={storyPoints}
            changeType="ACCEPTANCE_CRITERIA_CHANGED"
          />
        ) : null}
      </div>

      {/*
        Both permanent fields moved, and the server writes only one. Said here rather
        than discovered afterwards, when the other edit has silently not happened.
      */}
      {priority !== loaded.priority && acceptanceCriteria !== originalCriteria ? (
        <p className="rounded-lg border border-amber/30 bg-amber-soft px-3 py-2 text-xs leading-relaxed text-amber">
          Priority and acceptance criteria have both moved, and one change is logged at a
          time. Only the{" "}
          <span className="font-semibold">
            {changeType === "PRIORITY_CHANGED" ? "priority" : "criteria"}
          </span>{" "}
          edit will be saved — log the other as a second change.
        </p>
      ) : null}

      {/* What actually edits the story, for whichever type is selected. */}
      <ChangeEditor
        changeType={changeType}
        story={loaded}
        priority={priority}
        description={description}
        onDescription={setDescription}
        acceptanceCriteria={acceptanceCriteria}
        storyPoints={storyPoints}
        onStoryPoints={setStoryPoints}
      />

      {/* The two free-text fields, paired — neither needs the full width. */}
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="space-y-1.5">
          <Label htmlFor="story-change-note">Detail</Label>
          <Textarea
            id="story-change-note"
            value={note}
            onChange={(event) => setNote(event.target.value)}
            rows={2}
            placeholder="Why this moved, and who asked"
          />
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="story-change-requester">Requested by</Label>
          <Input
            id="story-change-requester"
            value={requestedByName}
            onChange={(event) => setRequestedByName(event.target.value)}
            placeholder="e.g. Product owner, compliance"
          />
        </div>
      </div>

      {loaded.sprintId ? (
        <label className="flex items-start gap-2 text-sm">
          <input
            type="checkbox"
            checked={attachToSprint}
            onChange={(event) => setAttachToSprint(event.target.checked)}
            className="mt-0.5"
          />
          <span>
            Charge this to the story&rsquo;s sprint
            <span className="block text-xs text-ink-3">
              Uncheck for a change agreed outside the sprint — planning churn should not
              be billed to whichever sprint happens to be running.
            </span>
          </span>
        </label>
      ) : (
        <p className="text-xs text-ink-3">
          This story is not in a sprint, so the change goes on the record without
          charging any sprint&rsquo;s risk.
        </p>
      )}

      <DialogFooter>
        <Button type="button" variant="ghost" onClick={onDone}>
          Cancel
        </Button>
        <Button type="submit" disabled={apply.isPending}>
          {apply.isPending ? (
            <Loader2 className="size-3.5 animate-spin" aria-hidden />
          ) : null}
          Log change
        </Button>
      </DialogFooter>
    </form>
  );
}

/**
 * The field this change type owns, pre-filled, with a live reading of which way it moved.
 *
 * <p>The direction is shown before the change is logged and computed the same way the
 * server computes it. A form that surprised somebody with the sign afterwards would teach
 * them to stop logging the changes that look expensive — which are the ones worth having
 * on the record.</p>
 *
 * <p>It is a preview, not the decision. The server derives the sign again from the story
 * it actually loads, so a stale form cannot talk it into the wrong answer.</p>
 */
function ChangeEditor({
  changeType,
  story,
  priority,
  description,
  onDescription,
  acceptanceCriteria,
  storyPoints,
  onStoryPoints,
}: {
  changeType: RequirementChangeType;
  story: Story;
  priority: Priority;
  description: string;
  onDescription: (value: string) => void;
  acceptanceCriteria: string;
  storyPoints: string;
  onStoryPoints: (value: string) => void;
}) {
  const field = TYPE_FIELD[changeType];

  if (field === "none") {
    return (
      <p className="rounded-lg border border-dashed border-line px-3 py-2 text-xs leading-relaxed text-ink-3">
        &ldquo;Other&rdquo; changes nothing on the story — it goes on the record as a note, and
        is charged as a small increase.
      </p>
    );
  }

  if (field === "priority") {
    // The control is a permanent field further up the form. Two selects bound to one
    // value would let the form disagree with itself about what is being logged.
    return (
      <p className="rounded-lg border border-dashed border-line px-3 py-2 text-xs leading-relaxed text-ink-3">
        Set the new priority in the <span className="font-medium text-ink-2">Priority</span>{" "}
        field above. The direction appears beside it as soon as it moves.
      </p>
    );
  }

  if (field === "acceptanceCriteria") {
    // Also a permanent field further up the form, for the same reason as priority: one
    // value, one control, or the form can disagree with itself about what is being
    // logged.
    return (
      <p className="rounded-lg border border-dashed border-line px-3 py-2 text-xs leading-relaxed text-ink-3">
        Add, edit or delete rows in the{" "}
        <span className="font-medium text-ink-2">Acceptance criteria</span> field above. The
        direction appears beside it as soon as the list changes.
      </p>
    );
  }

  const editor =
    field === "storyPoints" ? (
      <Input
        id="story-change-field"
        type="number"
        min={0}
        step="0.5"
        value={storyPoints}
        onChange={(event) => onStoryPoints(event.target.value)}
        placeholder="New estimate in points"
      />
    ) : (
      <Textarea
        id="story-change-field"
        rows={4}
        value={description}
        onChange={(event) => onDescription(event.target.value)}
        placeholder="What this story is, in full"
      />
    );

  return (
    <div className="space-y-1.5 rounded-lg border border-line bg-surface-2/50 p-3">
      <Label htmlFor="story-change-field">{FIELD_LABEL[field]}</Label>
      {editor}
      <DirectionPreview
        field={field}
        story={story}
        priority={priority}
        description={description}
        acceptanceCriteria={acceptanceCriteria}
        storyPoints={storyPoints}
        changeType={changeType}
      />
    </div>
  );
}

const FIELD_LABEL: Record<string, string> = {
  description: "Description",
  acceptanceCriteria: "Acceptance criteria",
  storyPoints: "New estimate",
};

function DirectionPreview({
  field,
  story,
  priority,
  description,
  acceptanceCriteria,
  storyPoints,
  changeType,
}: {
  field: string;
  story: Story;
  priority: Priority;
  description: string;
  acceptanceCriteria: string;
  storyPoints: string;
  changeType: RequirementChangeType;
}) {
  const moved = movement(field, story, priority, description, acceptanceCriteria, storyPoints);

  if (moved === 0) {
    return (
      <p className="text-xs text-ink-3">
        Nothing has moved yet — edit the field above and the direction appears here.
      </p>
    );
  }

  // Scope carries its sign in its name, whichever way the estimate went; the server
  // refuses the pair that disagree rather than quietly logging one of them.
  const incoherent =
    (changeType === "SCOPE_ADDED" && moved < 0) || (changeType === "SCOPE_REMOVED" && moved > 0);
  if (incoherent) {
    return (
      <p className="text-xs font-medium text-amber">
        {changeType === "SCOPE_ADDED"
          ? "Scope added, but the estimate went down — log this as scope removed."
          : "Scope removed, but the estimate went up — log this as scope added."}
      </p>
    );
  }

  const up = moved > 0;
  const Icon = up ? ArrowUp : ArrowDown;
  return (
    <p className={cn("flex items-center gap-1.5 text-xs font-medium", up ? "text-red" : "text-green")}>
      <Icon className="size-3 shrink-0" aria-hidden />
      {up
        ? "Reads as harder — requirement-change risk will go up."
        : "Reads as easier — requirement-change risk will come down."}
    </p>
  );
}

/** Mirrors the server's comparison: sign of after minus before. */
function movement(
  field: string,
  story: Story,
  priority: Priority,
  description: string,
  acceptanceCriteria: string,
  storyPoints: string,
): number {
  switch (field) {
    case "priority":
      return Math.sign(PRIORITY_WEIGHT[priority] - PRIORITY_WEIGHT[story.priority]);
    case "storyPoints": {
      if (storyPoints.trim() === "") return 0;
      return Math.sign(Number(storyPoints) - (story.storyPoints ?? 0));
    }
    case "acceptanceCriteria": {
      const before = countCriteria(story.acceptanceCriteria);
      const after = countCriteria(acceptanceCriteria);
      if (before !== after) return Math.sign(after - before);
      return Math.sign(compact(acceptanceCriteria) - compact(story.acceptanceCriteria));
    }
    case "description":
      return Math.sign(compact(description) - compact(story.description));
    default:
      return 0;
  }
}

/**
 * How many criteria a block of text holds.
 *
 * <p>Shared with the editor rather than re-implemented, so "a criterion" means one thing
 * on both sides of this dialog — and the same thing the server counts when it decides
 * which way the change pushed.</p>
 */
function countCriteria(text: string | null | undefined): number {
  return parseCriteria(text).length;
}

function compact(text: string | null | undefined): number {
  return text ? text.replace(/\s+/g, " ").trim().length : 0;
}
