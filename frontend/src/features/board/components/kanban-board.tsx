"use client";

import { useState } from "react";
import { AlertTriangle, Check, GripVertical, UserPlus, UserX } from "lucide-react";
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
import type { TaskBoard } from "../api";
import { cn } from "@/lib/utils";

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
  members = [],
  disabled,
}: {
  board: TaskBoard;
  onMove: (taskId: string, state: WorkflowState) => void;
  onAssign?: (taskId: string, assigneeId: string | null) => void;
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
            <span className="text-[11px] text-muted-foreground tabular-nums font-mono">{task.itemKey}</span>
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
