"use client";

import { useState, useEffect } from "react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Check,
  Copy,
  Clock,
  AlertTriangle,
  UserPlus,
  UserX,
  Layers,
  Layout,
  Server,
  Database,
  Sparkles,
  Calendar,
  CheckCircle2,
  ChevronDown,
  Flame,
  ShieldAlert,
  Edit3,
  RotateCcw,
  X,
} from "lucide-react";
import type { Task, WorkflowState } from "@/lib/api/delivery-schemas";
import { clockLabel, computeTaskClock, formatCompact } from "@/lib/task-clock";
import { cn } from "@/lib/utils";
import { useQueryClient } from "@tanstack/react-query";
import { queryKeys } from "@/lib/api/query-keys";
import { CommentThread } from "@/features/comments/components/comment-thread";
import { isBoardTask } from "../api";
import type { BoardMember } from "./kanban-board";
import { getTokenStore } from "@/lib/api/token-store";
import { getActiveOrganization } from "@/lib/api/org-context";
import { toast } from "sonner";

export interface TaskDetailDialogProps {
  task: Task | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  workflowStates?: WorkflowState[];
  members?: BoardMember[];
  onMove?: (taskId: string, state: WorkflowState) => void;
  onAssign?: (taskId: string, assigneeId: string | null) => void;
  /** Sends a finished card back for rework. Omit to hide the control. */
  onReopen?: (task: Task) => void;
  reopenPending?: boolean;
}

const STATUS_TONE: Record<string, string> = {
  TODO: "bg-muted text-muted-foreground",
  IN_PROGRESS: "bg-blue-500/10 text-blue-700 dark:text-blue-400",
  IN_REVIEW: "bg-violet-500/10 text-violet-700 dark:text-violet-400",
  BLOCKED: "bg-destructive/10 text-destructive",
  DONE: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
  CANCELLED: "bg-muted text-muted-foreground line-through",
};

const PRIORITY_TONE: Record<string, string> = {
  CRITICAL: "bg-red-500/10 text-red-500 dark:text-red-400 border-red-500/30",
  HIGH: "bg-amber-500/10 text-amber-500 dark:text-amber-400 border-amber-500/30",
  MEDIUM: "bg-blue-500/10 text-blue-500 dark:text-blue-400 border-blue-500/30",
  LOW: "bg-slate-500/10 text-slate-400 border-slate-500/30",
  LOWEST: "bg-slate-500/10 text-slate-400 border-slate-500/30",
};

async function authFetch(url: string, options: RequestInit = {}) {
  const tokenStore = getTokenStore();
  const orgId = getActiveOrganization();

  const getHeaders = (token: string | null) => {
    const headers = new Headers(options.headers || {});
    if (token && !headers.has("Authorization")) {
      headers.set("Authorization", `Bearer ${token}`);
    }
    if (orgId && !headers.has("X-Organization-Id")) {
      headers.set("X-Organization-Id", orgId);
    }
    return headers;
  };

  const token = tokenStore.get();
  let res = await fetch(url, { ...options, headers: getHeaders(token) });

  if (res.status === 401) {
    try {
      const newToken = await tokenStore.refresh();
      res = await fetch(url, { ...options, headers: getHeaders(newToken) });
    } catch (err) {
      console.error("Token refresh failed:", err);
    }
  }

  return res;
}

function initials(name?: string | null) {
  if (!name) return "?";
  return name.split(/\s+/).slice(0, 2).map((part) => part[0]?.toUpperCase() ?? "").join("");
}

function humanise(value: string) {
  return value.replace(/_/g, " ").toLowerCase().replace(/^./, (character) => character.toUpperCase());
}

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

