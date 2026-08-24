"use client";

import { AlertTriangle, RotateCcw } from "lucide-react";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import type { Task } from "@/lib/api/delivery-schemas";
import { clockLabel, computeTaskClock, formatCompact } from "@/lib/task-clock";
import { cn } from "@/lib/utils";
import { isBoardTask } from "../api";

/** Each status in the tone the board already uses for it. */
const STATUS_TONE: Record<string, string> = {
  TODO: "bg-muted text-muted-foreground",
  IN_PROGRESS: "bg-blue-500/10 text-blue-700 dark:text-blue-400",
  IN_REVIEW: "bg-violet-500/10 text-violet-700 dark:text-violet-400",
  BLOCKED: "bg-destructive/10 text-destructive",
  DONE: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
  CANCELLED: "bg-muted text-muted-foreground line-through",
};

const PRIORITY_TONE: Record<string, string> = {
  CRITICAL: "text-red-600 dark:text-red-400",
  HIGH: "text-amber-600 dark:text-amber-400",
  MEDIUM: "text-foreground",
  LOW: "text-muted-foreground",
  LOWEST: "text-muted-foreground",
};

function initials(name?: string | null) {
  if (!name) return "?";
  return name.split(/\s+/).slice(0, 2).map((part) => part[0]?.toUpperCase() ?? "").join("");
}

function humanise(value: string) {
  return value.replace(/_/g, " ").toLowerCase().replace(/^./, (character) => character.toUpperCase());
}

/** `480` becomes `1d`. Minutes are how the API talks; nobody reads a card in them. */
function duration(value?: number | null): string | null {
  if (value == null) return null;
  if (value < 60) return `${value}m`;
  const hours = value / 60;
  if (hours < 8) return `${Number(hours.toFixed(1))}h`;
  return `${Number((hours / 8).toFixed(1))}d`;
}

function timestamp(iso?: string | null): string | null {
  if (!iso) return null;
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? null
    : date.toLocaleString("en-GB", {
        day: "2-digit",
        month: "short",
        year: "numeric",
        hour: "2-digit",
        minute: "2-digit",
      });
}

function day(iso?: string | null): string | null {
  if (!iso) return null;
  const date = new Date(`${iso}T00:00:00Z`);
  return Number.isNaN(date.getTime())
    ? null
    : date.toLocaleDateString("en-GB", {
        day: "2-digit",
        month: "short",
        year: "numeric",
        timeZone: "UTC",
      });
}

/**
 * Everything the board knows about one card.
 *
 * <p>Read-only on purpose. The board itself already carries the two edits people make
 * from it — moving the card and reassigning it — and a dialog that also edited them
 * would give both fields two homes that can disagree. This one answers "what is this?",
 * which a card of four badges and a title cannot.</p>
 *
 * <p>Opening it counts as a reopen. The count, and what it is costing the sprint, are
 * shown here rather than only as a badge on the card: this is the surface where someone
 * has asked for the whole story of the task, and it is also the only place where the act
 * of looking is what moved the number.</p>
 */
