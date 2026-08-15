"use client";

import { motion, AnimatePresence } from "framer-motion";
import {
  Bot,
  CheckCircle2,
  Clock,
  Briefcase,
  Loader2,
  RefreshCw,
  ChevronDown,
  ChevronUp,
  UserCheck,
  XCircle,
  Trophy,
  Cpu,
  Layers,
  Database,
} from "lucide-react";
import Image from "next/image";
import Link from "next/link";
import { useState, useEffect, useCallback } from "react";
import { useRouter } from "next/navigation";
import { getTokenStore } from "@/lib/api/token-store";
import { getActiveOrganization } from "@/lib/api/org-context";

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

// ─── Types ──────────────────────────────────────────────────────────────────

interface DeveloperRec {
  id: number;           // allocation id
  developerId: number;
  devId: string;
  rankPosition: number;
  finalScore: number;
  skillMatchScore: number;
  workloadBalance: number;
  mlScore: number;
  status: string;       // RECOMMENDED | ALLOCATED | DECLINED
  availability: number;
  skillFrontend: number;
  skillBackend: number;
  skillDb: number;
}

interface TaskGroup {
  taskId: string;       // "T-{id}"
  taskRawId: number;
  title: string;
  priority: string;
  complexity: string;
  overallStatus: string; // derived: Approved | Pending Approval
  topAllocationId: number;
  recommendations: DeveloperRec[];
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

function deriveTaskStatus(recs: DeveloperRec[]): string {
  if (recs.some((r) => r.status === "ALLOCATED")) return "Approved";
  return "Pending Approval";
}

function buildGroups(data: any[]): TaskGroup[] {
  const map = new Map<number, TaskGroup>();

  data.forEach((alloc: any) => {
    const taskRawId: number = alloc.task.id;

    const rec: DeveloperRec = {
      id: alloc.id,
      developerId: alloc.developer.id,
      devId: alloc.developer.dev_id,
      rankPosition: alloc.rankPosition,
      finalScore: Math.round(alloc.finalScore ?? 0),
      skillMatchScore: alloc.skillMatchScore ?? 0,
      workloadBalance: alloc.workloadBalance ?? 0,
      mlScore: alloc.mlScore ?? 0,
      status: alloc.status,
      availability: alloc.developer.availability ?? 0,
      skillFrontend: alloc.developer.skill_frontend,
      skillBackend: alloc.developer.skill_backend,
      skillDb: alloc.developer.skill_db,
    };

    if (!map.has(taskRawId)) {
      map.set(taskRawId, {
        taskId: `T-${taskRawId}`,
        taskRawId,
        title: alloc.task.task_type,
        priority: alloc.task.task_complexity > 3 ? "High" : "Medium",
        complexity: `${alloc.task.story_points} Story Points`,
        overallStatus: "Pending Approval",
        topAllocationId: alloc.id,
        recommendations: [rec],
      });
    } else {
      map.get(taskRawId)!.recommendations.push(rec);
    }
  });

  // Sort each task's recommendations by rank, keep only top-5
  map.forEach((group) => {
    group.recommendations.sort((a, b) => a.rankPosition - b.rankPosition);
    group.recommendations = group.recommendations.slice(0, 5);
    group.overallStatus = deriveTaskStatus(group.recommendations);
    // The best active recommendation
    const best = group.recommendations.find((r) => r.status === "ALLOCATED") ??
                 group.recommendations.find((r) => r.status === "RECOMMENDED");
    if (best) group.topAllocationId = best.id;
  });

  return Array.from(map.values());
}

// ─── Sub-components ──────────────────────────────────────────────────────────

function DeveloperWorkloadModal({
  developerId,
  devId,
  onClose,
}: {
  developerId: number;
  devId: string;
  onClose: () => void;
}) {
  const [tasks, setTasks] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchTasks = async () => {
      try {
        const res = await authFetch(`http://localhost:8080/api/tasks/developer/${developerId}`);
        const data = await res.json();
        setTasks(Array.isArray(data) ? data : []);
      } catch (err) {
        console.error("Error fetching dev tasks:", err);
      } finally {
        setLoading(false);
      }
    };
    fetchTasks();
  }, [developerId]);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm">
      <motion.div
        initial={{ scale: 0.95, opacity: 0 }}
        animate={{ scale: 1, opacity: 1 }}
        className="glass w-full max-w-md rounded-2xl border border-card-border p-6 shadow-2xl"
      >
        <div className="flex items-center justify-between mb-6">
          <div className="flex items-center gap-3">
            <div className="relative w-10 h-10 rounded-full overflow-hidden border border-slate-700">
              <Image
                src={`https://ui-avatars.com/api/?name=${devId}&background=random`}
                alt={devId}
                fill
                unoptimized
              />
            </div>
            <div>
              <h3 className="text-lg font-bold text-foreground">{devId}</h3>
              <p className="text-xs text-slate-400">Current Assigned Tasks</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-2 hover:bg-slate-800 rounded-full transition-colors"
          >
            <XCircle className="w-5 h-5 text-slate-400" />
          </button>
        </div>

        {loading ? (
          <div className="flex justify-center py-10">
            <Loader2 className="w-6 h-6 animate-spin text-primary" />
          </div>
        ) : tasks.length === 0 ? (
          <div className="text-center py-10 text-slate-500">
            No tasks currently assigned to this developer.
          </div>
        ) : (
          <div className="space-y-3 max-h-[400px] overflow-y-auto pr-2 custom-scrollbar">
            {tasks.map((task) => (
              <div key={task.id} className="p-3 rounded-lg bg-slate-900/50 border border-slate-800">
                <div className="flex items-center justify-between mb-1">
                  <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-slate-800 text-slate-400 border border-slate-700">
                    T-{task.id}
                  </span>
                  <span className="text-[10px] font-semibold text-emerald-400 uppercase tracking-wider">
                    {task.status || "Active"}
                  </span>
                </div>
                <div className="text-sm font-medium text-slate-200">{task.task_type}</div>
                <div className="flex items-center gap-3 mt-2 text-[10px] text-slate-500">
                  <span className="flex items-center gap-1">
                    <Clock className="w-3 h-3" />
                    {task.story_points} pts
                  </span>
                  <span className="flex items-center gap-1">
                    <Briefcase className="w-3 h-3" />
                    {task.task_complexity > 3 ? "High" : "Medium"} Complexity
                  </span>
                </div>
              </div>
            ))}
          </div>
        )}

        <button
          onClick={onClose}
          className="w-full mt-6 py-2.5 bg-slate-800 hover:bg-slate-700 text-white rounded-lg text-sm font-medium transition-colors"
        >
          Close
        </button>
      </motion.div>
    </div>
  );
}