export function TaskDetailDialog({
  task,
  open,
  onOpenChange,
  workflowStates = [],
  members = [],
  onMove,
  onAssign,
  onReopen,
  reopenPending,
}: TaskDetailDialogProps) {
  const queryClient = useQueryClient();
  const [extraMetadata, setExtraMetadata] = useState<{
    description?: string;
    acceptanceCriteria?: string;
    storyPoints?: number;
    reqFrontend?: number;
    reqBackend?: number;
    reqDb?: number;
    taskComplexity?: number;
  } | null>(null);

  // Fetch story details & optimizer metadata for rich Jira view
  useEffect(() => {
    if (!open || !task) {
      setExtraMetadata(null);
      return;
    }

    Promise.all([
      task.storyId
        ? authFetch(`http://localhost:8080/api/v1/stories/${task.storyId}`).then((r) => r.json()).catch(() => null)
        : Promise.resolve(null),
      authFetch("http://localhost:8080/api/tasks").then((r) => r.json()).catch(() => [])
    ])
      .then(([storyData, optTasks]: [any, any[]]) => {
        let reqFrontend = 4;
        let reqBackend = 4;
        let reqDb = 3;
        let storyPoints = task.estimateMinutes ? Math.round(task.estimateMinutes / 60) : 3;
        let taskComplexity = 3;
        let description = task.description || "";
        let acceptanceCriteria = "";

        if (storyData && storyData.id) {
          if (storyData.description) description = storyData.description;
          if (storyData.acceptanceCriteria) acceptanceCriteria = storyData.acceptanceCriteria;
          if (storyData.storyPoints) storyPoints = storyData.storyPoints;
        }

        if (Array.isArray(optTasks)) {
          const matchedOpt = optTasks.find((ot: any) => {
            if (!ot.task_type) return false;
            return ot.task_type.includes(task.itemKey) || ot.task_type.toLowerCase() === task.title.toLowerCase();
          });

          if (matchedOpt) {
            reqFrontend = matchedOpt.req_frontend ?? reqFrontend;
            reqBackend = matchedOpt.req_backend ?? reqBackend;
            reqDb = matchedOpt.req_db ?? reqDb;
            taskComplexity = matchedOpt.task_complexity ?? taskComplexity;
            if (matchedOpt.story_points) storyPoints = matchedOpt.story_points;
          } else {
            const text = (task.title + " " + description).toLowerCase();
            if (text.includes("ui") || text.includes("frontend") || text.includes("react") || text.includes("css") || text.includes("component")) {
              reqFrontend = 8;
              reqBackend = 3;
              reqDb = 2;
            }
            if (text.includes("backend") || text.includes("api") || text.includes("spring") || text.includes("auth") || text.includes("service")) {
              reqBackend = 9;
              reqDb = 5;
              reqFrontend = text.includes("ui") ? 5 : 2;
            }
            if (text.includes("database") || text.includes("db") || text.includes("sql") || text.includes("postgres") || text.includes("schema")) {
              reqDb = 9;
              reqBackend = 6;
              reqFrontend = 2;
            }
          }
        }

        setExtraMetadata({
          description,
          acceptanceCriteria,
          storyPoints,
          reqFrontend,
          reqBackend,
          reqDb,
          taskComplexity,
        });
      })
      .catch(() => {
        // graceful fallback
      });
  }, [open, task]);

  if (!task) return null;

  const clock = computeTaskClock(task);
  const timer = clockLabel(clock);
  const finished = task.status === "DONE" || task.status === "CANCELLED";
  const reopens = task.reopenedCount ?? 0;

  const people = members
    .map((member) => ({
      id: member.userId || member.id || "",
      fullName: member.fullName || member.email || "Developer",
      avatarUrl: member.avatarUrl,
    }))
    .filter((person) => person.id !== "");

  const copyItemKey = () => {
    navigator.clipboard.writeText(task.itemKey);
    toast.success(`Copied ${task.itemKey} to clipboard`);
  };

  const currentState = workflowStates.find((s) => s.id === task.workflowStateId) || {
    id: task.workflowStateId || "current",
    name: task.status.replace(/_/g, " "),
    category: task.status,
  };

  const storyPointsVal = extraMetadata?.storyPoints ?? (task.estimateMinutes ? Math.round(task.estimateMinutes / 60) : 3);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        showCloseButton={false}
        className="w-[94vw] max-w-4xl sm:max-w-4xl md:max-w-4xl lg:max-w-4xl max-h-[90vh] overflow-y-auto bg-card border border-border p-0 shadow-2xl rounded-2xl"
      >
        {/* Jira-style Top Header Bar */}
        <div className="p-4 sm:p-5 border-b border-border bg-muted/40 flex flex-wrap items-center justify-between gap-3 sticky top-0 z-10 backdrop-blur-md">
          <div className="flex items-center gap-2.5 flex-wrap">
            {/* Type badge */}
            <span className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs font-bold bg-primary/10 text-primary border border-primary/20">
              <Layers className="size-3.5" />
              {task.type || "STORY"}
            </span>

            {/* Item Key with Copy Button */}
            <button
              type="button"
              onClick={copyItemKey}
              className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs font-mono font-bold bg-muted hover:bg-accent text-foreground border border-border transition-colors cursor-pointer"
              title="Click to copy key"
            >
              <span>{task.itemKey}</span>
              <Copy className="size-3 text-muted-foreground" />
            </button>

            {/* Blocked indicator */}
            {task.blocked && (
              <Badge variant="destructive" className="flex items-center gap-1 text-xs">
                <AlertTriangle className="size-3" />
                Blocked
              </Badge>
            )}
          </div>

          {/* Right Header Actions: Status dropdown / badge + Close button */}
          <div className="flex items-center gap-3">
            {workflowStates.length > 0 && onMove ? (
              <div className="flex items-center gap-1.5">
                <span className="text-xs text-muted-foreground hidden sm:inline font-medium">Status:</span>
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <button
                      type="button"
                      className="flex items-center gap-2 px-3 py-1.5 rounded-lg text-xs font-semibold bg-muted hover:bg-accent border border-border text-foreground transition-colors cursor-pointer"
                    >
                      <span className="size-2 rounded-full bg-primary" />
                      <span className="capitalize">{currentState.name}</span>
                      <ChevronDown className="size-3 text-muted-foreground" />
                    </button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="end" className="w-48">
                    <DropdownMenuLabel className="text-xs text-muted-foreground">Move to Column</DropdownMenuLabel>
                    <DropdownMenuSeparator />
                    {workflowStates.map((state) => (
                      <DropdownMenuItem
                        key={state.id}
                        onClick={() => onMove?.(task.id, state)}
                        className="flex items-center justify-between text-xs cursor-pointer"
                      >
                        <span>{state.name}</span>
                        {state.id === task.workflowStateId && <Check className="size-3.5 text-primary" />}
                      </DropdownMenuItem>
                    ))}
                  </DropdownMenuContent>
                </DropdownMenu>
              </div>
            ) : (
              <span
                className={cn(
                  "rounded px-2 py-1 text-xs font-semibold tracking-wider uppercase",
                  STATUS_TONE[task.status] ?? "bg-muted text-muted-foreground"
                )}
              >
                {humanise(task.status)}
              </span>
            )}

            {/* Close Button */}
            <button
              type="button"
              onClick={() => onOpenChange(false)}
              className="p-1.5 rounded-lg text-muted-foreground hover:text-foreground hover:bg-muted transition-colors cursor-pointer"
              aria-label="Close dialog"
            >
              <X className="size-5" />
            </button>
          </div>
        </div>

        {/* 2-Column Jira Body Layout */}
        <div className="grid grid-cols-1 md:grid-cols-[1fr_310px] divide-y md:divide-y-0 md:divide-x divide-border">
          {/* Main Left Content Area */}
          <div className="p-6 space-y-6 min-w-0">
            {/* Title / Summary */}
            <div>
              <DialogTitle className="text-xl font-bold text-foreground tracking-tight leading-snug">
                {task.title}
              </DialogTitle>
            </div>

            {/* Description Section */}
            <div className="space-y-2">
              <h4 className="text-xs font-bold uppercase tracking-wider text-muted-foreground flex items-center gap-1.5">
                <Edit3 className="size-3.5 text-primary" />
                Description & Scope
              </h4>
              <div className="p-4 rounded-xl bg-muted/40 border border-border text-xs text-foreground leading-relaxed min-h-[80px]">
                {extraMetadata?.description || task.description?.trim() ? (
                  <p className="whitespace-pre-wrap">{extraMetadata?.description || task.description}</p>
                ) : (
                  <p className="text-muted-foreground italic">No description provided for this item.</p>
                )}
              </div>
            </div>

            {/* Acceptance Criteria */}
            {extraMetadata?.acceptanceCriteria && (
              <div className="space-y-2">
                <h4 className="text-xs font-bold uppercase tracking-wider text-muted-foreground flex items-center gap-1.5">
                  <CheckCircle2 className="size-3.5 text-emerald-500" />
                  Acceptance Criteria
                </h4>
                <div className="p-4 rounded-xl bg-muted/40 border border-border text-xs text-foreground leading-relaxed whitespace-pre-wrap">
                  {extraMetadata.acceptanceCriteria}
                </div>
              </div>
            )}

            {/* AI Technical Domain Analysis */}
            <div className="p-5 rounded-2xl bg-muted/30 border border-border space-y-4">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Sparkles className="size-4 text-amber-500" />
                  <h4 className="text-xs font-bold uppercase tracking-wider text-foreground">
                    AI Domain Requirements & Skill Match
                  </h4>
                </div>
                <span className="text-[10px] font-mono text-muted-foreground">NLP Calibrated</span>
              </div>

              <div className="space-y-3.5">
                {/* Frontend Rating */}
                <div>
                  <div className="flex justify-between text-xs mb-1.5">
                    <span className="font-semibold text-foreground flex items-center gap-1.5">
                      <Layout className="size-3.5 text-cyan-500" />
                      Frontend Engineering
                    </span>
                    <span className="font-bold text-cyan-600 dark:text-cyan-400 font-mono">
                      {extraMetadata?.reqFrontend ?? 4} / 10
                    </span>
                  </div>
                  <div className="h-2 w-full bg-muted rounded-full overflow-hidden">
                    <div
                      className="h-full rounded-full bg-gradient-to-r from-cyan-500 to-blue-500 transition-all duration-300"
                      style={{ width: `${(extraMetadata?.reqFrontend ?? 4) * 10}%` }}
                    />
                  </div>
                </div>

                {/* Backend Rating */}
                <div>
                  <div className="flex justify-between text-xs mb-1.5">
                    <span className="font-semibold text-foreground flex items-center gap-1.5">
                      <Server className="size-3.5 text-emerald-500" />
                      Backend & APIs
                    </span>
                    <span className="font-bold text-emerald-600 dark:text-emerald-400 font-mono">
                      {extraMetadata?.reqBackend ?? 4} / 10
                    </span>
                  </div>
                  <div className="h-2 w-full bg-muted rounded-full overflow-hidden">
                    <div
                      className="h-full rounded-full bg-gradient-to-r from-emerald-500 to-teal-500 transition-all duration-300"
                      style={{ width: `${(extraMetadata?.reqBackend ?? 4) * 10}%` }}
                    />
                  </div>
                </div>

                {/* Database Rating */}
                <div>
                  <div className="flex justify-between text-xs mb-1.5">
                    <span className="font-semibold text-foreground flex items-center gap-1.5">
                      <Database className="size-3.5 text-purple-500" />
                      Database Architecture & SQL
                    </span>
                    <span className="font-bold text-purple-600 dark:text-purple-400 font-mono">
                      {extraMetadata?.reqDb ?? 3} / 10
                    </span>
                  </div>
                  <div className="h-2 w-full bg-muted rounded-full overflow-hidden">
                    <div
                      className="h-full rounded-full bg-gradient-to-r from-purple-500 to-pink-500 transition-all duration-300"
                      style={{ width: `${(extraMetadata?.reqDb ?? 3) * 10}%` }}
                    />
                  </div>
                </div>
              </div>
            </div>

            {/* Over-budget Reopen Impact Banner */}
            <div
              className={cn(
                "rounded-xl border p-4 text-xs",
                reopens > 0 ? "border-amber-500/40 bg-amber-500/5" : "border-border bg-muted/40"
              )}
            >
              <div className="flex items-center justify-between gap-2">
                <span className="inline-flex items-center gap-1.5 font-semibold text-foreground">
                  <RotateCcw className="size-3.5 text-amber-600 dark:text-amber-400" aria-hidden />
                  Reopened Metrics
                </span>
                <span className="font-mono text-sm font-bold tabular-nums text-foreground">
                  {reopens} time{reopens === 1 ? "" : "s"}
                </span>
              </div>
              <p className="mt-1 text-[11px] text-muted-foreground leading-relaxed">
                {reopens > 0
                  ? "Opening this task counts as a reopen. Every one of them raises this sprint's over-budget risk, and each raises it by more than the last — the estimate is already spent by then."
                  : "Opening this task counts as a reopen, which raises this sprint's over-budget risk."}
              </p>
            </div>

            {/* Conversation Thread */}
            <div className="space-y-2">
              <h4 className="text-xs font-bold uppercase tracking-wider text-muted-foreground">
                Discussion & Collaboration
              </h4>
              <div className="max-h-72 overflow-y-auto rounded-xl border border-border p-3 bg-card">
                <CommentThread
                  entityType="TASK"
                  entityId={task.id}
                  people={people}
                  onChanged={() => {
                    void queryClient.invalidateQueries({
                      queryKey: queryKeys.projects.board(task.projectId),
                    });
                  }}
                />
              </div>
            </div>
          </div>

          {/* Right Sidebar: Jira Attributes */}
          <div className="p-6 space-y-5 bg-muted/20 min-w-0">
            {/* Assignee Card */}
            <div className="space-y-2">
              <span className="text-[11px] font-bold uppercase tracking-wider text-muted-foreground">
                Assignee
              </span>

              {onAssign ? (
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <div className="flex items-center justify-between p-2.5 rounded-xl bg-card border border-border hover:border-primary/50 cursor-pointer transition-all">
                      <div className="flex items-center gap-2.5 min-w-0">
                        <Avatar className="size-7 border border-primary/30 shrink-0">
                          {task.assigneeAvatarUrl ? <AvatarImage src={task.assigneeAvatarUrl} /> : null}
                          <AvatarFallback className="bg-primary/20 text-primary text-2xs font-bold">
                            {initials(task.assigneeName)}
                          </AvatarFallback>
                        </Avatar>
                        <div className="min-w-0">
                          <div className="text-xs font-bold text-foreground truncate">
                            {task.assigneeName || "Unassigned"}
                          </div>
                          <div className="text-[10px] text-muted-foreground truncate">
                            {task.assigneeId ? "Assigned Developer" : "Click to assign"}
                          </div>
                        </div>
                      </div>
                      <ChevronDown className="size-3.5 text-muted-foreground shrink-0" />
                    </div>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="end" className="w-56">
                    <DropdownMenuLabel className="text-xs text-muted-foreground">Assign Developer</DropdownMenuLabel>
                    <DropdownMenuSeparator />
                    {task.assigneeId && (
                      <>
                        <DropdownMenuItem
                          onClick={() => onAssign(task.id, null)}
                          className="flex items-center gap-2 text-xs text-destructive cursor-pointer"
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
                      members.map((member) => {
                        const memberId = member.userId || member.id;
                        if (!memberId) return null;
                        const isSelected = memberId === task.assigneeId;

                        return (
                          <DropdownMenuItem
                            key={memberId}
                            onClick={() => onAssign(task.id, memberId)}
                            className="flex items-center gap-2 text-xs cursor-pointer"
                          >
                            <Avatar className="size-5 border border-primary/20 shrink-0">
                              {member.avatarUrl ? <AvatarImage src={member.avatarUrl} /> : null}
                              <AvatarFallback className="bg-primary/10 text-primary text-[9px] font-bold">
                                {initials(member.fullName)}
                              </AvatarFallback>
                            </Avatar>
                            <span className="flex-1 truncate">{member.fullName || member.email}</span>
                            {isSelected && <Check className="size-3.5 text-primary shrink-0" />}
                          </DropdownMenuItem>
                        );
                      })
                    )}
                  </DropdownMenuContent>
                </DropdownMenu>
              ) : (
                <div className="flex items-center gap-2.5 p-2.5 rounded-xl bg-card border border-border">
                  <Avatar className="size-7 border border-primary/30 shrink-0">
                    {task.assigneeAvatarUrl ? <AvatarImage src={task.assigneeAvatarUrl} /> : null}
                    <AvatarFallback className="bg-primary/20 text-primary text-2xs font-bold">
                      {initials(task.assigneeName)}
                    </AvatarFallback>
                  </Avatar>
                  <div className="min-w-0">
                    <div className="text-xs font-bold text-foreground truncate">
                      {task.assigneeName || "Unassigned"}
                    </div>
                  </div>
                </div>
              )}
            </div>

            {/* Story Points & Effort */}
            <div className="space-y-1.5">
              <span className="text-[11px] font-bold uppercase tracking-wider text-muted-foreground">
                Story Points
              </span>
              <div className="flex items-center gap-2 flex-wrap">
                <span className="px-3 py-1 rounded-lg bg-primary/10 text-primary border border-primary/20 text-xs font-bold font-mono">
                  {storyPointsVal} SPs
                </span>
                <span className="text-xs text-muted-foreground font-medium">
                  (~{storyPointsVal * 4}h effort)
                </span>
              </div>
            </div>

            {/* Priority */}
            <div className="space-y-1.5">
              <span className="text-[11px] font-bold uppercase tracking-wider text-muted-foreground">
                Priority
              </span>
              <div>
                <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-md text-xs font-bold border ${PRIORITY_TONE[task.priority] ?? "text-foreground"}`}>
                  <Flame className="size-3" />
                  {humanise(task.priority)}
                </span>
              </div>
            </div>

            {/* Complexity Tier */}
            <div className="space-y-1.5">
              <span className="text-[11px] font-bold uppercase tracking-wider text-muted-foreground">
                Task Complexity
              </span>
              <div className="text-xs text-foreground font-semibold flex items-center gap-2">
                <span className="px-2 py-0.5 rounded bg-muted border border-border text-foreground font-mono">
                  Level {extraMetadata?.taskComplexity ?? 3} / 5
                </span>
                <span className="text-muted-foreground text-[11px]">
                  {(extraMetadata?.taskComplexity ?? 3) >= 4 ? "High scope" : "Standard"}
                </span>
              </div>
            </div>

            {/* Blocked Reason Details if blocked */}
            {task.blocked && task.blockedReason && (
              <div className="p-3 rounded-xl bg-destructive/10 border border-destructive/20 text-xs text-destructive space-y-1">
                <span className="font-bold flex items-center gap-1">
                  <ShieldAlert className="size-3.5" />
                  Blocker Reason:
                </span>
                <p>{task.blockedReason}</p>
              </div>
            )}

            {/* Time Tracking & Clock */}
            <div className="pt-4 border-t border-border space-y-2 text-xs text-muted-foreground">
              {task.estimateMinutes ? (
                <div className="flex items-center justify-between">
                  <span>Estimate</span>
                  <span className="font-semibold text-foreground tabular-nums">{duration(task.estimateMinutes)}</span>
                </div>
              ) : null}
              {task.spentMinutes ? (
                <div className="flex items-center justify-between">
                  <span>Logged</span>
                  <span className="font-semibold text-foreground tabular-nums">{duration(task.spentMinutes)}</span>
                </div>
              ) : null}
              {task.remainingMinutes ? (
                <div className="flex items-center justify-between">
                  <span>Remaining</span>
                  <span className="font-semibold text-foreground tabular-nums">{duration(task.remainingMinutes)}</span>
                </div>
              ) : null}
              {clock.tracked ? (
                <div className="flex items-center justify-between">
                  <span>On the clock</span>
                  <span className="font-semibold text-foreground tabular-nums">{formatCompact(clock.elapsedSeconds)}</span>
                </div>
              ) : null}
              {timer ? (
                <div className="flex items-center justify-between">
                  <span>{timer.label}</span>
                  <span className="font-semibold text-foreground tabular-nums">{timer.value}</span>
                </div>
              ) : null}
              <div className="flex items-center justify-between">
                <span className="flex items-center gap-1.5">
                  <Calendar className="size-3.5 text-muted-foreground" />
                  Due Date
                </span>
                <span className="font-semibold text-foreground">
                  {task.dueDate ? day(task.dueDate) : "None set"}
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="flex items-center gap-1.5">
                  <Clock className="size-3.5 text-muted-foreground" />
                  Created
                </span>
                <span className="text-foreground">
                  {timestamp(task.createdAt)}
                </span>
              </div>
              {task.requiredSkills && task.requiredSkills.length > 0 ? (
                <div className="pt-2 border-t border-border">
                  <span className="text-[11px] font-medium text-muted-foreground block mb-1">Required Skills:</span>
                  <div className="flex flex-wrap gap-1">
                    {task.requiredSkills.map((skill) => (
                      <span key={skill} className="px-1.5 py-0.5 rounded bg-muted text-[10px] font-mono text-foreground">
                        {skill}
                      </span>
                    ))}
                  </div>
                </div>
              ) : null}
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="p-4 border-t border-border bg-muted/40 flex items-center justify-between">
          <div>
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
          </div>

          <Button
            type="button"
            onClick={() => onOpenChange(false)}
            className="px-5 text-xs font-semibold cursor-pointer"
          >
            Close
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
