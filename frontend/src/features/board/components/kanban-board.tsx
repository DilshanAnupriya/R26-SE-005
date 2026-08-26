"use client";

import { useState } from "react";
import { AlertTriangle, Check, GripVertical, RotateCcw, UserPlus, UserX } from "lucide-react";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import type { Task, WorkflowState } from "@/lib/api/delivery-schemas";
import { isBoardTask, type TaskBoard } from "../api";
import { cn } from "@/lib/utils";
import { CommentBadge, OverdueBadge, ReopenBadge, TaskClockRow } from "./task-clock-row";

function initials(name?: string | null) {
  if (!name) return "?";
  return name.split(/\s+/).slice(0, 2).map((part) => part[0]?.toUpperCase() ?? "").join("");
}

export interface BoardMember {
  userId?: string;
  id?: string;
  fullName?: string | null;
  email?: string | null;
  avatarUrl?: string | null;
  jobTitle?: string | null;
}

/**
 * The project kanban board with Jira-style interactive developer assignments.
 */
export function KanbanBoard({
  board,
  onMove,
  onAssign,
  onReopen,
  onOpenTask,
  onCommentUnanswered,
  onTaskOverdue,
  members = [],
  disabled,
}: {
  board: TaskBoard;
  onMove: (taskId: string, state: WorkflowState) => void;
  onAssign?: (taskId: string, assigneeId: string | null) => void;
  /** Sends a finished card back for rework. Only offered on finished cards. */
  onReopen?: (task: Task) => void;
  /**
   * Opens a card's detail dialog. Every open counts as a reopen, so the handler is what
   * decides whether it does — the board only reports that a card was clicked.
   */
  onOpenTask?: (task: Task) => void;
  /**
   * Fires when a card's oldest unanswered comment passes half its estimate. Same
   * arrangement as `onTaskOverdue`: the board is ticking anyway to draw the wait, so it
   * is the only thing that knows the moment it crosses.
   */
  onCommentUnanswered?: (task: Task) => void;
  /**
   * Fires the first time an open card's countdown crosses its estimate. The board is
   * the only thing that knows the moment it happens — it is already ticking to draw it.
   */
  onTaskOverdue?: (task: Task) => void;
  members?: BoardMember[];
  disabled?: boolean;
}) {
  // The card the keyboard has picked up, and which column it is hovering over.
  const [carrying, setCarrying] = useState<{ taskId: string; columnIndex: number } | null>(null);
  const [dragOverColumn, setDragOverColumn] = useState<string | null>(null);

  function moveCarried(direction: -1 | 1) {
    if (!carrying) return;
    const next = carrying.columnIndex + direction;
    if (next < 0 || next >= board.columns.length) return;
    setCarrying({ ...carrying, columnIndex: next });
  }

  function dropCarried() {
    if (!carrying) return;
    const target = board.columns[carrying.columnIndex];
    if (target) {
      onMove(carrying.taskId, target.state);
    }
    setCarrying(null);
  }

  return (
    <div className="flex gap-3 overflow-x-auto pb-2">
      {board.columns.map((column, columnIndex) => {
        const isDropTarget =
          dragOverColumn === column.state.id ||
          (carrying !== null && carrying.columnIndex === columnIndex);

        return (
          <section
            key={column.state.id}
            aria-labelledby={`column-${column.state.id}`}
            className={cn(
              "flex w-72 shrink-0 flex-col rounded-lg border bg-muted/30 transition-colors",
              isDropTarget && "border-primary bg-primary/5",
              column.wipLimitExceeded && "border-amber-500/50",
            )}
            onDragOver={(event) => {
              if (disabled) return;
              event.preventDefault();
              setDragOverColumn(column.state.id);
            }}
            onDragLeave={() => setDragOverColumn(null)}
            onDrop={(event) => {
              event.preventDefault();
              setDragOverColumn(null);
              const taskId = event.dataTransfer.getData("text/plain");
              if (taskId) {
                onMove(taskId, column.state);
              }
            }}
          >
            <header className="flex items-center justify-between gap-2 border-b px-3 py-2">
              <h3 id={`column-${column.state.id}`} className="text-sm font-medium">
                {column.state.name}
              </h3>
              {column.state.wipLimit != null ? (
                <Badge
                  variant={column.wipLimitExceeded ? "destructive" : "secondary"}
                  className="tabular-nums"
                >
                  {column.wipLimitExceeded ? (
                    <AlertTriangle className="size-3" aria-hidden />
                  ) : null}
                  {column.itemCount}/{column.state.wipLimit}
                </Badge>
              ) : (
                <span className="text-xs text-muted-foreground tabular-nums">
                  {column.itemCount}
                </span>
              )}
            </header>

            {column.wipLimitExceeded ? (
              <p className="px-3 pt-2 text-[11px] text-amber-700 dark:text-amber-400">
                Over the WIP limit — finish something before starting more.
              </p>
            ) : null}

            <ul className="flex-1 space-y-2 p-2">
              {column.tasks.map((task) => (
                <li key={task.id}>
                  <TaskCard
                    task={task}
                    members={members}
                    onAssign={onAssign}
                    onReopen={onReopen}
                    onOpenTask={onOpenTask}
                    onCommentUnanswered={onCommentUnanswered}
                    onTaskOverdue={onTaskOverdue}
                    carried={carrying?.taskId === task.id}
                    disabled={disabled}
                    onPickUp={() => setCarrying({ taskId: task.id, columnIndex })}
                    onDropCarried={dropCarried}
                    onMoveCarried={moveCarried}
                    onCancel={() => setCarrying(null)}
                  />
                </li>
              ))}

              {column.tasks.length === 0 ? (
                <li className="rounded-md border border-dashed px-3 py-6 text-center text-xs text-muted-foreground">
                  Nothing here
                </li>
              ) : null}
            </ul>

          </section>
        );
      })}
    </div>
  );
}

