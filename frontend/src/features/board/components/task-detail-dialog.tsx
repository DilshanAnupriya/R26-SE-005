"use client";

import { useState, useEffect } from "react";
import { 
  Dialog, 
  DialogContent, 
  DialogHeader, 
  DialogTitle 
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
  DropdownMenuTrigger 
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
  X
} from "lucide-react";
import type { Task, WorkflowState } from "@/lib/api/delivery-schemas";
import type { BoardMember } from "./kanban-board";
import { getTokenStore } from "@/lib/api/token-store";
import { getActiveOrganization } from "@/lib/api/org-context";
import { toast } from "sonner";

interface TaskDetailDialogProps {
  task: Task | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  workflowStates?: WorkflowState[];
  members?: BoardMember[];
  onMove?: (taskId: string, state: WorkflowState) => void;
  onAssign?: (taskId: string, assigneeId: string | null) => void;
}

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

export function TaskDetailDialog({
  task,
  open,
  onOpenChange,
  workflowStates = [],
  members = [],
  onMove,
  onAssign,
}: TaskDetailDialogProps) {
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
      // 1. Fetch story details if storyId exists
      task.storyId
        ? authFetch(`http://localhost:8080/api/v1/stories/${task.storyId}`).then((r) => r.json()).catch(() => null)
        : Promise.resolve(null),
      // 2. Fetch optimizer tasks to get AI domain requirements
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
            // Fallback NLP heuristic from title & description
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

  const copyItemKey = () => {
    navigator.clipboard.writeText(task.itemKey);
    toast.success(`Copied ${task.itemKey} to clipboard`);
  };

  const currentState = workflowStates.find((s) => s.id === task.workflowStateId) || {
    id: task.workflowStateId || "current",
    name: task.status.replace("_", " "),
    category: task.status,
  };

  const getPriorityColor = (priority: string) => {
    switch (priority.toUpperCase()) {
      case "CRITICAL": return "bg-red-500/10 text-red-400 border-red-500/30";
      case "HIGH": return "bg-amber-500/10 text-amber-400 border-amber-500/30";
      case "MEDIUM": return "bg-blue-500/10 text-blue-400 border-blue-500/30";
      default: return "bg-slate-500/10 text-slate-400 border-slate-500/30";
    }
  };

  const storyPointsVal = extraMetadata?.storyPoints ?? (task.estimateMinutes ? Math.round(task.estimateMinutes / 60) : 3);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent 
        showCloseButton={false}
        className="w-[94vw] max-w-4xl sm:max-w-4xl md:max-w-4xl lg:max-w-4xl max-h-[90vh] overflow-y-auto bg-slate-950 border border-slate-800 p-0 shadow-2xl rounded-2xl"
      >
        {/* Jira-style Top Header Bar */}
        <div className="p-4 sm:p-5 border-b border-slate-800 bg-slate-900/70 flex flex-wrap items-center justify-between gap-3 sticky top-0 z-10 backdrop-blur-md">
          <div className="flex items-center gap-2.5 flex-wrap">
            {/* Type badge */}
            <span className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs font-bold bg-primary/10 text-primary border border-primary/20">
              <Layers className="size-3.5" />
              {task.type || "STORY"}
            </span>

            {/* Item Key with Copy Button */}
            <button
              onClick={copyItemKey}
              className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs font-mono font-bold bg-slate-800 hover:bg-slate-700 text-slate-200 border border-slate-700 transition-colors cursor-pointer"
              title="Click to copy key"
            >
              <span>{task.itemKey}</span>
              <Copy className="size-3 text-slate-400" />
            </button>

            {/* Blocked indicator */}
            {task.blocked && (
              <Badge variant="destructive" className="flex items-center gap-1 text-xs">
                <AlertTriangle className="size-3" />
                Blocked
              </Badge>
            )}
          </div>

          {/* Right Header Actions: Status dropdown + Close button */}
          <div className="flex items-center gap-3">
            <div className="flex items-center gap-1.5">
              <span className="text-xs text-slate-400 hidden sm:inline font-medium">Status:</span>
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <button
                    type="button"
                    className="flex items-center gap-2 px-3 py-1.5 rounded-lg text-xs font-bold bg-slate-800 hover:bg-slate-700 border border-slate-700 text-white transition-colors cursor-pointer"
                  >
                    <span className="size-2 rounded-full bg-primary" />
                    <span className="capitalize">{currentState.name}</span>
                    <ChevronDown className="size-3 text-slate-400" />
                  </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" className="w-48 bg-slate-900 border-slate-800">
                  <DropdownMenuLabel className="text-xs text-slate-400">Move to Column</DropdownMenuLabel>
                  <DropdownMenuSeparator className="bg-slate-800" />
                  {workflowStates.map((state) => (
                    <DropdownMenuItem
                      key={state.id}
                      onClick={() => onMove?.(task.id, state)}
                      className="flex items-center justify-between text-xs cursor-pointer hover:bg-slate-800"
                    >
                      <span>{state.name}</span>
                      {state.id === task.workflowStateId && <Check className="size-3.5 text-primary" />}
                    </DropdownMenuItem>
                  ))}
                </DropdownMenuContent>
              </DropdownMenu>
            </div>

            {/* Close Button */}
            <button
              onClick={() => onOpenChange(false)}
              className="p-1.5 rounded-lg text-slate-400 hover:text-white hover:bg-slate-800 transition-colors"
              aria-label="Close dialog"
            >
              <X className="size-5" />
            </button>
          </div>
        </div>

        {/* 2-Column Jira Body Layout */}
        <div className="grid grid-cols-1 md:grid-cols-[1fr_310px] divide-y md:divide-y-0 md:divide-x divide-slate-800">
          {/* Main Left Content Area */}
          <div className="p-6 space-y-6 min-w-0">
            {/* Title / Summary */}
            <div>
              <DialogTitle className="text-xl font-bold text-white tracking-tight leading-snug">
                {task.title}
              </DialogTitle>
            </div>

            {/* Description Section */}
            <div className="space-y-2">
              <h4 className="text-xs font-bold uppercase tracking-wider text-slate-400 flex items-center gap-1.5">
                <Edit3 className="size-3.5 text-primary" />
                Description & Scope
              </h4>
              <div className="p-4 rounded-xl bg-slate-900/60 border border-slate-800/80 text-xs text-slate-300 leading-relaxed min-h-[80px]">
                {extraMetadata?.description ? (
                  <p className="whitespace-pre-wrap">{extraMetadata.description}</p>
                ) : (
                  <p className="text-slate-500 italic">No description provided for this item.</p>
                )}
              </div>
            </div>

            {/* Acceptance Criteria */}
            {extraMetadata?.acceptanceCriteria && (
              <div className="space-y-2">
                <h4 className="text-xs font-bold uppercase tracking-wider text-slate-400 flex items-center gap-1.5">
                  <CheckCircle2 className="size-3.5 text-emerald-400" />
                  Acceptance Criteria
                </h4>
                <div className="p-4 rounded-xl bg-slate-900/60 border border-slate-800/80 text-xs text-slate-300 leading-relaxed whitespace-pre-wrap">
                  {extraMetadata.acceptanceCriteria}
                </div>
              </div>
            )}

            {/* AI Technical Domain Analysis (ML Telemetry) */}
            <div className="p-5 rounded-2xl bg-slate-900/60 border border-slate-800 space-y-4">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Sparkles className="size-4 text-amber-400" />
                  <h4 className="text-xs font-bold uppercase tracking-wider text-white">
                    AI Domain Requirements & Skill Match
                  </h4>
                </div>
                <span className="text-[10px] font-mono text-slate-500">NLP Calibrated</span>
              </div>

              <div className="space-y-3.5">
                {/* Frontend Rating */}
                <div>
                  <div className="flex justify-between text-xs mb-1.5">
                    <span className="font-semibold text-slate-300 flex items-center gap-1.5">
                      <Layout className="size-3.5 text-cyan-400" />
                      Frontend Engineering
                    </span>
                    <span className="font-bold text-cyan-400 font-mono">
                      {extraMetadata?.reqFrontend ?? 4} / 10
                    </span>
                  </div>
                  <div className="h-2 w-full bg-slate-800 rounded-full overflow-hidden">
                    <div 
                      className="h-full rounded-full bg-gradient-to-r from-cyan-500 to-blue-500 transition-all duration-300" 
                      style={{ width: `${(extraMetadata?.reqFrontend ?? 4) * 10}%` }}
                    />
                  </div>
                </div>

                {/* Backend Rating */}
                <div>
                  <div className="flex justify-between text-xs mb-1.5">
                    <span className="font-semibold text-slate-300 flex items-center gap-1.5">
                      <Server className="size-3.5 text-emerald-400" />
                      Backend & APIs
                    </span>
                    <span className="font-bold text-emerald-400 font-mono">
                      {extraMetadata?.reqBackend ?? 4} / 10
                    </span>
                  </div>
                  <div className="h-2 w-full bg-slate-800 rounded-full overflow-hidden">
                    <div 
                      className="h-full rounded-full bg-gradient-to-r from-emerald-500 to-teal-500 transition-all duration-300" 
                      style={{ width: `${(extraMetadata?.reqBackend ?? 4) * 10}%` }}
                    />
                  </div>
                </div>

                {/* Database Rating */}
                <div>
                  <div className="flex justify-between text-xs mb-1.5">
                    <span className="font-semibold text-slate-300 flex items-center gap-1.5">
                      <Database className="size-3.5 text-purple-400" />
                      Database Architecture & SQL
                    </span>
                    <span className="font-bold text-purple-400 font-mono">
                      {extraMetadata?.reqDb ?? 3} / 10
                    </span>
                  </div>
                  <div className="h-2 w-full bg-slate-800 rounded-full overflow-hidden">
                    <div 
                      className="h-full rounded-full bg-gradient-to-r from-purple-500 to-pink-500 transition-all duration-300" 
                      style={{ width: `${(extraMetadata?.reqDb ?? 3) * 10}%` }}
                    />
                  </div>
                </div>
              </div>
            </div>
          </div>

          {/* Right Sidebar: Jira Attributes */}
          <div className="p-6 space-y-5 bg-slate-900/40 min-w-0">
            {/* Assignee Card */}
            <div className="space-y-2">
              <span className="text-[11px] font-bold uppercase tracking-wider text-slate-400">
                Assignee
              </span>
              
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <div className="flex items-center justify-between p-2.5 rounded-xl bg-slate-900 border border-slate-800 hover:border-slate-700 cursor-pointer transition-all">
                    <div className="flex items-center gap-2.5 min-w-0">
                      <Avatar className="size-7 border border-primary/30 shrink-0">
                        {task.assigneeAvatarUrl ? <AvatarImage src={task.assigneeAvatarUrl} /> : null}
                        <AvatarFallback className="bg-primary/20 text-primary text-2xs font-bold">
                          {initials(task.assigneeName)}
                        </AvatarFallback>
                      </Avatar>
                      <div className="min-w-0">
                        <div className="text-xs font-bold text-white truncate">
                          {task.assigneeName || "Unassigned"}
                        </div>
                        <div className="text-[10px] text-slate-400 truncate">
                          {task.assigneeId ? "Assigned Developer" : "Click to assign"}
                        </div>
                      </div>
                    </div>
                    <ChevronDown className="size-3.5 text-slate-400 shrink-0" />
                  </div>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" className="w-56 bg-slate-900 border-slate-800">
                  <DropdownMenuLabel className="text-xs text-slate-400">Assign Developer</DropdownMenuLabel>
                  <DropdownMenuSeparator className="bg-slate-800" />
                  {task.assigneeId && (
                    <>
                      <DropdownMenuItem 
                        onClick={() => onAssign?.(task.id, null)} 
                        className="flex items-center gap-2 text-xs text-destructive hover:bg-slate-800 cursor-pointer"
                      >
                        <UserX className="size-3.5" />
                        <span>Unassign</span>
                      </DropdownMenuItem>
                      <DropdownMenuSeparator className="bg-slate-800" />
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
                          onClick={() => onAssign?.(task.id, memberId)}
                          className="flex items-center gap-2 text-xs hover:bg-slate-800 cursor-pointer"
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
            </div>

            {/* Story Points & Effort */}
            <div className="space-y-1.5">
              <span className="text-[11px] font-bold uppercase tracking-wider text-slate-400">
                Story Points
              </span>
              <div className="flex items-center gap-2 flex-wrap">
                <span className="px-3 py-1 rounded-lg bg-primary/10 text-primary border border-primary/20 text-xs font-bold font-mono">
                  {storyPointsVal} SPs
                </span>
                <span className="text-xs text-slate-400 font-medium">
                  (~{storyPointsVal * 4}h effort)
                </span>
              </div>
            </div>

            {/* Priority */}
            <div className="space-y-1.5">
              <span className="text-[11px] font-bold uppercase tracking-wider text-slate-400">
                Priority
              </span>
              <div>
                <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-md text-xs font-bold border ${getPriorityColor(task.priority)}`}>
                  <Flame className="size-3" />
                  {task.priority}
                </span>
              </div>
            </div>

            {/* Complexity Tier */}
            <div className="space-y-1.5">
              <span className="text-[11px] font-bold uppercase tracking-wider text-slate-400">
                Task Complexity
              </span>
              <div className="text-xs text-slate-300 font-semibold flex items-center gap-2">
                <span className="px-2 py-0.5 rounded bg-slate-800 border border-slate-700 text-white font-mono">
                  Level {extraMetadata?.taskComplexity ?? 3} / 5
                </span>
                <span className="text-slate-400 text-[11px]">
                  {(extraMetadata?.taskComplexity ?? 3) >= 4 ? "High scope" : "Standard"}
                </span>
              </div>
            </div>

            {/* Blocked Reason Details if blocked */}
            {task.blocked && task.blockedReason && (
              <div className="p-3 rounded-xl bg-red-500/10 border border-red-500/20 text-xs text-red-300 space-y-1">
                <span className="font-bold flex items-center gap-1 text-red-400">
                  <ShieldAlert className="size-3.5" />
                  Blocker Reason:
                </span>
                <p>{task.blockedReason}</p>
              </div>
            )}

            {/* Dates & Time Tracking */}
            <div className="pt-4 border-t border-slate-800 space-y-2 text-xs text-slate-400">
              <div className="flex items-center justify-between">
                <span className="flex items-center gap-1.5">
                  <Calendar className="size-3.5 text-slate-500" />
                  Due Date
                </span>
                <span className="font-semibold text-slate-200">
                  {task.dueDate ? new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", year: "numeric" }).format(new Date(task.dueDate)) : "None set"}
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="flex items-center gap-1.5">
                  <Clock className="size-3.5 text-slate-500" />
                  Created
                </span>
                <span className="text-slate-300">
                  {new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short" }).format(new Date(task.createdAt))}
                </span>
              </div>
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="p-4 border-t border-slate-800 bg-slate-900/70 flex items-center justify-end">
          <Button
            type="button"
            onClick={() => onOpenChange(false)}
            className="px-5 bg-slate-800 hover:bg-slate-700 text-white text-xs font-bold cursor-pointer"
          >
            Close
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