function ScorePill({ value, label, color }: { value: number; label: string; color: string }) {
  return (
    <div className="flex flex-col items-center">
      <div className={`text-xs font-bold ${color}`}>{(value * 100).toFixed(0)}%</div>
      <div className="text-[9px] text-slate-500 uppercase tracking-wide">{label}</div>
    </div>
  );
}

function SkillBar({ label, value, max = 5 }: { label: string; value: number; max?: number }) {
  const pct = Math.round((value / max) * 100);
  return (
    <div>
      <div className="flex justify-between text-[10px] text-slate-400 mb-0.5">
        <span>{label}</span>
        <span>{value}/{max}</span>
      </div>
      <div className="h-1 bg-slate-800 rounded-full overflow-hidden">
        <div
          className="h-full rounded-full bg-gradient-to-r from-primary to-emerald-400 transition-all duration-700"
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  );
}

function DevCard({
  rec,
  selected,
  onSelect,
  isApproved,
  approvedId,
  onApprove,
  onDecline,
  onViewWorkload,
  approving,
}: {
  rec: DeveloperRec;
  selected: boolean;
  onSelect: () => void;
  isApproved: boolean;           // whole task approved
  approvedId?: number;           // which allocation was approved
  onApprove: () => void;
  onDecline: () => void;
  onViewWorkload: (id: number, devId: string) => void;
  approving: boolean;
}) {
  const isThisApproved = rec.status === "ALLOCATED";
  const isDeclined = rec.status === "DECLINED";
  const isRecommended = rec.status === "RECOMMENDED";

  const rankColors = ["text-yellow-400", "text-slate-300", "text-amber-600", "text-slate-400", "text-slate-500"];
  const rankColor = rankColors[rec.rankPosition - 1] ?? "text-slate-500";

  return (
    <motion.div
      layout
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: isDeclined ? 0.4 : 1, y: 0 }}
      className={`relative rounded-xl border p-4 cursor-pointer transition-all duration-200 ${
        isThisApproved
          ? "border-emerald-500/50 bg-emerald-500/5 shadow-lg shadow-emerald-500/10"
          : isDeclined
          ? "border-slate-800 bg-slate-900/20 cursor-not-allowed"
          : selected
          ? "border-primary/60 bg-primary/5 shadow-md shadow-primary/10"
          : "border-card-border hover:border-slate-600 bg-slate-900/30"
      }`}
      onClick={() => {
        if (!isDeclined && !isThisApproved) onSelect();
      }}
    >
      {/* Rank badge */}
      <div className={`absolute -top-2.5 -left-2.5 w-6 h-6 rounded-full bg-slate-900 border border-slate-700 flex items-center justify-center text-[10px] font-bold ${rankColor}`}>
        {rec.rankPosition === 1 ? <Trophy className="w-3 h-3" /> : `#${rec.rankPosition}`}
      </div>

      {/* Status badge */}
      {isThisApproved && (
        <div className="absolute -top-2.5 right-2 px-2 py-0.5 rounded-full bg-emerald-500/20 border border-emerald-500/40 text-[10px] text-emerald-400 font-semibold">
          Approved
        </div>
      )}
      {isDeclined && (
        <div className="absolute -top-2.5 right-2 px-2 py-0.5 rounded-full bg-red-500/20 border border-red-500/40 text-[10px] text-red-400 font-semibold">
          Declined
        </div>
      )}

      {/* Dev info */}
      <div className="flex items-center gap-3 mb-3">
        <div 
          className="relative w-9 h-9 rounded-full overflow-hidden border border-slate-700 shrink-0 hover:ring-2 ring-primary/40 transition-all"
          onClick={(e) => { e.stopPropagation(); onViewWorkload(rec.developerId, rec.devId); }}
          title="Click to view developer workload"
        >
          <Image
            src={`https://ui-avatars.com/api/?name=${rec.devId}&background=random`}
            alt={`Developer ${rec.devId}`}
            fill
            unoptimized
            className="object-cover"
          />
        </div>
        <div className="min-w-0 flex-1" onClick={(e) => { e.stopPropagation(); onViewWorkload(rec.developerId, rec.devId); }}>
          <div className="text-sm font-semibold text-foreground truncate hover:text-primary transition-colors">{rec.devId}</div>
          <div className="text-xs text-slate-400">
            Avail: {rec.availability.toFixed(0)}%
          </div>
        </div>
        <div className="ml-auto text-right shrink-0">
          <div className="text-xl font-bold text-emerald-400">{rec.finalScore}%</div>
          <div className="text-[9px] text-slate-500 uppercase font-semibold">Match</div>
        </div>
      </div>

      {/* Score pills */}
      <div className="flex justify-between px-1 mb-3">
        <ScorePill value={rec.skillMatchScore} label="Skill" color="text-blue-400" />
        <ScorePill value={rec.workloadBalance} label="Load" color="text-purple-400" />
        <div className="flex flex-col items-center">
          <div className="text-xs font-bold text-amber-400">{rec.mlScore.toFixed(0)}%</div>
          <div className="text-[9px] text-slate-500 uppercase tracking-wide">ML Pred</div>
        </div>
      </div>

      {/* Skill bars */}
      <div className="space-y-1.5 mb-4">
        <SkillBar label="Frontend" value={rec.skillFrontend} />
        <SkillBar label="Backend" value={rec.skillBackend} />
        <SkillBar label="Database" value={rec.skillDb} />
      </div>

      {/* Action buttons — only shown when card is selected and task not yet approved */}
      <AnimatePresence>
        {selected && isRecommended && !isApproved && (
          <motion.div
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: "auto" }}
            exit={{ opacity: 0, height: 0 }}
            className="flex gap-2 overflow-hidden"
          >
            <button
              onClick={(e) => { e.stopPropagation(); onApprove(); }}
              disabled={approving}
              className="flex-1 flex items-center justify-center gap-1.5 px-3 py-2 bg-emerald-500/20 hover:bg-emerald-500/30 text-emerald-400 rounded-lg text-xs font-semibold border border-emerald-500/30 transition-colors disabled:opacity-50"
            >
              {approving ? <Loader2 className="w-3 h-3 animate-spin" /> : <UserCheck className="w-3 h-3" />}
              Approve
            </button>
            <button
              onClick={(e) => { e.stopPropagation(); onDecline(); }}
              disabled={approving}
              className="flex-1 flex items-center justify-center gap-1.5 px-3 py-2 bg-red-500/10 hover:bg-red-500/20 text-red-400 rounded-lg text-xs font-semibold border border-red-500/20 transition-colors disabled:opacity-50"
            >
              <XCircle className="w-3 h-3" />
              Decline
            </button>
          </motion.div>
        )}
      </AnimatePresence>
    </motion.div>
  );
}

