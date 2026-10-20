"use client";

import { useMemo, useState } from "react";
import { ArrowDownRight, ArrowUpRight, Loader2, Plus, Trash2 } from "lucide-react";
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
import { Textarea } from "@/components/ui/textarea";
import { Card, CardHeader } from "@/components/design/surfaces";
import type {
  ImpactDirection,
  RequirementChange,
  RequirementChangeSummary,
  RequirementChangeType,
  RequirementChangeTypeInfo,
} from "../api";
import type { Priority } from "@/lib/api/delivery-schemas";
import { useApplyRequirementChange, useBacklog } from "@/features/backlog/hooks";
import {
  useDeleteRequirementChange,
  useLogRequirementChange,
  useRequirementChangeTypes,
} from "../hooks";

const TYPE_LABEL: Record<RequirementChangeType, string> = {
  SCOPE_ADDED: "Scope added",
  SCOPE_REMOVED: "Scope removed",
  ACCEPTANCE_CRITERIA_CHANGED: "Acceptance criteria changed",
  PRIORITY_CHANGED: "Priority changed",
  DESCRIPTION_UPDATED: "Description updated",
  OTHER: "Other",
};

function formatDelta(delta: number): string {
  return `${delta >= 0 ? "+" : ""}${delta} pts`;
}