export function TaskDetailDialog({
  task,
  open,
  onOpenChange,
  onReopen,
  reopenPending,
}: {
  task: Task | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Sends a finished card back for rework. Omit to hide the control. */
  onReopen?: (task: Task) => void;
  reopenPending?: boolean;
}) {
  if (!task) {
    return null;
  }

  const clock = computeTaskClock(task);
  const timer = clockLabel(clock);
  const finished = task.status === "DONE" || task.status === "CANCELLED";
  const reopens = task.reopenedCount ?? 0;

  // Built as a list and filtered rather than written out as markup with a guard around
  // each line: most cards carry half of these, and fifteen conditionals in the JSX is
  // where a stray one starts rendering an empty row.
  const rows: { label: string; value: React.ReactNode }[] = [
    { label: "Status", value: humanise(task.status) },
    { label: "Type", value: humanise(task.type) },
    {
      label: "Priority",
      value: (
        <span className={cn("font-medium", PRIORITY_TONE[task.priority] ?? "text-foreground")}>
          {humanise(task.priority)}
        </span>
      ),
    },
    {
      label: "Assignee",
      value: task.assigneeId ? (
        <span className="inline-flex items-center gap-1.5">
          <Avatar className="size-4">
            <AvatarImage src={task.assigneeAvatarUrl ?? undefined} alt={task.assigneeName ?? ""} />
            <AvatarFallback className="bg-primary text-[8px] text-white">
              {initials(task.assigneeName)}
            </AvatarFallback>
          </Avatar>
          {task.assigneeName ?? "Developer"}
        </span>
      ) : (
        <span className="text-muted-foreground">Unassigned</span>
      ),
    },
    { label: "Estimate", value: duration(task.estimateMinutes) },
    { label: "Logged", value: duration(task.spentMinutes) },
    { label: "Remaining", value: duration(task.remainingMinutes) },
    { label: "Due", value: day(task.dueDate) },
    { label: "On the clock", value: clock.tracked ? formatCompact(clock.elapsedSeconds) : null },
    { label: timer?.label ?? "", value: timer?.value ?? null },
    { label: "Started", value: timestamp(task.startedAt) },
    { label: "Finished", value: timestamp(task.completedAt) },
    { label: "Created", value: timestamp(task.createdAt) },
    {
      label: "Skills",
      value: task.requiredSkills.length > 0 ? task.requiredSkills.join(", ") : null,
    },
  ].filter((row) => row.label !== "" && row.value != null);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <div className="flex flex-wrap items-center gap-2 pr-8">
            <span className="font-mono text-xs text-muted-foreground tabular-nums">
              {task.itemKey}
            </span>
            <span
              className={cn(
                "rounded px-1.5 py-px text-[10px] font-semibold tracking-[0.06em] uppercase",
                STATUS_TONE[task.status] ?? "bg-muted text-muted-foreground",
              )}
            >
              {humanise(task.status)}
            </span>
            {task.blocked ? (
              <Badge variant="destructive" className="gap-1 text-[10px]">
                <AlertTriangle className="size-3" aria-hidden />
                blocked
              </Badge>
            ) : null}
          </div>
          <DialogTitle className="text-left leading-snug">{task.title}</DialogTitle>
          <DialogDescription className="max-h-32 overflow-y-auto text-left whitespace-pre-wrap">
            {task.description?.trim() ? task.description : "No description."}
          </DialogDescription>
        </DialogHeader>

        {task.blocked && task.blockedReason ? (
          <p className="rounded-md border border-destructive/40 bg-destructive/5 px-2.5 py-1.5 text-xs text-destructive">
            {task.blockedReason}
          </p>
        ) : null}

        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-xs">
          {rows.map((row) => (
            <div key={row.label} className="contents">
              <dt className="text-muted-foreground">{row.label}</dt>
              <dd className="text-right tabular-nums">{row.value}</dd>
            </div>
          ))}
        </dl>

        {/*
          The reopen count gets its own block rather than another row in the list above.
          It is the one number on this dialog that opening the dialog changed, and a
          reader who is not told why it went up will read it as a bug.
        */}
        <div
          className={cn(
            "rounded-md border px-2.5 py-2 text-xs",
            reopens > 0 ? "border-amber-500/40 bg-amber-500/5" : "border-border bg-muted/40",
          )}
        >
          <div className="flex items-center justify-between gap-2">
            <span className="inline-flex items-center gap-1.5 font-medium">
              <RotateCcw className="size-3.5 text-amber-600 dark:text-amber-400" aria-hidden />
              Reopened
            </span>
            <span className="font-mono text-sm font-semibold tabular-nums">
              {reopens} time{reopens === 1 ? "" : "s"}
            </span>
          </div>
          <p className="mt-1 text-[11px] text-muted-foreground">
            {reopens > 0
              ? "Opening this task counts as a reopen. Every one of them raises this sprint's over-budget risk, and each raises it by more than the last — the estimate is already spent by then."
              : "Opening this task counts as a reopen, which raises this sprint's over-budget risk."}
          </p>
        </div>

        <DialogFooter showCloseButton>
          {finished && isBoardTask(task) && onReopen ? (
            <Button
              variant="outline"
              disabled={reopenPending}
              onClick={() => onReopen(task)}
              className="gap-1.5"
            >
              <RotateCcw className="size-3.5" aria-hidden />
              Send back for rework
            </Button>
          ) : null}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
