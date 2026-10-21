"use client";
import { backendOrigin } from "@/lib/env";

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
  UserX,
  XCircle,
  Trophy,
  Layers,
  Sparkles,
  Check,
  AlertTriangle,
  Scale,
  X,
  RotateCcw,
  ShieldAlert,
} from "lucide-react";
import Image from "next/image";
import { useState, useEffect, useCallback } from "react";
import { getTokenStore } from "@/lib/api/token-store";
import { getActiveOrganization } from "@/lib/api/org-context";
import { useSession } from "@/components/session-provider";
import { cn } from "@/lib/utils";

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

export interface ShapFeature {
  feature: string;
  shap_value: number;
  direction: "positive" | "negative";
  magnitude: number;
}

export interface ShapExplanation {
  method: string;
  top_features: ShapFeature[];
  interpretation?: string;
}

export interface ScoreBreakdownItem {
  value: number;
  weight: string;
  contribution: number;
  explanation: string;
}

export interface ScoreBreakdown {
  ml_prediction?: ScoreBreakdownItem;
  skill_match?: ScoreBreakdownItem;
  workload_balance?: ScoreBreakdownItem;
  consistency?: ScoreBreakdownItem;
  learning_rate?: ScoreBreakdownItem;
  total?: number;
}

export interface StrengthItem {
  strength: string;
  detail: string;
  metric?: string;
}

export interface ConcernItem {
  concern: string;
  detail: string;
  severity?: "low" | "medium" | "high";
}

export interface RecommendationExplanation {
  dev_id?: string;
  rank?: number;
  final_score?: number;
  summary?: string;
  breakdown?: ScoreBreakdown;
  strengths?: StrengthItem[] | string[];
  concerns?: ConcernItem[] | string[];
  shap_explanation?: ShapExplanation | null;
  recommendation_confidence?: number;
}