function formatWhen(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? iso
    : date.toLocaleString("en-GB", { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" });
}

/**
 * The requirement-change register for one sprint.
 *
 * <p>Shows the ledger, not just a count. A team looking at a high requirement-change
 * score needs to know it came from three scope additions and a priority reshuffle
 * rather than from a model's opinion — and a register that cannot be traced back to
 * named changes gets treated as one.</p>
 */
export function RequirementChangesPanel({
  projectId,
  sprintId,
  summary,
  loading,
}: {
  projectId: string;
  sprintId?: string;
  summary?: RequirementChangeSummary;
  loading?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const remove = useDeleteRequirementChange(projectId);

  const changes = summary?.changes ?? [];

  return (
    <Card>
      <CardHeader
        title="Requirement changes"
        subtitle={
          summary && summary.totalChanges > 0
            ? `${summary.totalChanges} logged · ${summary.increasingChanges} added pressure, ${summary.decreasingChanges} relieved it · net ${formatDelta(summary.netRiskDelta)}`
            : "Nothing logged against this sprint yet."
        }
        action={
          <Button size="sm" onClick={() => setOpen(true)} disabled={!projectId} className="gap-1.5">
            <Plus className="size-3.5" aria-hidden />
            Log a change
          </Button>
        }
      />

      {loading ? (
        <div className="space-y-2 p-4">
          {[0, 1, 2].map((row) => (
            <div key={row} className="h-10 animate-pulse rounded-lg bg-surface-3" />
          ))}
        </div>
      ) : changes.length === 0 ? (
        <p className="px-4 py-8 text-center text-sm text-ink-3">
          Requirements that move after a sprint starts are the single clearest predictor of it
          missing. Logging them here charges — or credits — the sprint&rsquo;s requirement-change
          risk straight away.
        </p>
      ) : (
        <ul className="divide-y divide-line">
          {changes.map((change) => (
            <ChangeRow
              key={change.id}
              change={change}
              onDelete={() => remove.mutate({ changeId: change.id, sprintId: change.sprintId })}
              deleting={remove.isPending}
            />
          ))}
        </ul>
      )}

      <LogChangeDialog
        projectId={projectId}
        sprintId={sprintId}
        open={open}
        onOpenChange={setOpen}
      />
    </Card>
  );
}

function ChangeRow({
  change,
  onDelete,
  deleting,
}: {
  change: RequirementChange;
  onDelete: () => void;
  deleting?: boolean;
}) {
  const increases = change.riskDelta >= 0;
  return (
    <li className="flex items-start gap-3 px-4 py-3">
      <span
        aria-hidden
        className={
          increases
            ? "mt-0.5 rounded-md bg-red-soft p-1 text-red"
            : "mt-0.5 rounded-md bg-green-soft p-1 text-green"
        }
      >
        {increases ? <ArrowUpRight className="size-3.5" /> : <ArrowDownRight className="size-3.5" />}
      </span>

      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-baseline gap-2">
          <span className="text-sm font-semibold">{TYPE_LABEL[change.changeType]}</span>
          <span
            className={
              increases
                ? "text-xs font-semibold text-red tabular-nums"
                : "text-xs font-semibold text-green tabular-nums"
            }
          >
            {formatDelta(change.riskDelta)}
          </span>
          {change.requirementCode ? (
            <span className="font-mono text-[11px] text-ink-3">{change.requirementCode}</span>
          ) : null}
        </div>
        {change.description ? (
          <p className="mt-0.5 text-sm text-ink-2">{change.description}</p>
        ) : null}
        <p className="mt-0.5 text-xs text-ink-3">
          {change.requestedByName ? `${change.requestedByName} · ` : ""}
          {formatWhen(change.changedAt)}
        </p>
      </div>

      <button
        type="button"
        onClick={onDelete}
        disabled={deleting}
        title="Withdraw this change and reverse its risk charge"
        className="mt-0.5 rounded p-1 text-ink-3 transition-colors hover:text-red focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none disabled:opacity-50"
      >
        <Trash2 className="size-3.5" aria-hidden />
        <span className="sr-only">Withdraw this change</span>
      </button>
    </li>
  );
}

/** Sentinel for "not about one story" — Radix Select cannot hold an empty value. */
const NO_STORY = "__none__";

/** The change types that edit a story, and so offer one to pick. */
const STORY_TYPES: ReadonlySet<RequirementChangeType> = new Set([
  "SCOPE_ADDED",
  "SCOPE_REMOVED",
  "DESCRIPTION_UPDATED",
  "PRIORITY_CHANGED",
  "ACCEPTANCE_CRITERIA_CHANGED",
]);

/**
 * A story's acceptance criteria as a list — one per non-blank line, which is how the
 * server counts them, so the list the form shows is the count the charge is made from.
 */
export function parseCriteria(text: string | null | undefined): string[] {
  return (text ?? "")
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
}

/** Lowest first, so a level is its index — the same ladder as the server's weights. */
const PRIORITY_LEVELS: readonly Priority[] = ["LOWEST", "LOW", "MEDIUM", "HIGH", "CRITICAL"];

const PRIORITY_LABEL: Record<Priority, string> = {
  LOWEST: "Lowest",
  LOW: "Low",
  MEDIUM: "Medium",
  HIGH: "High",
  CRITICAL: "Critical",
};

/**
 * How far a priority moved, signed: +2 for Low to High, -1 for Medium to Low. An unset
 * priority reads as Medium, as it does on the server. The charge is the change type's
 * per-level weight times this, so Low to High costs twice what Low to Medium does.
 */
export function priorityLevelsMoved(from: Priority | null | undefined, to: Priority): number {
  return PRIORITY_LEVELS.indexOf(to) - PRIORITY_LEVELS.indexOf(from ?? "MEDIUM");
}

/**
 * A description's length as the server measures it — whitespace runs collapsed, ends
 * trimmed — so the preview and the charge cannot disagree about which way an edit went.
 */
export function measuredLength(text: string | null | undefined): number {
  return text == null ? 0 : text.replace(/\s+/g, " ").trim().length;
}

function LogChangeDialog({
  projectId,
  sprintId,
  open,
  onOpenChange,
}: {
  projectId: string;
  sprintId?: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const types = useRequirementChangeTypes();
  const log = useLogRequirementChange(projectId);

  const [changeType, setChangeType] = useState<RequirementChangeType>("SCOPE_ADDED");
  const [direction, setDirection] = useState<ImpactDirection>("INCREASE");
  const [description, setDescription] = useState("");
  const [requestedByName, setRequestedByName] = useState("");
  const [attachToSprint, setAttachToSprint] = useState(true);
  const [storyId, setStoryId] = useState<string>(NO_STORY);
  const [storyDescription, setStoryDescription] = useState("");
  const [newPoints, setNewPoints] = useState("");
  const [newPriority, setNewPriority] = useState<Priority>("MEDIUM");
  const [criteria, setCriteria] = useState<string[]>([]);
  const [newCriterion, setNewCriterion] = useState("");

  // The sprint's own stories — the ones a change logged here would be charged against.
  const stories = useBacklog(open && sprintId ? projectId : undefined, {
    sprintId: sprintId ?? "",
    size: 100,
  });
  const sprintStories = useMemo(
    () => (stories.data?.content ?? []).filter((story) => story.sprintId === sprintId),
    [stories.data, sprintId],
  );
  const offersStory = STORY_TYPES.has(changeType);
  const story = offersStory ? sprintStories.find((entry) => entry.id === storyId) : undefined;
  const apply = useApplyRequirementChange(story?.id);

  const info: RequirementChangeTypeInfo | undefined = useMemo(
    () => types.data?.find((entry) => entry.changeType === changeType),
    [types.data, changeType],
  );

  function chooseStory(value: string) {
    setStoryId(value);
    const chosen = sprintStories.find((entry) => entry.id === value);
    // The current text and size are where the edit starts from: the form shows the story
    // as it stands, and what moves the risk is the difference from it.
    setStoryDescription(chosen?.description ?? "");
    setNewPoints(chosen?.storyPoints != null ? String(chosen.storyPoints) : "");
    setNewPriority(chosen?.priority ?? "MEDIUM");
    setCriteria(parseCriteria(chosen?.acceptanceCriteria));
    setNewCriterion("");
  }

  function addCriterion() {
    const text = newCriterion.trim();
    if (!text) return;
    setCriteria((current) => [...current, text]);
    setNewCriterion("");
  }

  // The two scope types carry their own sign; showing a direction control for them
  // would offer a choice the server discards. A description edited on a story is read
  // the same way — longer is more to build, shorter is less — so it hides the control too.
  const descriptionEdit = changeType === "DESCRIPTION_UPDATED" && story != null;
  const scopeEdit = (changeType === "SCOPE_ADDED" || changeType === "SCOPE_REMOVED") && story != null;
  const priorityEdit = changeType === "PRIORITY_CHANGED" && story != null;
  const criteriaEdit = changeType === "ACCEPTANCE_CRITERIA_CHANGED" && story != null;
  const criteriaBefore = parseCriteria(story?.acceptanceCriteria);
  const criteriaNet = criteriaEdit ? criteria.length - criteriaBefore.length : 0;
  // Same count but different criteria — one swapped for another — is read from the
  // length, exactly as the server reads it; the same length is no change at all.
  const criteriaLengthBefore = measuredLength(criteriaBefore.join("\n"));
  const criteriaLengthAfter = measuredLength(criteria.join("\n"));
  const criteriaSwapDirection: ImpactDirection | null =
    criteriaEdit && criteriaNet === 0
      ? criteriaLengthAfter > criteriaLengthBefore
        ? "INCREASE"
        : criteriaLengthAfter < criteriaLengthBefore
          ? "DECREASE"
          : null
      : null;
  const levels = priorityEdit
    ? priorityLevelsMoved(story?.priority, newPriority)
    : criteriaEdit
      ? criteriaNet
      : 0;
  const lengthBefore = measuredLength(story?.description);
  const lengthAfter = measuredLength(storyDescription);
  const descriptionDirection: ImpactDirection | null = descriptionEdit
    ? lengthAfter > lengthBefore
      ? "INCREASE"
      : lengthAfter < lengthBefore
        ? "DECREASE"
        : null
    : null;

  // A priority set on a story is read the same way: raised is harder, lowered is easier.
  const directionFixed =
    (info?.directionFixed ?? false) || descriptionEdit || priorityEdit || criteriaEdit;

  // Scope is recorded against the story's points, and has to move the way the type says
  // — the server refuses anything else, so the form says so before it is sent.
  const pointsBefore = story?.storyPoints ?? 0;
  const pointsAfter = newPoints.trim() === "" ? null : Number(newPoints);
  const scopeProblem = !scopeEdit
    ? null
    : pointsAfter == null || Number.isNaN(pointsAfter) || pointsAfter < 0
      ? "Enter the story's new size in points."
      : changeType === "SCOPE_ADDED" && pointsAfter <= pointsBefore
        ? `Scope added has to raise the size above ${pointsBefore} pts.`
        : changeType === "SCOPE_REMOVED" && pointsAfter >= pointsBefore
          ? `Scope removed has to lower the size below ${pointsBefore} pts.`
          : null;
  const descriptionUnchanged =
    (descriptionEdit && descriptionDirection == null) ||
    (priorityEdit && levels === 0) ||
    (criteriaEdit && criteriaNet === 0 && criteriaSwapDirection == null);
  const pending = log.isPending || apply.isPending;

  function reset() {
    setDescription("");
    setRequestedByName("");
    setStoryId(NO_STORY);
    setStoryDescription("");
    setNewPoints("");
    setNewPriority("MEDIUM");
    setCriteria([]);
    setNewCriterion("");
  }

  // Closing drops the draft. Reopened, the form has to show the story as it stands now,
  // not an edit nobody saved — or the next change would be measured from the wrong text.
  function close(next: boolean) {
    if (!next) reset();
    onOpenChange(next);
  }

  function submit(event: React.FormEvent) {
    event.preventDefault();
    if (pending || scopeProblem || descriptionUnchanged) return;

    if (story && (descriptionEdit || scopeEdit || priorityEdit || criteriaEdit)) {
      // The edit itself goes to the story, which logs it and charges the sprint in one
      // step — the register entry then describes a change that really happened.
      apply.mutate(
        {
          changeType,
          // A scope change sends its updated scope only when it was edited, so an
          // untouched box never rewrites the story's text.
          newDescription: descriptionEdit
            ? storyDescription
            : scopeEdit && storyDescription !== (story.description ?? "")
              ? storyDescription
              : undefined,
          newStoryPoints: scopeEdit ? pointsAfter : undefined,
          newPriority: priorityEdit ? newPriority : undefined,
          newAcceptanceCriteria: criteriaEdit ? criteria.join("\n") : undefined,
          description:
            description.trim() ||
            (descriptionEdit
              ? `${story.itemKey} description ${lengthAfter > lengthBefore ? "extended" : "cut"}: ${lengthBefore} → ${lengthAfter} characters`
              : criteriaEdit
                ? `${story.itemKey} acceptance criteria: ${criteriaBefore.length} → ${criteria.length}`
                : priorityEdit
                ? `${story.itemKey} priority ${PRIORITY_LABEL[story.priority ?? "MEDIUM"]} → ${PRIORITY_LABEL[newPriority]}`
                : `${story.itemKey}: ${pointsBefore} → ${pointsAfter} pts`),
          requestedByName: requestedByName.trim() || undefined,
          attachToSprint,
        },
        {
          onSuccess: () => {
            reset();
            onOpenChange(false);
          },
        },
      );
      return;
    }

    log.mutate(
      {
        changeType,
        impactDirection: directionFixed ? undefined : direction,
        sprintId: attachToSprint ? (sprintId ?? null) : null,
        description: description.trim() || undefined,
        requestedByName: requestedByName.trim() || undefined,
      },
      {
        onSuccess: () => {
          reset();
          onOpenChange(false);
        },
      },
    );
  }

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Log a requirement change</DialogTitle>
          <DialogDescription>
            Recorded against the register and charged to the sprint in flight straight away.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={submit} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="change-type">What changed</Label>
            <Select
              value={changeType}
              onValueChange={(value) => setChangeType(value as RequirementChangeType)}
            >
              <SelectTrigger id="change-type" className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {(types.data ?? []).map((entry) => (
                  <SelectItem key={entry.changeType} value={entry.changeType}>
                    {TYPE_LABEL[entry.changeType]}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {offersStory ? (
            <div className="space-y-1.5">
              <Label htmlFor="change-story">Select the story you want to change</Label>
              <Select value={story ? storyId : NO_STORY} onValueChange={chooseStory}>
                <SelectTrigger id="change-story" className="w-full" disabled={!sprintId}>
                  <SelectValue
                    placeholder={!sprintId ? "Pick a sprint above first" : "Choose a story"}
                  />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={NO_STORY}>Not about one story</SelectItem>
                  {sprintStories.map((entry) => (
                    <SelectItem key={entry.id} value={entry.id}>
                      {entry.itemKey} · {entry.title}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {sprintId && !stories.isLoading && sprintStories.length === 0 ? (
                <p className="text-xs text-ink-3">This sprint has no stories yet.</p>
              ) : null}
            </div>
          ) : null}

          {descriptionEdit ? (
            <div className="space-y-1.5">
              <Label htmlFor="change-story-description">Description of {story?.itemKey}</Label>
              <Textarea
                id="change-story-description"
                value={storyDescription}
                onChange={(event) => setStoryDescription(event.target.value)}
                rows={6}
                placeholder="This story has no description yet"
              />
              <p className="text-xs text-ink-3 tabular-nums">
                {lengthBefore} → {lengthAfter} characters. Adding to it raises requirement risk;
                cutting it lowers it.
              </p>
            </div>
          ) : null}

          {priorityEdit ? (
            <div className="space-y-1.5">
              <Label htmlFor="change-story-priority">
                New priority of {story?.itemKey} (now {PRIORITY_LABEL[story?.priority ?? "MEDIUM"]})
              </Label>
              <Select value={newPriority} onValueChange={(value) => setNewPriority(value as Priority)}>
                <SelectTrigger id="change-story-priority" className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {[...PRIORITY_LEVELS].reverse().map((level) => (
                    <SelectItem key={level} value={level}>
                      {PRIORITY_LABEL[level]}
                      {level === (story?.priority ?? "MEDIUM") ? " (current)" : ""}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <p className="text-xs text-ink-3">
                Raising it adds requirement risk and lowering it takes risk off — more for each
                level it moves.
              </p>
            </div>
          ) : null}

          {criteriaEdit ? (
            <div className="space-y-1.5">
              <Label htmlFor="change-new-criterion">
                Acceptance criteria of {story?.itemKey} ({criteriaBefore.length} now)
              </Label>
              {criteria.length === 0 ? (
                <p className="rounded-lg border border-dashed border-line px-3 py-2 text-xs text-ink-3">
                  No acceptance criteria. Add one below.
                </p>
              ) : (
                <ul className="divide-y divide-line rounded-lg border border-line">
                  {criteria.map((criterion, index) => (
                    <li key={`${index}-${criterion}`} className="flex items-start gap-2 px-3 py-2">
                      <span className="min-w-0 flex-1 text-sm break-words">{criterion}</span>
                      <button
                        type="button"
                        onClick={() =>
                          setCriteria((current) => current.filter((_, at) => at !== index))
                        }
                        title="Delete this acceptance criterion"
                        className="shrink-0 rounded p-1 text-ink-3 transition-colors hover:text-red focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
                      >
                        <Trash2 className="size-3.5" aria-hidden />
                        <span className="sr-only">Delete “{criterion}”</span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
              <div className="flex items-center gap-2">
                <Input
                  id="change-new-criterion"
                  value={newCriterion}
                  onChange={(event) => setNewCriterion(event.target.value)}
                  onKeyDown={(event) => {
                    // Enter adds the criterion rather than submitting the whole form.
                    if (event.key === "Enter") {
                      event.preventDefault();
                      addCriterion();
                    }
                  }}
                  placeholder="Add an acceptance criterion"
                />
                <Button
                  type="button"
                  size="icon"
                  variant="outline"
                  onClick={addCriterion}
                  disabled={!newCriterion.trim()}
                  title="Add this acceptance criterion"
                >
                  <Plus className="size-4" aria-hidden />
                  <span className="sr-only">Add acceptance criterion</span>
                </Button>
              </div>
              <p className="text-xs text-ink-3 tabular-nums">
                {criteriaBefore.length} → {criteria.length} criteria. Each one added raises
                requirement risk; each one deleted lowers it.
              </p>
            </div>
          ) : null}

          {scopeEdit ? (
            <div className="space-y-1.5">
              <Label htmlFor="change-story-points">
                New size of {story?.itemKey} (now {pointsBefore} pts)
              </Label>
              <Input
                id="change-story-points"
                type="number"
                min={0}
                step="0.5"
                value={newPoints}
                onChange={(event) => setNewPoints(event.target.value)}
              />
              {scopeProblem ? <p className="text-xs text-red">{scopeProblem}</p> : null}
            </div>
          ) : null}

          {scopeEdit ? (
            <div className="space-y-1.5">
              <Label htmlFor="change-story-scope">Updated scope of {story?.itemKey}</Label>
              <Textarea
                id="change-story-scope"
                value={storyDescription}
                onChange={(event) => setStoryDescription(event.target.value)}
                rows={5}
                placeholder="Describe the story's scope as it now stands"
              />
              <p className="text-xs text-ink-3">
                {changeType === "SCOPE_ADDED"
                  ? "Write in what was added."
                  : "Take out what was removed."}{" "}
                Saved to the story with its new size — the size decides the charge.
              </p>
            </div>
          ) : null}

          {!directionFixed ? (
            <div className="space-y-1.5">
              <Label htmlFor="change-direction">Did this make delivery harder or easier?</Label>
              <Select
                value={direction}
                onValueChange={(value) => setDirection(value as ImpactDirection)}
              >
                <SelectTrigger id="change-direction" className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="INCREASE">Harder — more work or more ambiguity</SelectItem>
                  <SelectItem value="DECREASE">Easier — less work or an ambiguity resolved</SelectItem>
                </SelectContent>
              </Select>
            </div>
          ) : null}

          {/*
            The charge itself is not previewed: the server works it out when the change is
            logged, and the register below shows it once it is on the record.
          */}
          {descriptionUnchanged ? (
            <div className="rounded-lg border border-line bg-surface-2 px-3 py-2 text-sm text-ink-3">
              {priorityEdit
                ? "Pick a different priority to log a change."
                : criteriaEdit
                  ? "Add or delete an acceptance criterion to log a change."
                  : "Edit the description to log a change — add to it or cut from it."}
            </div>
          ) : null}

          <div className="space-y-1.5">
            <Label htmlFor="change-description">
              {descriptionEdit || scopeEdit ? "Why it changed (optional)" : "Detail"}
            </Label>
            <Textarea
              id="change-description"
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              rows={3}
              placeholder="What moved, and why"
            />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="change-requester">Requested by</Label>
            <Input
              id="change-requester"
              value={requestedByName}
              onChange={(event) => setRequestedByName(event.target.value)}
              placeholder="e.g. Product owner, compliance, the customer"
            />
          </div>

          {sprintId ? (
            <label className="flex items-start gap-2 text-sm">
              <input
                type="checkbox"
                checked={attachToSprint}
                onChange={(event) => setAttachToSprint(event.target.checked)}
                className="mt-0.5"
              />
              <span>
                Charge this to the current sprint
                <span className="block text-xs text-ink-3">
                  Uncheck for a change agreed outside a sprint — planning churn should not be
                  billed to whichever sprint happens to be running.
                </span>
              </span>
            </label>
          ) : null}

          <DialogFooter>
            <Button type="button" variant="ghost" onClick={() => close(false)}>
              Cancel
            </Button>
            <Button
              type="submit"
              disabled={pending || types.isLoading || Boolean(scopeProblem) || descriptionUnchanged}
            >
              {pending ? <Loader2 className="size-3.5 animate-spin" aria-hidden /> : null}
              Log change
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
