"use client";

import { AlertTriangle } from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import type { Task } from "@/lib/api/delivery-schemas";
import { computeTaskClock, formatCompact } from "@/lib/task-clock";
import { cn } from "@/lib/utils";
import { isBoardTask } from "../api";
import type { BoardMember } from "./kanban-board";

/**
 * Open board tasks with nobody assigned to them.
 *
 * <p>Real tasks only. A story card's clock starts when the story was written, often long
 * before the sprint, so "waited this long for an owner" would mean nothing for one — and
 * the server only charges tasks, so listing stories here would promise a credit that
 * assigning them never pays.</p>
 */
export function unassignedTasks(tasks: Task[]): Task[] {
  return tasks.filter(
    (task) =>
      isBoardTask(task) &&
      !task.assigneeId &&
      task.status !== "DONE" &&
      task.status !== "CANCELLED",
  );
}

/**
 * The danger sign beside the board's Live badge.
 *
 * <p>Red and blinking while any open task has no owner, because each one is charging the
 * sprint's collaboration risk for as long as it waits. Opening it lists those tasks with
 * an assignee picker on each, so the fix is one click from the warning rather than a hunt
 * across the columns. Assigning early matters: the server gives back the share of the
 * charge matching the estimate still left, so the remaining time is shown beside each.</p>
 */
export function UnassignedAlert({
  tasks,
  members,
  onAssign,
  disabled,
}: {
  tasks: Task[];
  members: BoardMember[];
  onAssign: (taskId: string, assigneeId: string) => void;
  disabled?: boolean;
}) {
  const waiting = unassignedTasks(tasks);
  const count = waiting.length;
  const label =
    count === 0
      ? "Every open task has an owner"
      : `${count} task${count === 1 ? "" : "s"} with nobody assigned`;

  return (
    <Popover>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label={label}
          title={label}
          className={cn(
            "relative inline-flex size-8 items-center justify-center rounded-md border transition-colors focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none",
            count > 0
              ? "border-red/40 bg-red-soft text-red hover:bg-red-soft/80"
              : "border-border bg-background text-muted-foreground hover:text-foreground",
          )}
        >
          <AlertTriangle
            className={cn("size-4", count > 0 && "animate-pulse motion-reduce:animate-none")}
            aria-hidden
          />
          {count > 0 ? (
            <span className="absolute -top-1.5 -right-1.5 flex size-4 items-center justify-center">
              <span
                aria-hidden
                className="absolute inline-flex size-full animate-ping rounded-full bg-red opacity-60 motion-reduce:animate-none"
              />
              <span className="relative inline-flex size-4 items-center justify-center rounded-full bg-red text-[10px] font-bold text-white tabular-nums">
                {count > 9 ? "9+" : count}
              </span>
            </span>
          ) : null}
        </button>
      </PopoverTrigger>

      <PopoverContent align="end" className="w-80 p-0">
        <div className="border-b border-border px-3 py-2.5">
          <p className="text-sm font-semibold">
            {count === 0 ? "Everything is assigned" : `Unassigned tasks (${count})`}
          </p>
          <p className="mt-0.5 text-xs leading-relaxed text-muted-foreground">
            {count === 0
              ? "No open task is waiting for an owner."
              : "Each one raises collaboration risk by its size until somebody owns it. Assigning gives back the share of that charge matching the time still left."}
          </p>
        </div>

        {count > 0 ? (
          <ul className="max-h-80 divide-y divide-border overflow-y-auto">
            {waiting.map((task) => {
              const clock = computeTaskClock(task);
              const remaining =
                clock.remainingSeconds == null
                  ? "No estimate"
                  : clock.remainingSeconds > 0
                    ? `${formatCompact(clock.remainingSeconds)} left`
                    : "Out of time";
              return (
                <li key={task.id} className="space-y-1.5 px-3 py-2.5">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <p className="font-mono text-[11px] text-muted-foreground">{task.itemKey}</p>
                      <p className="truncate text-sm font-medium" title={task.title}>
                        {task.title}
                      </p>
                    </div>
                    <span
                      className={cn(
                        "shrink-0 rounded px-1.5 py-0.5 text-[11px] tabular-nums",
                        clock.remainingSeconds === 0
                          ? "bg-red-soft text-red"
                          : "bg-muted text-muted-foreground",
                      )}
                    >
                      {remaining}
                    </span>
                  </div>
                  <label className="block">
                    <span className="sr-only">Assign {task.itemKey}</span>
                    <select
                      className="h-8 w-full rounded-md border bg-background px-2 text-sm focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none disabled:opacity-50"
                      value=""
                      disabled={disabled || members.length === 0}
                      onChange={(event) => {
                        if (event.target.value) {
                          onAssign(task.id, event.target.value);
                        }
                      }}
                    >
                      <option value="">
                        {members.length === 0 ? "No members to assign" : "Assign to…"}
                      </option>
                      {members.map((member) => {
                        const id = member.userId || member.id || "";
                        return id ? (
                          <option key={id} value={id}>
                            {member.fullName || member.email || "Member"}
                          </option>
                        ) : null;
                      })}
                    </select>
                  </label>
                </li>
              );
            })}
          </ul>
        ) : null}
      </PopoverContent>
    </Popover>
  );
}