function TaskCard({
  task,
  members,
  onAssign,
  onReopen,
  onOpenTask,
  onCommentUnanswered,
  onTaskOverdue,
  carried,
  disabled,
  onPickUp,
  onDropCarried,
  onMoveCarried,
  onCancel,
}: {
  task: Task;
  members: BoardMember[];
  onAssign?: (taskId: string, assigneeId: string | null) => void;
  onReopen?: (task: Task) => void;
  onOpenTask?: (task: Task) => void;
  onCommentUnanswered?: (task: Task) => void;
  onTaskOverdue?: (task: Task) => void;
  carried?: boolean;
  disabled?: boolean;
  onPickUp?: () => void;
  onDropCarried?: () => void;
  onMoveCarried?: (direction: -1 | 1) => void;
  onCancel?: () => void;
}) {
  const draggable = !disabled && Boolean(onPickUp);
  const finished = task.status === "DONE" || task.status === "CANCELLED";
  // Stories are rendered through the task shape on this board and have no /tasks/{id} of
  // their own, so every control here that calls one is hidden for them — a Reopen button
  // or a detail dialog on a story row would 404.
  const isTask = isBoardTask(task);
  const openable = Boolean(onOpenTask) && isTask;

  /**
   * Opens the detail dialog from a click anywhere on the card that was not aimed at one
   * of the controls living on it.
   *
   * <p>The guard is a `closest` rather than a `stopPropagation` on each control, because
   * the assignee menu's items are portalled and the drag handle is focus-visible only:
   * one rule here is checkable, whereas six scattered ones are how the "assign" click
   * quietly starts costing the sprint a reopen too.</p>
   */
  function handleCardClick(event: React.MouseEvent<HTMLElement>) {
    if (!openable) return;
    const target = event.target as HTMLElement | null;
    if (target?.closest("button, a, input, select, textarea, [role='menuitem']")) {
      return;
    }
    onOpenTask?.(task);
  }

  return (
    <article
      draggable={draggable}
      onDragStart={(event) => {
        event.dataTransfer.setData("text/plain", task.id);
        event.dataTransfer.effectAllowed = "move";
      }}
      onClick={handleCardClick}
      className={cn(
        "group rounded-md border bg-background p-2.5 shadow-sm transition-shadow",
        draggable && "cursor-grab active:cursor-grabbing hover:shadow",
        openable && "hover:border-primary/40 hover:shadow",
        carried && "ring-2 ring-primary",
        task.blocked && "border-destructive/40",
      )}
    >
      <div className="flex items-start gap-2">
        {draggable ? (
          <button
            type="button"
            aria-pressed={carried}
            aria-label={
              carried
                ? `${task.itemKey} picked up. Left and right arrows choose a column, Enter drops it, Escape cancels.`
                : `Move ${task.itemKey}. Press Enter to pick it up.`
            }
            className="mt-0.5 shrink-0 rounded text-muted-foreground opacity-0 transition-opacity focus-visible:opacity-100 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none group-hover:opacity-100"
            onClick={() => (carried ? onDropCarried?.() : onPickUp?.())}
            onKeyDown={(event) => {
              if (!carried) return;
              if (event.key === "ArrowLeft") {
                event.preventDefault();
                onMoveCarried?.(-1);
              } else if (event.key === "ArrowRight") {
                event.preventDefault();
                onMoveCarried?.(1);
              } else if (event.key === "Escape") {
                event.preventDefault();
                onCancel?.();
              }
            }}
          >
            <GripVertical className="size-3.5" aria-hidden />
          </button>
        ) : null}

        <div className="min-w-0 flex-1">
          {/*
            The title doubles as the keyboard route into the details. The whole card is
            clickable, but a div with an onClick is reachable by nothing but a mouse, and
            the drag handle next to it is already the card's only other focus stop.
          */}
          {openable ? (
            <button
              type="button"
              onClick={() => onOpenTask?.(task)}
              className="w-full rounded text-left text-sm leading-snug hover:underline focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
              title={`Open ${task.itemKey}. Counts as a reopen.`}
            >
              {task.title}
            </button>
          ) : (
            <p className="text-sm leading-snug">{task.title}</p>
          )}
          <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
            <span className="text-[11px] text-muted-foreground tabular-nums font-mono">{task.itemKey}</span>
            {task.blocked ? (
              <Badge variant="destructive" className="text-[10px]">
                blocked
              </Badge>
            ) : null}
            {task.estimateMinutes ? (
              <span className="text-[11px] text-muted-foreground tabular-nums">
                {formatEstimate(task.estimateMinutes)}
              </span>
            ) : null}
            <ReopenBadge count={task.reopenedCount ?? 0} />
            <CommentBadge task={task} onUnanswered={onCommentUnanswered} />
            <OverdueBadge task={task} />
          </div>

          <TaskClockRow task={task} onExpired={onTaskOverdue} />

          {/*
            Only on a finished card. Offering "reopen" on a task nobody has finished is
            an action with no meaning, and the server rejects it — better not to show it.
          */}
          {finished && isTask && onReopen ? (
            <button
              type="button"
              disabled={disabled}
              onClick={() => onReopen(task)}
              className="mt-1.5 inline-flex items-center gap-1 rounded border border-border px-1.5 py-0.5 text-[10px] text-muted-foreground transition-colors hover:border-amber-500/50 hover:text-amber-600 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none disabled:opacity-50 dark:hover:text-amber-400"
              title="Send this back for rework. Counts as a reopen and resumes the clock from where it froze."
            >
              <RotateCcw className="size-2.5" aria-hidden />
              Reopen
            </button>
          ) : null}
        </div>

        {/* Jira-style Assignee Dropdown */}
        <div className="shrink-0">
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              {task.assigneeId ? (
                <button
                  type="button"
                  className="flex items-center gap-1.5 bg-primary/10 hover:bg-primary/20 border border-primary/20 rounded-full py-0.5 px-2 transition-colors cursor-pointer"
                  title={`Assigned to ${task.assigneeName || 'Developer'}. Click to reassign.`}
                >
                  <Avatar className="size-4 shrink-0">
                    <AvatarImage src={task.assigneeAvatarUrl ?? undefined} alt={task.assigneeName ?? ""} />
                    <AvatarFallback className="text-[8px] bg-primary text-white font-medium">
                      {initials(task.assigneeName)}
                    </AvatarFallback>
                  </Avatar>
                  <span className="text-[11px] font-medium max-w-[70px] truncate text-foreground">
                    {task.assigneeName}
                  </span>
                </button>
              ) : (
                <button
                  type="button"
                  className="inline-flex items-center gap-1 rounded-full border border-dashed border-border px-2 py-0.5 text-[11px] text-muted-foreground hover:border-primary hover:text-primary hover:bg-primary/5 transition-colors cursor-pointer"
                  title="Assign developer"
                >
                  <UserPlus className="size-3" />
                  <span>Assign</span>
                </button>
              )}
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-56">
              <DropdownMenuLabel className="text-xs text-muted-foreground">Assign Developer</DropdownMenuLabel>
              {task.assigneeId && (
                <>
                  <DropdownMenuItem
                    onClick={() => onAssign?.(task.id, null)}
                    className="text-xs text-destructive flex items-center gap-2 cursor-pointer"
                  >
                    <UserX className="size-3.5" />
                    <span>Unassign</span>
                  </DropdownMenuItem>
                  <DropdownMenuSeparator />
                </>
              )}
              {members.length === 0 ? (
                <div className="px-2 py-1.5 text-xs text-muted-foreground">No members found</div>
              ) : (
                members.map((m) => {
                  const uid = m.userId || m.id || "";
                  const name = m.fullName || m.email || "Developer";
                  const isSelected = task.assigneeId === uid;
                  return (
                    <DropdownMenuItem
                      key={uid}
                      onClick={() => onAssign?.(task.id, uid)}
                      className={cn(
                        "text-xs flex items-center justify-between cursor-pointer",
                        isSelected && "bg-primary/10 font-semibold text-primary"
                      )}
                    >
                      <div className="flex items-center gap-2 min-w-0">
                        <Avatar className="size-4 shrink-0">
                          <AvatarImage src={m.avatarUrl ?? undefined} alt={name} />
                          <AvatarFallback className="text-[8px] bg-primary text-white">
                            {initials(name)}
                          </AvatarFallback>
                        </Avatar>
                        <span className="truncate">{name}</span>
                      </div>
                      {isSelected && <Check className="size-3.5 text-primary shrink-0" />}
                    </DropdownMenuItem>
                  );
                })
              )}
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </div>

      {task.blocked && task.blockedReason ? (
        <p className="mt-1.5 text-[11px] text-destructive">{task.blockedReason}</p>
      ) : null}
    </article>
  );
}

/**
 * The estimate as people say it. `480m` and `8h` are the same number and only one of
 * them is read at a glance on a card.
 */
function formatEstimate(minutes: number): string {
  if (minutes < 60) return `${minutes}m`;
  const hours = minutes / 60;
  if (hours < 8) return `${Number(hours.toFixed(1))}h`;
  const days = hours / 8;
  return `${Number(days.toFixed(1))}d`;
}