// ─── Task Row ─────────────────────────────────────────────────────────────────

function TaskRow({
  group,
  onApprove,
  onDecline,
  onViewWorkload,
}: {
  group: TaskGroup;
  onApprove: (allocationId: number, developerId: number) => Promise<void>;
  onDecline: (allocationId: number) => Promise<void>;
  onViewWorkload: (id: number, devId: string) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const [selectedAllocId, setSelectedAllocId] = useState<number | null>(null);
  const [approving, setApproving] = useState(false);

  const isApproved = group.overallStatus === "Approved";
  const approvedRec = group.recommendations.find((r) => r.status === "ALLOCATED");

  const handleApprove = async (rec: DeveloperRec) => {
    setApproving(true);
    await onApprove(rec.id, rec.developerId);
    setApproving(false);
    setSelectedAllocId(null);
  };

  const handleDecline = async (rec: DeveloperRec) => {
    setApproving(true);
    await onDecline(rec.id);
    setApproving(false);
    setSelectedAllocId(null);
  };

  return (
    <motion.div
      layout
      className={`glass rounded-xl border transition-all ${
        isApproved ? "border-emerald-500/20" : "border-card-border hover:border-primary/30"
      }`}
    >
      {/* Task header — click to expand */}
      <div
        className="flex flex-col lg:flex-row items-start lg:items-center gap-4 p-5 cursor-pointer select-none"
        onClick={() => setExpanded((v) => !v)}
      >
        {/* Task meta */}
        <div className="flex-1 min-w-0">
          <div className="flex flex-wrap items-center gap-2 mb-1.5">
            <span className="text-xs font-mono px-2 py-0.5 rounded bg-slate-800 text-slate-300 border border-slate-700">
              {group.taskId}
            </span>
            <span
              className={`text-xs px-2 py-0.5 rounded-full font-medium ${
                group.priority === "High"
                  ? "bg-orange-500/10 text-orange-400 border border-orange-500/20"
                  : "bg-blue-500/10 text-blue-400 border border-blue-500/20"
              }`}
            >
              {group.priority} Priority
            </span>
            <span className="flex items-center text-xs text-slate-400">
              <Clock className="w-3 h-3 mr-1" />
              {group.complexity}
            </span>
          </div>
          <h3 className="text-lg font-semibold text-foreground">{group.title}</h3>
        </div>

        {/* Right section */}
        <div className="flex items-center gap-4 shrink-0">
          {isApproved && approvedRec ? (
            <div className="flex items-center gap-2">
              <div className="relative w-8 h-8 rounded-full overflow-hidden border border-emerald-500/40">
                <Image
                  src={`https://ui-avatars.com/api/?name=${approvedRec.devId}&background=random`}
                  alt={`Developer ${approvedRec.devId}`}
                  fill
                  unoptimized
                  className="object-cover"
                />
              </div>
              <div>
                <div className="text-sm font-medium text-foreground">Dev {approvedRec.devId}</div>
                <div className="text-xs text-emerald-400">{approvedRec.finalScore}% match</div>
              </div>
            </div>
          ) : (
            <div className="text-sm text-slate-400 flex items-center gap-1.5">
              <Bot className="w-4 h-4 text-primary" />
              {group.recommendations.filter((r) => r.status === "RECOMMENDED").length} candidates
            </div>
          )}

          {/* Status badge */}
          <div
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold ${
              isApproved
                ? "bg-emerald-500/10 text-emerald-400 border border-emerald-500/20"
                : "bg-amber-500/10 text-amber-400 border border-amber-500/20"
            }`}
          >
            {isApproved ? <CheckCircle2 className="w-3.5 h-3.5" /> : <Briefcase className="w-3.5 h-3.5" />}
            {isApproved ? "Approved" : "Pending"}
          </div>

          {/* Expand toggle */}
          <div className="text-slate-500">
            {expanded ? <ChevronUp className="w-5 h-5" /> : <ChevronDown className="w-5 h-5" />}
          </div>
        </div>
      </div>

      {/* Expanded developer cards */}
      <AnimatePresence initial={false}>
        {expanded && (
          <motion.div
            key="panel"
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: "auto" }}
            exit={{ opacity: 0, height: 0 }}
            transition={{ duration: 0.25 }}
            className="overflow-hidden"
          >
            <div className="px-5 pb-5 border-t border-slate-800/60 pt-4">
              <div className="flex items-center justify-between mb-4">
                <p className="text-sm text-slate-400">
                  {isApproved
                    ? "Task assigned. You can view all candidates below."
                    : "Select a developer and approve or decline."}
                </p>
                <Link
                  href={`/ai-allocation/${group.topAllocationId}/explanation`}
                  className="text-xs text-primary hover:underline flex items-center gap-1"
                  onClick={(e) => e.stopPropagation()}
                >
                  View full explanation →
                </Link>
              </div>

              {/* Top-5 developer grid */}
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5 gap-4">
                {group.recommendations.map((rec) => (
                  <DevCard
                    key={rec.id}
                    rec={rec}
                    selected={selectedAllocId === rec.id}
                    onSelect={() => setSelectedAllocId(rec.id === selectedAllocId ? null : rec.id)}
                    isApproved={isApproved}
                    approvedId={approvedRec?.id}
                    onApprove={() => handleApprove(rec)}
                    onDecline={() => handleDecline(rec)}
                    onViewWorkload={onViewWorkload}
                    approving={approving}
                  />
                ))}
              </div>

              {!isApproved && selectedAllocId === null && (
                <p className="text-center text-xs text-slate-600 mt-4">
                  Click a developer card to select it, then approve or decline.
                </p>
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </motion.div>
  );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function AIAllocationPage({ isEmbedded = false }: { isEmbedded?: boolean }) {
  const router = useRouter();

  useEffect(() => {
    if (!isEmbedded) {
      router.replace("/allocation?tab=allocation");
    }
  }, [isEmbedded, router]);

  const [taskGroups, setTaskGroups] = useState<TaskGroup[]>([]);
  const [sprints, setSprints] = useState<any[]>([]);
  const [selectedSprintId, setSelectedSprintId] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [generating, setGenerating] = useState(false);
  const [approvingAll, setApprovingAll] = useState(false);
  const [workloadDev, setWorkloadDev] = useState<{ id: number; devId: string } | null>(null);

  const fetchSprints = useCallback(async () => {
    try {
      const res = await authFetch("http://localhost:8080/api/sprints");
      const data = await res.json();
      if (Array.isArray(data)) {
        setSprints(data);
        if (data.length > 0) {
          if (!selectedSprintId) {
            setSelectedSprintId(data[0].id);
          }
        } else {
          setSelectedSprintId(null);
          setLoading(false);
        }
      } else {
        console.error("Invalid sprints response:", data);
        setSprints([]);
        setLoading(false);
      }
    } catch (err) {
      console.error("Error fetching sprints:", err);
      setLoading(false);
    }
  }, [selectedSprintId]);


  const fetchAllocations = useCallback(async () => {
    if (!selectedSprintId) return;
    setLoading(true);
    try {
      const res = await authFetch(`http://localhost:8080/api/allocation/sprint/${selectedSprintId}`);
      const data = await res.json();
      setTaskGroups(buildGroups(Array.isArray(data) ? data : []));
    } catch (error) {
      console.error("Error fetching allocations:", error);
    } finally {
      setLoading(false);
    }
  }, [selectedSprintId]);

  useEffect(() => {
    fetchSprints();
  }, [fetchSprints]);

  useEffect(() => {
    if (selectedSprintId) {
      fetchAllocations();
    }
  }, [selectedSprintId, fetchAllocations]);

  const generateRecommendations = async () => {
    if (!selectedSprintId) {
      console.warn("No sprint selected to generate recommendations for.");
      return;
    }
    setGenerating(true);
    try {
      console.log(`Generating recommendations for sprint ID: ${selectedSprintId}...`);
      const res = await authFetch(`http://localhost:8080/api/rank/recommendations/sprint/${selectedSprintId}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
      });
      if (!res.ok) {
        const errorData = await res.text();
        console.error("Backend error generating recommendations:", res.status, errorData);
      } else {
        const data = await res.json();
        console.log("Recommendations received from ML:", data);
      }
      await fetchAllocations();
    } catch (error) {
      console.error("Error generating recommendations:", error);
    } finally {
      setGenerating(false);
    }
  };

  const handleApprove = async (allocationId: number, developerId: number) => {
    try {
      await authFetch(
        `http://localhost:8080/api/allocation/assign?allocationId=${allocationId}&developerId=${developerId}`,
        { method: "PUT" }
      );
      await fetchAllocations();
    } catch (error) {
      console.error("Error approving allocation:", error);
    }
  };

  const handleDecline = async (allocationId: number) => {
    try {
      await authFetch(`http://localhost:8080/api/allocation/${allocationId}/decline`, {
        method: "PUT",
      });
      await fetchAllocations();
    } catch (error) {
      console.error("Error declining allocation:", error);
    }
  };

  const handleApproveAll = async () => {
    if (!selectedSprintId) return;
    setApprovingAll(true);
    try {
      await authFetch(`http://localhost:8080/api/allocation/approve-all/sprint/${selectedSprintId}`, {
        method: "POST",
      });
      await fetchAllocations();
    } catch (error) {
      console.error("Error approving all recommendations:", error);
    } finally {
      setApprovingAll(false);
    }
  };

  const pendingCount = taskGroups.filter((g) => g.overallStatus !== "Approved").length;
  const approvedCount = taskGroups.filter((g) => g.overallStatus === "Approved").length;

  if (!isEmbedded) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-background">
        <Loader2 className="w-10 h-10 animate-spin text-primary" />
      </div>
    );
  }

  return (
    <div className={isEmbedded ? "w-full space-y-8" : "min-h-screen bg-background p-6 lg:p-10"}>
      <div className={isEmbedded ? "w-full space-y-8" : "max-w-7xl mx-auto space-y-8"}>

        {/* ── Header ── */}
        <div className={isEmbedded ? "flex flex-col md:flex-row md:items-center justify-between gap-6" : "flex flex-col md:flex-row md:items-start justify-between gap-6"}>
          {!isEmbedded && (
            <div>
              <h1 className="text-3xl font-bold text-foreground flex items-center gap-3">
                <Bot className="w-8 h-8 text-primary" />
                AI Task Allocation
              </h1>
              <p className="text-slate-400 mt-2 max-w-2xl leading-relaxed">
                Review AI-ranked developer recommendations for each task. Expand any task to see the top&nbsp;5 candidates — select one to approve or decline.
              </p>
            </div>
          )}

          <div className={isEmbedded ? "flex items-center gap-3 ml-auto shrink-0" : "flex items-center gap-3 shrink-0"}>
            {/* Sprint Selector */}
            <div className="flex items-center gap-2 bg-slate-900 border border-slate-700 rounded-lg px-3 py-2">

              <Layers className="w-4 h-4 text-slate-400" />
              <select
                value={selectedSprintId || ""}
                onChange={(e) => setSelectedSprintId(Number(e.target.value))}
                className="bg-transparent text-sm font-medium text-slate-200 outline-none cursor-pointer"
              >
                {sprints.map((s) => (
                  <option key={s.id} value={s.id} className="bg-slate-900">
                    Sprint: {s.sprintId}
                  </option>
                ))}
              </select>
            </div>

            <button
              onClick={generateRecommendations}
              disabled={generating || !selectedSprintId}
              className="px-5 py-2.5 bg-slate-800 hover:bg-slate-700 text-white rounded-lg text-sm font-medium transition-colors border border-slate-700 flex items-center gap-2 disabled:opacity-50"
            >
              {generating ? <Loader2 className="w-4 h-4 animate-spin" /> : <RefreshCw className="w-4 h-4" />}
              Generate Recommendations
            </button>

            {pendingCount > 0 && (
              <button
                onClick={handleApproveAll}
                disabled={approvingAll || !selectedSprintId}
                className="px-5 py-2.5 bg-emerald-500/10 hover:bg-emerald-500/20 text-emerald-400 rounded-lg text-sm font-semibold transition-colors border border-emerald-500/30 flex items-center gap-2 disabled:opacity-50"
              >
                {approvingAll ? <Loader2 className="w-4 h-4 animate-spin" /> : <CheckCircle2 className="w-4 h-4" />}
                Approve All
              </button>
            )}
          </div>
        </div>

        {/* ── Stats Bar ── */}
        {!loading && taskGroups.length > 0 && (
          <div className="grid grid-cols-3 gap-4">
            {[
              { label: "Total Tasks", value: taskGroups.length, color: "text-foreground" },
              { label: "Approved", value: approvedCount, color: "text-emerald-400" },
              { label: "Pending", value: pendingCount, color: "text-amber-400" },
            ].map((stat) => (
              <div
                key={stat.label}
                className="glass rounded-xl p-4 border border-card-border text-center"
              >
                <div className={`text-3xl font-bold ${stat.color}`}>{stat.value}</div>
                <div className="text-xs text-slate-400 mt-1 uppercase font-semibold tracking-wide">
                  {stat.label}
                </div>
              </div>
            ))}
          </div>
        )}

        {/* ── Task List ── */}
        {loading ? (
          <div className="flex items-center justify-center py-24">
            <Loader2 className="w-9 h-9 animate-spin text-primary" />
          </div>
        ) : sprints.length === 0 ? (
          <div className="text-center py-20 border border-dashed border-card-border rounded-2xl bg-slate-950/30 p-8">
            <Bot className="w-14 h-14 mx-auto text-primary/60 mb-4" />
            <h3 className="text-xl font-semibold text-foreground">No active sprints found</h3>
            <p className="text-sm mt-2 text-slate-400 max-w-md mx-auto">
              Create a sprint in Backlog & Sprints and register team developers to run AI task allocation.
            </p>
            <div className="mt-6 flex justify-center gap-4">
              <Link
                href="/register-dev"
                className="px-5 py-2.5 bg-primary hover:bg-primary-dark text-white rounded-xl text-sm font-semibold transition-all shadow-lg shadow-primary/20"
              >
                Register Developer
              </Link>
            </div>
          </div>
        ) : taskGroups.length === 0 ? (
          <div className="text-center py-24 text-slate-400 border border-dashed border-slate-800 rounded-xl">
            <Bot className="w-14 h-14 mx-auto text-slate-600 mb-4" />
            <p className="text-lg">No AI recommendations found for this sprint.</p>
            <p className="text-sm mt-2 text-slate-500">
              Click &apos;Generate Recommendations&apos; to analyse the sprint backlog.
            </p>
          </div>
        ) : (

          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            className="space-y-4"
          >
            {taskGroups.map((group) => (
              <TaskRow
                key={group.taskRawId}
                group={group}
                onApprove={handleApprove}
                onDecline={handleDecline}
                onViewWorkload={(id, devId) => setWorkloadDev({ id, devId })}
              />
            ))}
          </motion.div>
        )}
      </div>

      {/* Developer Workload Modal */}
      <AnimatePresence>
        {workloadDev && (
          <DeveloperWorkloadModal
            developerId={workloadDev.id}
            devId={workloadDev.devId}
            onClose={() => setWorkloadDev(null)}
          />
        )}
      </AnimatePresence>
    </div>
  );
}
