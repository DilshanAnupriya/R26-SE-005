"use client";

import { useState } from "react";
import { AlertTriangle, GripVertical } from "lucide-react";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import type { Task, WorkflowState } from "@/lib/api/delivery-schemas";
import type { TaskBoard } from "../api";
import { cn } from "@/lib/utils";

function initials(name?: string | null) {
  if (!name) return "?";
  return name.split(/\s+/).slice(0, 2).map((part) => part[0]?.toUpperCase() ?? "").join("");
}

/**
 * The project kanban board.
 *
 * <p>Drag uses the native HTML5 API rather than a library. The interaction is one
 * element into one of four drop zones; a drag library would add a bundle and a
 * pointer-event abstraction to solve a problem this screen does not have.</p>
 *
 * <p>Every card is also reachable from the keyboard: focus its handle, press Enter to
 * pick it up, move with the arrow keys, Enter to drop, Escape to cancel. Drag and drop
 * that only works with a mouse makes the board unusable for anyone who cannot use one,
 * and a kanban board is the primary interface of this product.</p>
 */
export function KanbanBoard({
  board,
  onMove,
  disabled,
}: {
  board: TaskBoard;
  onMove: (taskId: string, state: WorkflowState) => void;
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
              {/* WIP shown as used-of-limit, not just a warning once broken: a limit you
                  can see approaching is one a team can act on. */}
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
  carried,
  disabled,
  onPickUp,
  onDropCarried,
  onMoveCarried,
  onCancel,
}: {
  task: Task;
  carried?: boolean;
  disabled?: boolean;
  onPickUp?: () => void;
  onDropCarried?: () => void;
  onMoveCarried?: (direction: -1 | 1) => void;
  onCancel?: () => void;
}) {
  const draggable = !disabled && Boolean(onPickUp);

  return (
    <article
      draggable={draggable}
      onDragStart={(event) => {
        event.dataTransfer.setData("text/plain", task.id);
        event.dataTransfer.effectAllowed = "move";
      }}
      className={cn(
        "group rounded-md border bg-background p-2.5 shadow-sm transition-shadow",
        draggable && "cursor-grab active:cursor-grabbing hover:shadow",
        carried && "ring-2 ring-primary",
        task.blocked && "border-destructive/40",
      )}
    >
      <div className="flex items-start gap-2">
        {draggable ? (
          <button
            type="button"
            // The instructions live in the accessible name so a screen reader announces
            // how to move the card at the moment it becomes possible.
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
          <p className="text-sm leading-snug">{task.title}</p>
          <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
            <span className="text-[11px] text-muted-foreground tabular-nums">{task.itemKey}</span>
            {task.blocked ? (
              <Badge variant="destructive" className="text-[10px]">
                blocked
              </Badge>
            ) : null}
            {task.estimateMinutes ? (
              <span className="text-[11px] text-muted-foreground tabular-nums">
                {Math.round(task.estimateMinutes / 60)}h
              </span>
            ) : null}
          </div>
        </div>

        {task.assigneeId ? (
          <Avatar className="size-6 shrink-0">
            <AvatarImage src={task.assigneeAvatarUrl ?? undefined} alt="" />
            <AvatarFallback className="text-[10px]">
              {initials(task.assigneeName)}
            </AvatarFallback>
          </Avatar>
        ) : null}
      </div>

      {task.blocked && task.blockedReason ? (
        <p className="mt-1.5 text-[11px] text-destructive">{task.blockedReason}</p>
      ) : null}
    </article>
  );
}