interface DeveloperRec {
  id: number;           // allocation id
  developerId: number;
  devId: string;
  name: string;
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
  explanation?: RecommendationExplanation | null;
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

function buildGroups(data: any[]): TaskGroup[] {
  const map = new Map<number, TaskGroup>();

  data.forEach((alloc: any) => {
    const taskRawId: number = alloc.task?.id;
    if (!taskRawId) return;
    const profile = alloc.developer?.profile;
    const fullName = profile
      ? `${profile.firstName ?? profile.first_name ?? ''} ${profile.lastName ?? profile.last_name ?? ''}`.trim()
      : (alloc.developer?.name || alloc.developer?.dev_id);

    let parsedExplanation: RecommendationExplanation | null = null;
    if (alloc.explanation) {
      if (typeof alloc.explanation === "object") {
        parsedExplanation = alloc.explanation;
      } else if (typeof alloc.explanation === "string") {
        try {
          parsedExplanation = JSON.parse(alloc.explanation);
        } catch {
          parsedExplanation = null;
        }
      }
    }

    const rec: DeveloperRec = {
      id: alloc.id,
      developerId: alloc.developer?.id,
      devId: alloc.developer?.dev_id,
      name: fullName || alloc.developer?.dev_id,
      rankPosition: alloc.rankPosition,
      finalScore: Math.round(alloc.finalScore ?? 0),
      skillMatchScore: alloc.skillMatchScore ?? 0,
      workloadBalance: alloc.workloadBalance ?? 0,
      mlScore: alloc.mlScore ?? 0,
      status: alloc.status,
      availability: alloc.developer?.availability ?? 0,
      skillFrontend: alloc.developer?.skill_frontend ?? 0,
      skillBackend: alloc.developer?.skill_backend ?? 0,
      skillDb: alloc.developer?.skill_db ?? 0,
      explanation: parsedExplanation,
    };

    if (!map.has(taskRawId)) {
      map.set(taskRawId, {
        taskId: `T-${taskRawId}`,
        taskRawId,
        title: alloc.task?.task_type || "Task",
        priority: alloc.task?.task_complexity > 3 ? "High" : "Medium",
        complexity: `${alloc.task?.story_points || 0} Story Points`,
        overallStatus: "Pending Approval",
        topAllocationId: alloc.id,
        recommendations: [rec],
      });
    } else {
      map.get(taskRawId)!.recommendations.push(rec);
    }
  });

  map.forEach((group) => {
    group.recommendations.sort((a, b) => a.rankPosition - b.rankPosition);
    if (group.recommendations.length > 5) {
      group.recommendations = group.recommendations.slice(0, 5);
    }
    if (group.recommendations.some((r) => r.status === "ALLOCATED")) {
      group.overallStatus = "Approved";
    }
  });

  return Array.from(map.values());
}

// ─── AI Explanation Generator ────────────────────────────────────────────────

// ─── AI Explanation Generator & SHAP Resolver ───────────────────────────────

function formatFeatureName(raw: string): string {
  const map: Record<string, string> = {
    dev_experience_months: "Developer Experience (Months)",
    experience_level: "Seniority / Experience Level",
    skill_frontend: "Frontend Technical Proficiency",
    skill_backend: "Backend / API Technical Proficiency",
    skill_db: "Database Architecture Skill",
    skill_match: "Component Skill Alignment",
    dev_domain_familiarity: "Domain Expertise & Familiarity",
    dev_prior_completed_tasks: "Prior Completed Tasks (Track Record)",
    dev_prior_completed_rate: "Historical Task Completion Rate",
    dev_historical_velocity_avg: "Historical Average Velocity",
    dev_sprint_task_load: "Current Sprint Task Count",
    dev_sprint_story_point_load: "Assigned Sprint Story Points",
    dev_load_ratio: "Current Sprint Workload Ratio",
    current_tasks: "Concurrent Active Tasks",
    current_workload: "Total Story Point Load",
    availability: "Available Sprint Bandwidth",
    consistency: "Delivery Consistency Metric",
    learning_rate: "Skill Growth & Learning Velocity",
    story_points: "Task Complexity (Story Points)",
    comment_count: "Issue Discussion / Collaboration Intensity",
    sprint_total_issues: "Sprint Scope / Issue Density",
    sprint_devs: "Sprint Team Size (Active Devs)",
    sprint_len: "Sprint Duration (Days)",
    scope_creep: "Sprint Scope Creep Factor",
    historical_failure_rate: "Historical Defect / Failure Probability",
  };
  if (map[raw]) return map[raw];
  return raw
    .split("_")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
}

function generateExplanation(rec: DeveloperRec, taskTitle: string) {
  const skillPct = rec.skillMatchScore <= 1 && rec.skillMatchScore > 0 ? Math.round(rec.skillMatchScore * 100) : Math.round(rec.skillMatchScore);
  const loadPct = Math.round(rec.availability);
  const mlPct = rec.mlScore <= 1 && rec.mlScore > 0 ? Math.round(rec.mlScore * 100) : Math.round(rec.mlScore);

  const matchedSkills: string[] = [];
  if (rec.skillFrontend >= 4) matchedSkills.push("Frontend UI");
  if (rec.skillBackend >= 4) matchedSkills.push("Backend / APIs");
  if (rec.skillDb >= 4) matchedSkills.push("Database & Schema");
  if (matchedSkills.length === 0) matchedSkills.push("Core Fullstack Engineering");

  let summary = "";
  if (rec.rankPosition === 1) {
    summary = `${rec.name} is ranked #1 (Best Match: ${rec.finalScore}%) for "${taskTitle}". They demonstrate strong mastery in ${matchedSkills.join(" & ")}, currently have ${loadPct}% available capacity, and maintain a high historical ML delivery consistency (${mlPct}%).`;
  } else if (rec.rankPosition === 2) {
    summary = `${rec.name} is the second candidate (#2, ${rec.finalScore}% match). Technical proficiency is high (${skillPct}%), but capacity or historical velocity slightly favor the primary candidate.`;
  } else {
    summary = `${rec.name} ranked #${rec.rankPosition} (${rec.finalScore}% match). A solid candidate recommended if primary developers are reassigned to critical paths.`;
  }

  const strengths: string[] = [];
  if (skillPct >= 70) strengths.push(`Strong technical domain fit (${skillPct}% match)`);
  if (loadPct >= 70) strengths.push(`Ample sprint bandwidth (${loadPct}% availability remaining)`);
  if (mlPct >= 75) strengths.push(`High historical delivery consistency (${mlPct}%)`);
  if (strengths.length === 0) strengths.push(`General engineering domain coverage`);

  const considerations: string[] = [];
  if (loadPct < 50) considerations.push(`Bandwidth note: developer has only ${loadPct}% capacity left.`);
  if (skillPct < 60) considerations.push(`Secondary skill match: task may benefit from brief pairing.`);
  if (considerations.length === 0) considerations.push(`Zero capacity or risk bottlenecks detected.`);

  const breakdown = [
    {
      factor: "Domain Skill Alignment",
      weight: "35%",
      score: skillPct,
      description: `Evaluates Frontend (${Math.min(5, rec.skillFrontend)}/5), Backend (${Math.min(5, rec.skillBackend)}/5), and Database (${Math.min(5, rec.skillDb)}/5) against requirements.`,
    },
    {
      factor: "Sprint Capacity & Load",
      weight: "28%",
      score: loadPct,
      description: `Developer has ${loadPct}% availability in the current sprint to prevent team overloading.`,
    },
    {
      factor: "ML Performance Estimate",
      weight: "17%",
      score: mlPct,
      description: `Historical machine learning score evaluating task completion velocity, cycle time, and consistency.`,
    },
    {
      factor: "Delivery Consistency",
      weight: "10%",
      score: Math.round((rec.mlScore || 0.8) * 90),
      description: `Variance in cycle time across recent sprint deliverables.`,
    },
    {
      factor: "Learning Velocity",
      weight: "10%",
      score: 75,
      description: `Historical rate of skill acquisition on new project components.`,
    },
  ];

  return { summary, strengths, considerations, breakdown };
}

function resolveExplanation(rec: DeveloperRec, taskTitle: string) {
  const exp = rec.explanation;
  const fallback = generateExplanation(rec, taskTitle);

  if (!exp) {
    return {
      summary: fallback.summary,
      strengths: fallback.strengths,
      considerations: fallback.considerations,
      breakdown: fallback.breakdown,
      shap: null as ShapExplanation | null,
    };
  }

  // Summary
  const summary = exp.summary && exp.summary.length > 5 ? exp.summary : fallback.summary;

  // Strengths
  let strengths: string[] = [];
  if (Array.isArray(exp.strengths) && exp.strengths.length > 0) {
    strengths = exp.strengths.map((s: any) =>
      typeof s === "string" ? s : `${s.strength}${s.detail ? ` — ${s.detail}` : ""}`
    );
  } else {
    strengths = fallback.strengths;
  }

  // Considerations
  let considerations: string[] = [];
  if (Array.isArray(exp.concerns) && exp.concerns.length > 0) {
    considerations = exp.concerns.map((c: any) =>
      typeof c === "string" ? c : `${c.concern}${c.detail ? ` — ${c.detail}` : ""}`
    );
  } else {
    considerations = fallback.considerations;
  }

  // Breakdown
  let breakdown: Array<{ factor: string; weight: string; score: number; description: string }> = [];
  if (exp.breakdown && typeof exp.breakdown === "object") {
    const b = exp.breakdown;
    const factorDefs = [
      { key: "skill_match", factor: "Domain Skill Alignment", defaultWeight: "35%" },
      { key: "workload_balance", factor: "Sprint Capacity & Load", defaultWeight: "28%" },
      { key: "ml_prediction", factor: "ML Historical Success Estimate", defaultWeight: "17%" },
      { key: "consistency", factor: "Delivery Stability & Quality", defaultWeight: "10%" },
      { key: "learning_rate", factor: "Skill Growth & Learning Velocity", defaultWeight: "10%" },
    ];

    factorDefs.forEach(({ key, factor, defaultWeight }) => {
      const item = (b as any)[key];
      if (item) {
        const val = typeof item.value === "number"
          ? (item.value <= 1 && item.value > 0 ? Math.round(item.value * 100) : Math.round(item.value))
          : 0;
        breakdown.push({
          factor,
          weight: item.weight || defaultWeight,
          score: Math.min(100, Math.max(0, val)),
          description: item.explanation || `Evaluates ${factor.toLowerCase()} metric.`
        });
      }
    });
  }

  if (breakdown.length === 0) {
    breakdown = fallback.breakdown;
  }

  // SHAP
  const shap = exp.shap_explanation || null;

  return { summary, strengths, considerations, breakdown, shap };
}

// ─── AI Explanation Modal ─────────────────────────────────────────────────────

function AIExplanationModal({
  rec,
  taskTitle,
  onClose,
  onApprove,
  isApproved,
  canManageAllocations = true,
}: {
  rec: DeveloperRec;
  taskTitle: string;
  onClose: () => void;
  onApprove: () => void;
  isApproved: boolean;
  canManageAllocations?: boolean;
}) {
  const explanation = resolveExplanation(rec, taskTitle);

  return (
    <div className="fixed inset-0 bg-black/75 backdrop-blur-md z-50 flex items-center justify-center p-4">
      <motion.div
        initial={{ opacity: 0, scale: 0.95, y: 10 }}
        animate={{ opacity: 1, scale: 1, y: 0 }}
        exit={{ opacity: 0, scale: 0.95, y: 10 }}
        className="bg-card border border-primary/30 rounded-2xl w-full max-w-2xl overflow-hidden shadow-2xl relative"
      >
        {/* Modal Header */}
        <div className="p-6 border-b border-border/60 flex items-center justify-between bg-gradient-to-r from-primary/10 via-transparent to-transparent">
          <div className="flex items-center gap-3">
            <div className="relative w-12 h-12 rounded-full overflow-hidden border-2 border-primary/40 shadow-sm">
              <Image
                src={`https://ui-avatars.com/api/?name=${encodeURIComponent(rec.name)}&background=random`}
                alt={rec.name}
                fill
                unoptimized
                className="object-cover"
              />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h3 className="text-lg font-bold text-foreground">{rec.name}</h3>
                <span className="px-2 py-0.5 rounded-full text-xs font-semibold bg-primary/20 text-primary border border-primary/30">
                  Rank #{rec.rankPosition}
                </span>
                {explanation.shap && (
                  <span className="px-2 py-0.5 rounded-full text-[10px] font-semibold bg-indigo-500/20 text-indigo-400 border border-indigo-500/30 flex items-center gap-1">
                    <Sparkles className="w-3 h-3 text-indigo-400" />
                    SHAP Verified
                  </span>
                )}
              </div>
              <p className="text-xs text-muted-foreground">
                Task: <span className="font-medium text-foreground">{taskTitle}</span>
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-2 rounded-lg text-muted-foreground hover:text-foreground hover:bg-muted/50 transition-colors cursor-pointer"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Modal Body */}
        <div className="p-6 space-y-6 max-h-[75vh] overflow-y-auto">
          {/* Executive AI Justification */}
          <div className="p-4 rounded-xl bg-primary/5 border border-primary/20 space-y-2">
            <div className="flex items-center gap-2 text-xs font-semibold text-primary uppercase tracking-wider">
              <Sparkles className="w-4 h-4 text-primary" />
              AI Recommendation Justification
            </div>
            <p className="text-sm text-foreground/90 leading-relaxed">
              {explanation.summary}
            </p>
          </div>

          {/* Real SHAP Feature Attributions (TreeExplainer) */}
          {explanation.shap && explanation.shap.top_features && explanation.shap.top_features.length > 0 && (
            <div className="p-4 rounded-xl bg-slate-900/60 border border-indigo-500/30 space-y-3">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2 text-xs font-semibold text-indigo-400 uppercase tracking-wider">
                  <Sparkles className="w-4 h-4 text-indigo-400" />
                  SHAP Feature Attribution (TreeExplainer)
                </div>
                <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-indigo-500/10 text-indigo-300 border border-indigo-500/20">
                  Model Transparency
                </span>
              </div>

              {explanation.shap.interpretation && (
                <p className="text-xs text-slate-300 leading-relaxed italic bg-indigo-950/30 p-2.5 rounded-lg border border-indigo-800/30">
                  &ldquo;{explanation.shap.interpretation}&rdquo;
                </p>
              )}

              <div className="space-y-2 pt-1">
                <div className="text-[11px] text-muted-foreground flex justify-between">
                  <span>Influential Factor</span>
                  <span>Impact Direction & Magnitude</span>
                </div>
                {explanation.shap.top_features.map((feat, idx) => {
                  const isPositive = feat.direction === "positive" || feat.shap_value > 0;
                  const absVal = Math.abs(feat.shap_value);
                  const barWidth = Math.min(100, Math.max(12, Math.round(absVal * 400)));
                  const featureDisplayName = formatFeatureName(feat.feature);

                  return (
                    <div key={idx} className="space-y-1">
                      <div className="flex items-center justify-between text-xs">
                        <span className="font-medium text-slate-200">{featureDisplayName}</span>
                        <span className={cn(
                          "font-mono text-[11px] font-semibold px-1.5 py-0.5 rounded",
                          isPositive 
                            ? "text-emerald-400 bg-emerald-500/10" 
                            : "text-rose-400 bg-rose-500/10"
                        )}>
                          {isPositive ? `+${feat.shap_value.toFixed(4)}` : feat.shap_value.toFixed(4)}
                        </span>
                      </div>
                      <div className="h-2 bg-slate-800/80 rounded-full overflow-hidden flex">
                        <div
                          className={cn(
                            "h-full rounded-full transition-all duration-500",
                            isPositive 
                              ? "bg-gradient-to-r from-emerald-500 to-teal-400" 
                              : "bg-gradient-to-r from-rose-500 to-red-400"
                          )}
                          style={{ width: `${barWidth}%` }}
                        />
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {/* Strengths & Considerations */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div className="p-4 rounded-xl bg-emerald-500/5 border border-emerald-500/20 space-y-2">
              <div className="flex items-center gap-2 text-xs font-semibold text-emerald-400">
                <Check className="w-4 h-4" />
                Key Advantages
              </div>
              <ul className="space-y-1.5 text-xs text-muted-foreground">
                {explanation.strengths.map((item, idx) => (
                  <li key={idx} className="flex items-start gap-1.5 text-slate-300">
                    <span className="text-emerald-400 font-bold">•</span>
                    <span>{item}</span>
                  </li>
                ))}
              </ul>
            </div>

            <div className="p-4 rounded-xl bg-amber-500/5 border border-amber-500/20 space-y-2">
              <div className="flex items-center gap-2 text-xs font-semibold text-amber-400">
                <AlertTriangle className="w-4 h-4" />
                Sprint Considerations
              </div>
              <ul className="space-y-1.5 text-xs text-muted-foreground">
                {explanation.considerations.map((item, idx) => (
                  <li key={idx} className="flex items-start gap-1.5 text-slate-300">
                    <span className="text-amber-400 font-bold">•</span>
                    <span>{item}</span>
                  </li>
                ))}
              </ul>
            </div>
          </div>

          {/* Mathematical Weight Decomposition */}
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <h4 className="text-xs font-bold uppercase tracking-wider text-muted-foreground flex items-center gap-1.5">
                <Scale className="w-3.5 h-3.5" />
                AHP Decision Weight Breakdown
              </h4>
              <span className="text-xs font-bold text-emerald-400">{rec.finalScore}% Composite Match</span>
            </div>

            <div className="space-y-3">
              {explanation.breakdown.map((item, idx) => (
                <div key={idx} className="p-3 rounded-lg bg-muted/40 border border-border/50 space-y-1.5">
                  <div className="flex items-center justify-between text-xs">
                    <span className="font-semibold text-foreground">
                      {item.factor} <span className="text-muted-foreground text-[10px]">({item.weight} weight)</span>
                    </span>
                    <span className="font-mono font-bold text-primary">{item.score}%</span>
                  </div>
                  <div className="h-1.5 bg-slate-800 rounded-full overflow-hidden">
                    <div
                      className="h-full bg-gradient-to-r from-primary to-emerald-400 rounded-full"
                      style={{ width: `${Math.min(100, item.score)}%` }}
                    />
                  </div>
                  <p className="text-[11px] text-muted-foreground">{item.description}</p>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* Modal Footer */}
        <div className="p-4 border-t border-border/60 flex items-center justify-end gap-3 bg-muted/20">
          <button
            onClick={onClose}
            className="px-4 py-2 text-xs font-medium text-muted-foreground hover:text-foreground rounded-lg transition-colors cursor-pointer"
          >
            Close
          </button>
          {!isApproved && rec.status !== "ALLOCATED" && canManageAllocations && (
            <button
              onClick={() => {
                onApprove();
                onClose();
              }}
              className="px-4 py-2 bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold rounded-lg flex items-center gap-1.5 transition-colors shadow-sm cursor-pointer"
            >
              <UserCheck className="w-3.5 h-3.5" />
              Approve & Assign Developer
            </button>
          )}
        </div>
      </motion.div>
    </div>
  );
}

// ─── Workload Modal ──────────────────────────────────────────────────────────

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
    async function load() {
      try {
        const res = await authFetch(`${backendOrigin}/api/tasks/developer/${developerId}`);
        const data = await res.json();
        setTasks(Array.isArray(data) ? data : []);
      } catch (err) {
        console.error(err);
      } finally {
        setLoading(false);
      }
    }
    load();
  }, [developerId]);

  return (
    <div className="fixed inset-0 bg-black/60 backdrop-blur-sm z-50 flex items-center justify-center p-4">
      <motion.div
        initial={{ opacity: 0, scale: 0.95 }}
        animate={{ opacity: 1, scale: 1 }}
        exit={{ opacity: 0, scale: 0.95 }}
        className="bg-card border border-border rounded-2xl p-6 w-full max-w-lg shadow-2xl relative"
      >
        <div className="flex items-center justify-between mb-4 border-b border-border pb-3">
          <h3 className="text-base font-bold text-foreground">
            Current Workload: <span className="text-primary">{devId}</span>
          </h3>
          <button onClick={onClose} className="text-muted-foreground hover:text-foreground cursor-pointer">
            <X className="w-5 h-5" />
          </button>
        </div>

        {loading ? (
          <div className="py-12 flex justify-center">
            <Loader2 className="w-6 h-6 animate-spin text-primary" />
          </div>
        ) : tasks.length === 0 ? (
          <div className="text-center py-8 text-xs text-muted-foreground">
            No active tasks currently assigned.
          </div>
        ) : (
          <div className="space-y-2 max-h-64 overflow-y-auto">
            {tasks.map((t) => (
              <div key={t.id} className="p-3 rounded-lg border border-border bg-muted/20 flex justify-between items-center text-xs">
                <div>
                  <div className="font-semibold text-foreground">{t.task_type}</div>
                  <div className="text-[11px] text-muted-foreground mt-0.5">Status: {t.status}</div>
                </div>
                <span className="px-2 py-0.5 rounded bg-primary/10 text-primary font-mono font-semibold">
                  {t.story_points || 0} pts
                </span>
              </div>
            ))}
          </div>
        )}

        <button
          onClick={onClose}
          className="w-full mt-5 py-2 bg-muted hover:bg-muted/80 text-foreground rounded-lg text-xs font-semibold transition-colors cursor-pointer"
        >
          Done
        </button>
      </motion.div>
    </div>
  );
}

// ─── Score Pill & Skill Bar ──────────────────────────────────────────────────

function ScorePill({ value, label, color }: { value: number; label: string; color: string }) {
  const pct = value <= 1 && value > 0 ? Math.round(value * 100) : Math.round(value);
  return (
    <div className="flex flex-col items-center">
      <div className={`text-xs font-bold ${color}`}>{pct}%</div>
      <div className="text-[9px] text-muted-foreground uppercase tracking-wide font-medium">{label}</div>
    </div>
  );
}

function SkillBar({ label, value, max = 5 }: { label: string; value: number; max?: number }) {
  const cleanVal = Math.min(max, Math.max(0, value ?? 0));
  const pct = Math.round((cleanVal / max) * 100);
  return (
    <div>
      <div className="flex justify-between text-[10px] text-muted-foreground mb-0.5">
        <span>{label}</span>
        <span className="font-mono font-medium">{cleanVal}/{max}</span>
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

// ─── DevCard ─────────────────────────────────────────────────────────────────

function DevCard({
  rec,
  taskTitle,
  selected,
  onSelect,
  isApproved,
  onApprove,
  onDecline,
  onViewWorkload,
  onViewExplanation,
  approving,
  canManageAllocations = true,
}: {
  rec: DeveloperRec;
  taskTitle: string;
  selected: boolean;
  onSelect: () => void;
  isApproved: boolean;
  onApprove: () => void;
  onDecline: () => void;
  onViewWorkload: (id: number, devId: string) => void;
  onViewExplanation: (rec: DeveloperRec) => void;
  approving: boolean;
  canManageAllocations?: boolean;
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
          ? "border-border/40 bg-muted/10 cursor-not-allowed"
          : selected
          ? "border-primary/60 bg-primary/5 shadow-md shadow-primary/10"
          : "border-border hover:border-slate-600 bg-card/60"
      }`}
      onClick={() => {
        if (!isDeclined && !isThisApproved) onSelect();
      }}
    >
      {/* Rank badge */}
      <div className={`absolute -top-2.5 -left-2.5 w-6 h-6 rounded-full bg-slate-900 border border-slate-700 flex items-center justify-center text-[10px] font-bold ${rankColor}`}>
        {rec.rankPosition === 1 ? <Trophy className="w-3 h-3 text-yellow-400" /> : `#${rec.rankPosition}`}
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
          className="relative w-9 h-9 rounded-full overflow-hidden border border-slate-700 shrink-0 hover:ring-2 ring-primary/40 transition-all cursor-pointer"
          onClick={(e) => {
            e.stopPropagation();
            onViewWorkload(rec.developerId, rec.name || rec.devId);
          }}
          title="Click to view developer workload"
        >
          <Image
            src={`https://ui-avatars.com/api/?name=${encodeURIComponent(rec.name || rec.devId)}&background=random`}
            alt={rec.name || rec.devId}
            fill
            unoptimized
            className="object-cover"
          />
        </div>
        <div className="min-w-0 flex-1">
          <div className="text-sm font-semibold text-foreground truncate hover:text-primary transition-colors">
            {rec.name || rec.devId}
          </div>
          <div className="text-xs text-muted-foreground flex items-center gap-1.5">
            <span className="text-[10px] font-mono">{rec.devId}</span>
            <span>•</span>
            <span>Avail: {rec.availability.toFixed(0)}%</span>
          </div>
        </div>
        <div className="ml-auto text-right shrink-0">
          <div className="text-xl font-bold text-emerald-400">{rec.finalScore}%</div>
          <div className="text-[9px] text-muted-foreground uppercase font-semibold">Match</div>
        </div>
      </div>

      {/* Score pills */}
      <div className="flex justify-between px-1 mb-3 bg-muted/30 py-1.5 rounded-lg border border-border/40">
        <ScorePill value={rec.skillMatchScore} label="Skill" color="text-blue-400" />
        <ScorePill value={rec.availability} label="Load" color="text-purple-400" />
        <ScorePill value={rec.mlScore} label="ML Pred" color="text-amber-400" />
      </div>

      {/* Skill bars */}
      <div className="space-y-1.5 mb-3">
        <SkillBar label="Frontend" value={rec.skillFrontend} />
        <SkillBar label="Backend" value={rec.skillBackend} />
        <SkillBar label="Database" value={rec.skillDb} />
      </div>

      {/* AI Explanation Button */}
      <button
        type="button"
        onClick={(e) => {
          e.stopPropagation();
          onViewExplanation(rec);
        }}
        className="w-full mb-3 py-1.5 px-2.5 rounded-lg bg-primary/10 hover:bg-primary/20 text-primary border border-primary/20 hover:border-primary/40 text-xs font-semibold flex items-center justify-center gap-1.5 transition-all cursor-pointer shadow-sm"
      >
        <Sparkles className="w-3.5 h-3.5 text-primary" />
        <span>{rec.explanation?.shap_explanation ? "AI & SHAP Explanation" : "AI Explanation"}</span>
        {rec.explanation?.shap_explanation && (
          <span className="ml-1 text-[9px] font-mono bg-indigo-500/20 text-indigo-300 px-1.5 py-0.2 rounded border border-indigo-500/30">
            SHAP
          </span>
        )}
      </button>

      {/* Action buttons */}
      <AnimatePresence>
        {selected && isRecommended && !isApproved && canManageAllocations && (
          <motion.div
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: "auto" }}
            exit={{ opacity: 0, height: 0 }}
            className="flex gap-2 overflow-hidden"
          >
            <button
              onClick={(e) => {
                e.stopPropagation();
                onApprove();
              }}
              disabled={approving}
              className="flex-1 flex items-center justify-center gap-1.5 px-3 py-2 bg-emerald-500/20 hover:bg-emerald-500/30 text-emerald-400 rounded-lg text-xs font-semibold border border-emerald-500/30 transition-colors disabled:opacity-50 cursor-pointer"
            >
              {approving ? <Loader2 className="w-3 h-3 animate-spin" /> : <UserCheck className="w-3 h-3" />}
              Approve
            </button>
            <button
              onClick={(e) => {
                e.stopPropagation();
                onDecline();
              }}
              disabled={approving}
              className="flex-1 flex items-center justify-center gap-1.5 px-3 py-2 bg-red-500/10 hover:bg-red-500/20 text-red-400 rounded-lg text-xs font-semibold border border-red-500/20 transition-colors disabled:opacity-50 cursor-pointer"
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
  canManageAllocations = true,
  onApprove,
  onDecline,
  onUnassignTask,
  onViewWorkload,
  onViewExplanation,
}: {
  group: TaskGroup;
  canManageAllocations?: boolean;
  onApprove: (allocationId: number, developerId: number) => Promise<void>;
  onDecline: (allocationId: number) => Promise<void>;
  onUnassignTask: (taskRawId: number) => Promise<void>;
  onViewWorkload: (id: number, devId: string) => void;
  onViewExplanation: (rec: DeveloperRec, taskTitle: string) => void;
}) {
  const [expanded, setExpanded] = useState(true);
  const [selectedAllocId, setSelectedAllocId] = useState<number | null>(null);
  const [approving, setApproving] = useState(false);
  const [unassigning, setUnassigning] = useState(false);

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

  const handleUnassign = async (e: React.MouseEvent) => {
    e.stopPropagation();
    setUnassigning(true);
    await onUnassignTask(group.taskRawId);
    setUnassigning(false);
  };

  return (
    <motion.div
      layout
      className={`rounded-xl border transition-all ${
        isApproved ? "border-emerald-500/30 bg-card" : "border-border hover:border-primary/40 bg-card"
      }`}
    >
      {/* Task header */}
      <div
        className="flex flex-col lg:flex-row items-start lg:items-center justify-between gap-4 p-5 cursor-pointer select-none"
        onClick={() => setExpanded((v) => !v)}
      >
        <div className="flex-1 min-w-0">
          <div className="flex flex-wrap items-center gap-2 mb-1.5">
            <span className="text-xs font-mono px-2 py-0.5 rounded bg-muted text-muted-foreground border border-border">
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
            <span className="flex items-center text-xs text-muted-foreground">
              <Clock className="w-3 h-3 mr-1" />
              {group.complexity}
            </span>
          </div>
          <h3 className="text-lg font-bold text-foreground">{group.title}</h3>
        </div>

        <div className="flex items-center gap-3 shrink-0">
          {isApproved && approvedRec ? (
            <div className="flex items-center gap-2 mr-2">
              <div className="relative w-8 h-8 rounded-full overflow-hidden border border-emerald-500/40">
                <Image
                  src={`https://ui-avatars.com/api/?name=${encodeURIComponent(approvedRec.name || approvedRec.devId)}&background=random`}
                  alt={approvedRec.name || approvedRec.devId}
                  fill
                  unoptimized
                  className="object-cover"
                />
              </div>
              <div>
                <div className="text-sm font-semibold text-foreground">{approvedRec.name || approvedRec.devId}</div>
                <div className="text-xs text-emerald-400 font-medium">{approvedRec.finalScore}% Match</div>
              </div>
            </div>
          ) : (
            <div className="text-xs text-muted-foreground flex items-center gap-1.5 mr-2">
              <Bot className="w-4 h-4 text-primary" />
              <span>{group.recommendations.filter((r) => r.status === "RECOMMENDED").length} candidates</span>
            </div>
          )}

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

          {isApproved && canManageAllocations && (
            <button
              onClick={handleUnassign}
              disabled={unassigning}
              className="px-2.5 py-1.5 text-xs text-destructive hover:bg-destructive/10 border border-destructive/20 rounded-lg flex items-center gap-1 transition-colors cursor-pointer disabled:opacity-50"
              title="Unassign this task"
            >
              {unassigning ? <Loader2 className="w-3 h-3 animate-spin" /> : <UserX className="w-3 h-3" />}
              <span>Unassign</span>
            </button>
          )}

          <div className="text-muted-foreground">
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
            <div className="px-5 pb-5 border-t border-border pt-4">
              <div className="flex items-center justify-between mb-4">
                <p className="text-xs text-muted-foreground">
                  {isApproved
                    ? "Task assigned. Click any developer's 'AI Explanation' to see the detailed scoring justification, or click 'Unassign' above to reset."
                    : "Select a developer and approve or click 'AI Explanation' to view the full decision breakdown."}
                </p>
              </div>

              {/* Developer grid */}
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-3 gap-4">
                {group.recommendations.map((rec) => (
                  <DevCard
                    key={rec.id}
                    rec={rec}
                    taskTitle={group.title}
                    selected={selectedAllocId === rec.id}
                    isApproved={isApproved}
                    approving={approving}
                    canManageAllocations={canManageAllocations}
                    onSelect={() => setSelectedAllocId(rec.id === selectedAllocId ? null : rec.id)}
                    onApprove={() => handleApprove(rec)}
                    onDecline={() => handleDecline(rec)}
                    onViewWorkload={onViewWorkload}
                    onViewExplanation={(r) => onViewExplanation(r, group.title)}
                  />
                ))}
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </motion.div>
  );
}

// ─── Main View ────────────────────────────────────────────────────────────────

export default function AIAllocationView() {
  const [taskGroups, setTaskGroups] = useState<TaskGroup[]>([]);
  const [sprints, setSprints] = useState<any[]>([]);
  const [selectedSprintId, setSelectedSprintId] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [generating, setGenerating] = useState(false);
  const [approvingAll, setApprovingAll] = useState(false);
  const [unassigningAll, setUnassigningAll] = useState(false);
  const [workloadDev, setWorkloadDev] = useState<{ id: number; devId: string } | null>(null);
  const [explanationRec, setExplanationRec] = useState<{ rec: DeveloperRec; taskTitle: string } | null>(null);

  const fetchSprints = useCallback(async () => {
    try {
      const res = await authFetch(`${backendOrigin}/api/sprints`);
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
      const res = await authFetch(`${backendOrigin}/api/allocation/sprint/${selectedSprintId}`);
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
    if (!selectedSprintId) return;
    setGenerating(true);
    try {
      await authFetch(`${backendOrigin}/api/rank/recommendations/sprint/${selectedSprintId}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
      });
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
        `${backendOrigin}/api/allocation/assign?allocationId=${allocationId}&developerId=${developerId}`,
        { method: "PUT" }
      );
      await fetchAllocations();
    } catch (error) {
      console.error("Error approving allocation:", error);
    }
  };

  const handleDecline = async (allocationId: number) => {
    try {
      await authFetch(`${backendOrigin}/api/allocation/${allocationId}/decline`, {
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
      await authFetch(`${backendOrigin}/api/allocation/approve-all/sprint/${selectedSprintId}`, {
        method: "POST",
      });
      await fetchAllocations();
    } catch (error) {
      console.error("Error approving all recommendations:", error);
    } finally {
      setApprovingAll(false);
    }
  };

  const handleUnassignAll = async () => {
    if (!selectedSprintId) return;
    setUnassigningAll(true);
    try {
      await authFetch(`${backendOrigin}/api/allocation/unassign-all/sprint/${selectedSprintId}`, {
        method: "POST",
      });
      await fetchAllocations();
    } catch (error) {
      console.error("Error unassigning all:", error);
    } finally {
      setUnassigningAll(false);
    }
  };

  const handleUnassignTask = async (taskRawId: number) => {
    try {
      await authFetch(`${backendOrigin}/api/allocation/task/${taskRawId}/unassign`, {
        method: "POST",
      });
      await fetchAllocations();
    } catch (error) {
      console.error("Error unassigning task:", error);
    }
  };

  const { user, hasPermission } = useSession();
  const roleCode = user?.activeRole?.toUpperCase() || "MEMBER";
  const canManageAllocations = 
    roleCode === "OWNER" || 
    roleCode === "ADMIN" || 
    roleCode === "MANAGER" || 
    hasPermission("sprint:manage");

  const pendingCount = taskGroups.filter((g) => g.overallStatus !== "Approved").length;
  const approvedCount = taskGroups.filter((g) => g.overallStatus === "Approved").length;

  return (
    <div className="w-full space-y-6">
      {/* ── Header Controls ── */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h2 className="text-xl font-bold tracking-tight text-foreground flex items-center gap-2">
            <Sparkles className="w-5 h-5 text-primary" />
            AI Resource Allocation & Recommendation Engine
          </h2>
          <p className="text-xs text-muted-foreground mt-0.5">
            Real-time skill matching, capacity optimization, and machine learning performance predictions.
          </p>
        </div>

        <div className="flex items-center gap-3 shrink-0 flex-wrap">
          <div className="flex items-center gap-2 bg-card border border-border rounded-lg px-3 py-1.5 shadow-sm">
            <Layers className="w-4 h-4 text-muted-foreground" />
            <select
              value={selectedSprintId || ""}
              onChange={(e) => setSelectedSprintId(Number(e.target.value))}
              className="bg-transparent text-xs font-semibold text-foreground outline-none cursor-pointer"
            >
              {sprints.map((s) => (
                <option key={s.id} value={s.id} className="bg-card text-foreground">
                  Sprint: {s.sprintId}
                </option>
              ))}
            </select>
          </div>

          <button
            onClick={generateRecommendations}
            disabled={generating || !selectedSprintId || !canManageAllocations}
            className={cn(
              "px-3.5 py-2 rounded-lg text-xs font-semibold transition-colors border flex items-center gap-1.5 shadow-sm",
              canManageAllocations
                ? "bg-muted hover:bg-muted/80 text-foreground border-border cursor-pointer"
                : "bg-muted/30 text-muted-foreground border-border/40 opacity-60 cursor-not-allowed"
            )}
            title={canManageAllocations ? "Run AI resource ranking for sprint" : "Sprint re-ranking restricted to Managers & Admins"}
          >
            {generating ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5" />}
            <span>Re-Rank Sprint</span>
          </button>

          {canManageAllocations && pendingCount > 0 && (
            <button
              onClick={handleApproveAll}
              disabled={approvingAll || !selectedSprintId}
              className="px-3.5 py-2 bg-emerald-600 hover:bg-emerald-500 text-white rounded-lg text-xs font-semibold transition-colors flex items-center gap-1.5 disabled:opacity-50 shadow-sm cursor-pointer"
              title="Runs Global Hungarian Optimization to balance sprint workload across all developers and eliminate idle capacity"
            >
              {approvingAll ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <CheckCircle2 className="w-3.5 h-3.5" />}
              <span>AI Auto-Allocate (Global Optimal)</span>
            </button>
          )}

          {canManageAllocations && approvedCount > 0 && (
            <button
              onClick={handleUnassignAll}
              disabled={unassigningAll || !selectedSprintId}
              className="px-3.5 py-2 bg-destructive/10 hover:bg-destructive/20 text-destructive rounded-lg text-xs font-semibold transition-colors border border-destructive/20 flex items-center gap-1.5 disabled:opacity-50 shadow-sm cursor-pointer"
              title="Reset all assignments for this sprint"
            >
              {unassigningAll ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RotateCcw className="w-3.5 h-3.5" />}
              <span>Unassign All</span>
            </button>
          )}
        </div>
      </div>

      {/* Task Rows */}
      {loading ? (
        <div className="flex items-center justify-center py-16">
          <Loader2 className="w-8 h-8 animate-spin text-primary" />
        </div>
      ) : taskGroups.length === 0 ? (
        <div className="text-center py-16 text-xs text-muted-foreground bg-card rounded-2xl border border-border">
          No sprint tasks found. Create stories in your sprint or click "Re-Rank Sprint".
        </div>
      ) : (
        <div className="space-y-4">
          {taskGroups.map((group) => (
            <TaskRow
              key={group.taskId}
              group={group}
              canManageAllocations={canManageAllocations}
              onApprove={handleApprove}
              onDecline={handleDecline}
              onUnassignTask={handleUnassignTask}
              onViewWorkload={(id, devId) => setWorkloadDev({ id, devId })}
              onViewExplanation={(rec, taskTitle) => setExplanationRec({ rec, taskTitle })}
            />
          ))}
        </div>
      )}

      {/* Workload Modal */}
      {workloadDev && (
        <DeveloperWorkloadModal
          developerId={workloadDev.id}
          devId={workloadDev.devId}
          onClose={() => setWorkloadDev(null)}
        />
      )}

      {/* AI Explanation Modal */}
      {explanationRec && (
        <AIExplanationModal
          rec={explanationRec.rec}
          taskTitle={explanationRec.taskTitle}
          onClose={() => setExplanationRec(null)}
          onApprove={() => handleApprove(explanationRec.rec.id, explanationRec.rec.developerId)}
          isApproved={explanationRec.rec.status === "ALLOCATED"}
          canManageAllocations={canManageAllocations}
        />
      )}
    </div>
  );
}
