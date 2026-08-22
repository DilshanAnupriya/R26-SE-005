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

const NO_SPRINT = "__none__";

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

  const info: RequirementChangeTypeInfo | undefined = useMemo(
    () => types.data?.find((entry) => entry.changeType === changeType),
    [types.data, changeType],
  );

  // The two scope types carry their own sign; showing a direction control for them
  // would offer a choice the server discards.
  const directionFixed = info?.directionFixed ?? false;
  const effectiveDirection: ImpactDirection = directionFixed
    ? (info && info.increaseDelta < 0 ? "DECREASE" : "INCREASE")
    : direction;
  const delta = info
    ? effectiveDirection === "DECREASE"
      ? info.decreaseDelta
      : info.increaseDelta
    : null;
  const hint = info
    ? effectiveDirection === "DECREASE"
      ? info.decreaseHint
      : info.increaseHint
    : null;

  function submit(event: React.FormEvent) {
    event.preventDefault();
    if (log.isPending) return;
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
          setDescription("");
          setRequestedByName("");
          onOpenChange(false);
        },
      },
    );
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
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
            The consequence is stated before the change is logged, using the weights the
            server serves. A form that surprised you with the number afterwards would
            teach people to stop logging the changes that look expensive — which are
            exactly the ones worth having on the record.
          */}
          {delta != null ? (
            <div
              className={
                delta >= 0
                  ? "rounded-lg border border-line bg-red-soft/40 px-3 py-2 text-sm"
                  : "rounded-lg border border-line bg-green-soft/40 px-3 py-2 text-sm"
              }
            >
              <span className="font-semibold tabular-nums">{formatDelta(delta)}</span>
              <span className="text-ink-2"> — {hint}</span>
            </div>
          ) : null}

          <div className="space-y-1.5">
            <Label htmlFor="change-description">Detail</Label>
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
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={log.isPending || types.isLoading}>
              {log.isPending ? <Loader2 className="size-3.5 animate-spin" aria-hidden /> : null}
              Log change
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
