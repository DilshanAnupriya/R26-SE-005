"use client";
import { backendOrigin } from "@/lib/env";

import { useState, useEffect } from "react";
import { getTokenStore } from "@/lib/api/token-store";
import { getActiveOrganization } from "@/lib/api/org-context";
import { motion, AnimatePresence } from "framer-motion";
import { 
  Search, 
  Filter, 
  Code2, 
  Server, 
  Database, 
  Layout, 
  BrainCircuit,
  Star,
  Activity,
  X,
  Mail,
  Zap,
  TrendingUp,
  Award,
  Layers,
  Sparkles,
  CheckCircle2,
  Briefcase,
  UserCheck,
  ShieldCheck,
  BarChart3,
  AlertTriangle,
  Clock,
  Target,
  ChevronDown,
  ChevronUp,
  ShieldAlert,
  Loader2,
  HelpCircle
} from "lucide-react";
import Image from "next/image";

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

interface DeveloperProfile {
  id?: number;
  firstName?: string;
  lastName?: string;
  email?: string;
  specialization?: string;
  bio?: string;
  age?: number;
}

interface DeveloperData {
  id: number;
  dev_id: string;
  experience_level: number;
  skill_frontend: number;
  skill_backend: number;
  skill_db: number;
  current_workload: number;
  availability: number;
  current_tasks: number;
  consistency: number;
  learning_rate: number;
  base_velocity?: number;
  profile?: DeveloperProfile;
}

interface TaskData {
  id: number;
  task_type: string;
  story_points: number;
  task_complexity: number;
  req_frontend: number;
  req_backend: number;
  req_db: number;
  status: string;
  developer?: {
    id: number;
  };
}

interface TaskSummary {
  id: number;
  taskType: string;
  storyPoints: number;
  complexity: number;
  status: string;
  reqFrontend: number;
  reqBackend: number;
  reqDb: number;
  defects: number;
}

interface SprintPerformance {
  sprintId: number;
  sprintName: string;
  status: string;
  plannedSp: number;
  deliveredSp: number;
  tasksCount: number;
  defects: number;
  avgCycleTimeHours: number;
  tasks: TaskSummary[];
}

interface DeveloperAnalytics {
  developerId: number;
  devId: string;
  fullName: string;
  role: string;
  specialization: string;
  email: string;
  bio: string;
  experienceLevel: number;
  consistencyScore: number;
  learningRate: number;
  effectiveCapacity: number;
  currentWorkload: number;
  availability: number;
  currentTasks: number;
  totalTasksAssigned: number;
  totalTasksCompleted: number;
  totalStoryPointsDelivered: number;
  avgCycleTimeHours: number;
  defectRate: number;
  completionRate: number;
  sprintHistory: SprintPerformance[];
  domainDistribution: Record<string, number>;
  aiExecutiveSummary: string;
  strengths: string[];
  growthAreas: string[];
  coachingRecommendation: string;
  burnoutRiskLevel: "LOW" | "MODERATE" | "HIGH";
}

interface UIDeveloper {
  id: number;
  raw: DeveloperData;
  name: string;
  role: string;
  avatar: string;
  skills: string[];
  workload: number;
  capacity: number;
  capacitySource: "empirical" | "cold-start";
  availability: number;
  performance: number;
  status: string;
  projects: number;
}

const getWorkloadColor = (workload: number) => {
  if (workload < 50) return "text-green-500 bg-green-500/10 border-green-500/20";
  if (workload < 85) return "text-blue-500 bg-blue-500/10 border-blue-500/20";
  return "text-red-500 bg-red-500/10 border-red-500/20";
};

const getSkillIcon = (skill: string) => {
  const s = skill.toLowerCase();
  if (s.includes('react') || s.includes('next') || s.includes('tailwind') || s.includes('ui') || s.includes('frontend')) return <Layout className="w-3.5 h-3.5 mr-1" />;
  if (s.includes('node') || s.includes('java') || s.includes('spring') || s.includes('python') || s.includes('backend') || s.includes('api')) return <Server className="w-3.5 h-3.5 mr-1" />;
  if (s.includes('sql') || s.includes('postgres') || s.includes('mongo') || s.includes('pandas') || s.includes('db') || s.includes('database')) return <Database className="w-3.5 h-3.5 mr-1" />;
  if (s.includes('ml') || s.includes('tensor') || s.includes('pytorch')) return <BrainCircuit className="w-3.5 h-3.5 mr-1" />;
  return <Code2 className="w-3.5 h-3.5 mr-1" />;
};

export default function DevelopersView() {
  const [developers, setDevelopers] = useState<UIDeveloper[]>([]);
  const [tasks, setTasks] = useState<TaskData[]>([]);
  const [selectedDev, setSelectedDev] = useState<UIDeveloper | null>(null);
  const [analytics, setAnalytics] = useState<DeveloperAnalytics | null>(null);
  const [loadingAnalytics, setLoadingAnalytics] = useState(false);
  const [modalTab, setModalTab] = useState<"overview" | "sprints" | "coaching">("overview");
  const [expandedSprintId, setExpandedSprintId] = useState<number | null>(null);
  const [hoveredSprint, setHoveredSprint] = useState<SprintPerformance | null>(null);
  const [searchQuery, setSearchQuery] = useState("");
  const [loading, setLoading] = useState(true);

  // Fetch full developer analytics whenever a developer is clicked
  useEffect(() => {
    if (selectedDev) {
      setLoadingAnalytics(true);
      setModalTab("overview");
      setExpandedSprintId(null);
      setHoveredSprint(null);

      authFetch(`${backendOrigin}/api/developers/${selectedDev.id}/analytics`)
        .then((res) => {
          if (!res.ok) throw new Error("Failed to fetch analytics");
          return res.json();
        })
        .then((data: DeveloperAnalytics) => {
          setAnalytics(data);
          setLoadingAnalytics(false);
        })
        .catch((err) => {
          console.error("Failed to load developer analytics:", err);
          setLoadingAnalytics(false);
        });
    } else {
      setAnalytics(null);
    }
  }, [selectedDev]);

  useEffect(() => {
    Promise.all([
      authFetch(`${backendOrigin}/api/developers`).then(res => res.json()),
      authFetch(`${backendOrigin}/api/tasks`).then(res => res.json()).catch(() => [])
    ])
      .then(([devData, taskData]: [DeveloperData[], TaskData[]]) => {
        if (Array.isArray(taskData)) {
          setTasks(taskData);
        }
        if (!Array.isArray(devData)) {
          setDevelopers([]);
          setLoading(false);
          return;
        }

        const mapped = devData.map(dev => {
          const skills: string[] = [];
          if (dev.skill_frontend > 5) skills.push("Frontend");
          if (dev.skill_backend > 5) skills.push("Backend");
          if (dev.skill_db > 5) skills.push("Database");
          if (skills.length === 0) skills.push("General");

          let role = "Developer";
          if (dev.experience_level >= 4) role = "Senior Engineer";
          else if (dev.experience_level >= 2) role = "Mid-level Engineer";
          else role = "Junior Engineer";

          const profile = dev.profile;
          const fullName = profile
            ? `${profile.firstName ?? ''} ${profile.lastName ?? ''}`.trim()
            : dev.dev_id;

          const empiricalVel = (dev.base_velocity && dev.base_velocity > 0) ? dev.base_velocity : 0;
          const capacity = empiricalVel > 0 ? empiricalVel : (dev.experience_level || 2) * 10;
          const capacitySource: "empirical" | "cold-start" = empiricalVel > 0 ? "empirical" : "cold-start";

          return {
            id: dev.id,
            raw: dev,
            name: fullName || dev.dev_id,
            role,
            avatar: `https://ui-avatars.com/api/?name=${encodeURIComponent(fullName || dev.dev_id)}&background=random`,
            skills,
            workload: dev.current_workload,
            capacity: Math.round(capacity * 10) / 10,
            capacitySource,
            availability: dev.availability ?? 100,
            performance: Math.round(dev.consistency * 100),
            status: dev.availability > 40 ? "Available" : "Busy",
            projects: dev.current_tasks
          };
        });

        setDevelopers(mapped);
        setLoading(false);
      })
      .catch(err => {
        console.error("Failed to load developers:", err);
        setLoading(false);
      });
  }, []);

  const totalKeyDomains = 3;
  const coveredDomains = [
    developers.some(d => d.skills.includes("Frontend")),
    developers.some(d => d.skills.includes("Backend")),
    developers.some(d => d.skills.includes("Database"))
  ].filter(Boolean).length;
  const dynamicSkillCoverage = Math.round((coveredDomains / totalKeyDomains) * 100);

  const avgAvailableCapacity = Math.round(
    developers.reduce((acc, dev) => acc + (dev.availability || 0), 0) / (developers.length || 1)
  );

  const filteredDevelopers = developers.filter(dev => {
    const q = searchQuery.toLowerCase();
    return (
      dev.name.toLowerCase().includes(q) ||
      dev.role.toLowerCase().includes(q) ||
      dev.skills.some(s => s.toLowerCase().includes(q)) ||
      dev.raw.dev_id.toLowerCase().includes(q)
    );
  });

  const container = {
    hidden: { opacity: 0 },
    show: {
      opacity: 1,
      transition: { staggerChildren: 0.08 }
    }
  };

  const item = {
    hidden: { y: 20, opacity: 0 },
    show: { y: 0, opacity: 1 }
  };

  // Get assigned tasks for currently selected developer
  const selectedDevTasks = selectedDev 
    ? tasks.filter(t => t.developer?.id === selectedDev.id) 
    : [];

  return (
    <div className="w-full space-y-8">
      {/* Header section */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h2 className="text-lg font-bold text-foreground tracking-wide">Developer Profiles & AI Telemetry</h2>
          <p className="text-xs text-muted-foreground mt-0.5">Verified skill proficiencies, historical consistency, and real-time sprint workloads.</p>
        </div>

        <div className="flex items-center gap-3">
          <div className="relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
            <input 
              type="text" 
              placeholder="Search by name, skill, or ID..." 
              className="pl-9 pr-4 py-2 bg-card border border-border rounded-xl text-xs focus:outline-none focus:ring-1 focus:ring-primary/50 text-foreground w-72"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
          <button className="p-2 bg-card border border-border rounded-xl hover:bg-muted transition-colors text-muted-foreground">
            <Filter className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* Stats Row */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        {[
          { label: "Total Developers", value: developers.length.toString(), sub: "Active team members" },
          { label: "Avg Performance", value: `${Math.round(developers.reduce((acc, dev) => acc + dev.performance, 0) / (developers.length || 1))}%`, sub: "Historical consistency index" },
          { label: "Available Capacity", value: `${avgAvailableCapacity}%`, sub: `${developers.filter(d => d.status === "Available").length} of ${developers.length} devs taking tasks` },
          { label: "Skill Coverage", value: `${dynamicSkillCoverage}%`, sub: `${coveredDomains} of ${totalKeyDomains} core domains covered` },
        ].map((stat, i) => (
          <div key={i} className="glass p-5 rounded-2xl border border-border">
            <div className="text-sm text-muted-foreground mb-1">{stat.label}</div>
            <div className="text-2xl font-bold text-foreground">{stat.value}</div>
            <div className="text-xs text-emerald-500 dark:text-emerald-400 mt-1">{stat.sub}</div>
          </div>
        ))}
      </div>

      {/* Developer Grid */}
      {loading ? (
        <div className="flex justify-center items-center py-20 text-muted-foreground">
          <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary mr-3"></div>
          Loading developers...
        </div>
      ) : filteredDevelopers.length === 0 ? (
        <div className="text-center py-20 border border-dashed border-border rounded-2xl bg-card/40 p-8">
          <Code2 className="w-12 h-12 mx-auto text-primary/60 mb-3" />
          <h3 className="text-xl font-semibold text-foreground">No developers found</h3>
          <p className="text-sm mt-2 text-muted-foreground max-w-md mx-auto">
            {searchQuery ? "No developer matches your search query." : "Team members will appear here for AI resource matching."}
          </p>
        </div>
      ) : (
        <motion.div 
          variants={container}
          initial="hidden"
          animate="show"
          className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6"
        >
        {filteredDevelopers.map((dev) => (
          <div key={dev.id}>
            <motion.div 
              variants={item} 
              onClick={() => setSelectedDev(dev)}
              className="glass rounded-2xl border border-border overflow-hidden group hover:border-primary/50 hover:shadow-lg hover:shadow-primary/5 transition-all duration-300 h-full cursor-pointer relative"
            >
              <div className="p-6">
                <div className="flex justify-between items-start mb-4">
                  <div className="flex items-center space-x-4">
                    <div className="relative w-12 h-12 rounded-full overflow-hidden border-2 border-primary/20 group-hover:border-primary/60 transition-colors">
                      <Image src={dev.avatar} alt={dev.name} fill unoptimized className="object-cover" />
                    </div>
                    <div>
                      <div className="flex items-center gap-2">
                        <h3 className="font-bold text-foreground text-base group-hover:text-primary transition-colors">{dev.name}</h3>
                        <span className="text-[10px] font-mono text-muted-foreground bg-muted px-1.5 py-0.5 rounded border border-border">
                          {dev.raw.dev_id}
                        </span>
                      </div>
                      <p className="text-xs text-muted-foreground mt-0.5">{dev.role}</p>
                    </div>
                  </div>

                  <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full border ${
                    dev.status === 'Available' ? 'bg-emerald-500/10 text-emerald-500 dark:text-emerald-400 border-emerald-500/20' : 'bg-amber-500/10 text-amber-500 dark:text-amber-400 border-amber-500/20'
                  }`}>
                    {dev.status}
                  </span>
                </div>

                <div className="space-y-4">
                  <div>
                    <div className="text-[10px] font-bold text-muted-foreground mb-2 uppercase tracking-wider">Top Skills</div>
                    <div className="flex flex-wrap gap-1.5">
                      {dev.skills.map((skill, i) => (
                        <span key={i} className="inline-flex items-center px-2 py-1 rounded-lg text-xs font-semibold bg-muted text-foreground border border-border">
                          {getSkillIcon(skill)}
                          {skill}
                        </span>
                      ))}
                    </div>
                  </div>

                  {/* Workload & Availability Progress */}
                  <div className="pt-3 border-t border-border space-y-3">
                    <div>
                      <div className="flex justify-between items-center text-xs mb-1.5">
                        <span className="text-muted-foreground font-medium">Sprint Workload</span>
                        <div className="flex items-center gap-1.5">
                          <span className="font-bold text-foreground">{dev.workload} / {Math.round(dev.capacity)} SPs</span>
                          <span
                            className={`text-[9px] font-mono px-1 py-0.5 rounded border ${
                              dev.capacitySource === 'empirical'
                                ? 'text-cyan-500 bg-cyan-500/10 border-cyan-500/20'
                                : 'text-muted-foreground bg-muted border-border'
                            }`}
                            title={dev.capacitySource === 'empirical' ? 'Empirical historical velocity' : 'Cold-start prior baseline'}
                          >
                            {dev.capacitySource === 'empirical' ? 'velocity' : 'prior'}
                          </span>
                        </div>
                      </div>
                      <div className="h-1.5 w-full bg-muted rounded-full overflow-hidden">
                        <div 
                          className={`h-full rounded-full transition-all duration-500 ${
                            dev.workload > dev.capacity ? 'bg-red-500' : dev.workload >= dev.capacity * 0.85 ? 'bg-amber-500' : 'bg-primary'
                          }`}
                          style={{ width: `${Math.min((dev.workload / Math.max(1, dev.capacity)) * 100, 100)}%` }}
                        />
                      </div>
                    </div>

                    <div>
                      <div className="flex justify-between text-xs mb-1.5">
                        <span className="text-muted-foreground font-medium">AI Consistency Score</span>
                        <span className="font-bold text-foreground">{dev.performance}%</span>
                      </div>
                      <div className="h-1.5 w-full bg-muted rounded-full overflow-hidden">
                        <div 
                          className="h-full bg-gradient-to-r from-indigo-500 to-purple-500 rounded-full transition-all duration-500" 
                          style={{ width: `${dev.performance}%` }}
                        />
                      </div>
                    </div>
                  </div>
                </div>

                {/* Footer details indicator */}
                <div className="mt-5 pt-3 border-t border-border flex items-center justify-between text-xs text-muted-foreground">
                  <span className="flex items-center gap-1 font-mono text-[11px]">
                    <ShieldCheck className="w-3.5 h-3.5 text-emerald-500 dark:text-emerald-400" />
                    Level {dev.raw.experience_level || 2} • {Math.round(dev.capacity)} SP cap
                  </span>
                  <span className="font-semibold text-primary group-hover:underline flex items-center gap-1 text-[11px]">
                    View Metrics →
                  </span>
                </div>
              </div>
            </motion.div>
          </div>
        ))}
        </motion.div>
      )}

      {/* ========================================================================= */}
      {/* EXECUTIVE DEVELOPER INTELLIGENCE & EVALUATION PORTAL */}
      {/* ========================================================================= */}
      <AnimatePresence>
        {selectedDev && (
          <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-md">
            <motion.div
              initial={{ opacity: 0, scale: 0.96, y: 12 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.96, y: 12 }}
              className="bg-card text-card-foreground border border-border rounded-3xl w-full max-w-5xl overflow-hidden shadow-2xl max-h-[92vh] flex flex-col"
            >
              {/* Modal Header */}
              <div className="p-6 border-b border-border flex flex-col md:flex-row md:items-center justify-between gap-4 bg-muted/40">
                <div className="flex items-center space-x-4">
                  <div className="relative w-16 h-16 rounded-2xl overflow-hidden border-2 border-primary/40 ring-4 ring-primary/10 flex-shrink-0">
                    <Image src={selectedDev.avatar} alt={selectedDev.name} fill unoptimized className="object-cover" />
                  </div>
                  <div>
                    <div className="flex items-center gap-2 flex-wrap">
                      <h2 className="text-xl font-bold text-foreground capitalize">{selectedDev.name}</h2>
                      <span className="text-xs font-mono font-bold text-primary bg-primary/10 px-2 py-0.5 rounded-md border border-primary/20">
                        {selectedDev.raw.dev_id}
                      </span>
                      <span className={`text-[10px] font-bold uppercase px-2 py-0.5 rounded-full border ${
                        selectedDev.status === 'Available' ? 'bg-emerald-500/10 text-emerald-500 border-emerald-500/20' : 'bg-amber-500/10 text-amber-500 border-amber-500/20'
                      }`}>
                        {selectedDev.status}
                      </span>

                      {/* Burnout Risk Badge */}
                      {analytics?.burnoutRiskLevel && (
                        <span className={`text-[10px] font-bold uppercase px-2 py-0.5 rounded-full border flex items-center gap-1 ${
                          analytics.burnoutRiskLevel === 'HIGH'
                            ? 'bg-rose-500/10 text-rose-500 border-rose-500/30 animate-pulse'
                            : analytics.burnoutRiskLevel === 'MODERATE'
                            ? 'bg-amber-500/10 text-amber-500 border-amber-500/20'
                            : 'bg-emerald-500/10 text-emerald-500 border-emerald-500/20'
                        }`}>
                          <ShieldAlert className="w-3 h-3" />
                          {analytics.burnoutRiskLevel} Saturation Risk
                        </span>
                      )}
                    </div>

                    <div className="flex items-center gap-2.5 mt-1 text-xs text-muted-foreground flex-wrap">
                      <span className="font-semibold text-foreground">{selectedDev.role}</span>
                      <span>•</span>
                      <span>{selectedDev.raw.profile?.specialization || "Full-Stack Engineer"}</span>
                      {selectedDev.raw.profile?.email && (
                        <>
                          <span>•</span>
                          <span className="flex items-center gap-1 text-muted-foreground">
                            <Mail className="w-3 h-3 text-muted-foreground" />
                            {selectedDev.raw.profile.email}
                          </span>
                        </>
                      )}
                    </div>
                  </div>
                </div>

                <div className="flex items-center gap-2 self-end md:self-center">
                  <button
                    onClick={() => setSelectedDev(null)}
                    className="p-2 rounded-xl text-muted-foreground hover:text-foreground hover:bg-muted transition-colors cursor-pointer"
                  >
                    <X className="w-5 h-5" />
                  </button>
                </div>
              </div>

              {/* Navigation Tabs Inside Modal */}
              <div className="flex items-center gap-2 px-6 pt-3 border-b border-border bg-muted/20 overflow-x-auto">
                <button
                  onClick={() => setModalTab("overview")}
                  className={`px-4 py-2.5 text-xs font-bold rounded-t-xl transition-all border-b-2 flex items-center gap-2 cursor-pointer ${
                    modalTab === "overview"
                      ? "border-primary text-primary bg-background shadow-sm"
                      : "border-transparent text-muted-foreground hover:text-foreground hover:bg-muted/40"
                  }`}
                >
                  <BarChart3 className="w-3.5 h-3.5" />
                  <span>Performance & Velocity Graphs</span>
                </button>

                <button
                  onClick={() => setModalTab("sprints")}
                  className={`px-4 py-2.5 text-xs font-bold rounded-t-xl transition-all border-b-2 flex items-center gap-2 cursor-pointer ${
                    modalTab === "sprints"
                      ? "border-primary text-primary bg-background shadow-sm"
                      : "border-transparent text-muted-foreground hover:text-foreground hover:bg-muted/40"
                  }`}
                >
                  <Layers className="w-3.5 h-3.5" />
                  <span>Multi-Sprint Task Ledger ({analytics?.sprintHistory?.length || 0})</span>
                </button>

                <button
                  onClick={() => setModalTab("coaching")}
                  className={`px-4 py-2.5 text-xs font-bold rounded-t-xl transition-all border-b-2 flex items-center gap-2 cursor-pointer ${
                    modalTab === "coaching"
                      ? "border-primary text-primary bg-background shadow-sm"
                      : "border-transparent text-muted-foreground hover:text-foreground hover:bg-muted/40"
                  }`}
                >
                  <Sparkles className="w-3.5 h-3.5 text-amber-500" />
                  <span>AI Coaching & Talent Insights</span>
                </button>
              </div>

              {/* Modal Body */}
              <div className="p-6 overflow-y-auto space-y-6 flex-grow">
                {loadingAnalytics ? (
                  <div className="flex flex-col items-center justify-center py-24 text-muted-foreground space-y-3">
                    <Loader2 className="w-8 h-8 animate-spin text-primary" />
                    <p className="text-xs font-medium">Aggregating historical sprint telemetry and AI analytics...</p>
                  </div>
                ) : (
                  <>
                    {/* ========================================================================= */}
                    {/* TAB 1: OVERVIEW & INTERACTIVE VELOCITY CHART */}
                    {/* ========================================================================= */}
                    {modalTab === "overview" && (
                      <div className="space-y-6">
                        {/* 4 Delivery Health Gauges */}
                        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                          <div className="bg-muted/30 p-4 rounded-2xl border border-border">
                            <div className="flex items-center justify-between mb-1">
                              <span className="text-[11px] font-bold text-muted-foreground uppercase">Completion Rate</span>
                              <CheckCircle2 className="w-3.5 h-3.5 text-emerald-500" />
                            </div>
                            <div className="text-2xl font-bold text-foreground">
                              {analytics?.completionRate ?? 100}%
                            </div>
                            <div className="text-[10px] text-muted-foreground mt-1">
                              {analytics?.totalTasksCompleted ?? 0} of {analytics?.totalTasksAssigned ?? 0} tasks completed
                            </div>
                          </div>

                          <div className="bg-muted/30 p-4 rounded-2xl border border-border">
                            <div className="flex items-center justify-between mb-1">
                              <span className="text-[11px] font-bold text-muted-foreground uppercase">Delivery Stability</span>
                              <Zap className="w-3.5 h-3.5 text-yellow-500" />
                            </div>
                            <div className="text-2xl font-bold text-foreground">
                              {selectedDev.performance}%
                            </div>
                            <div className="text-[10px] text-muted-foreground mt-1">
                              Historical consistency index
                            </div>
                          </div>

                          <div className="bg-muted/30 p-4 rounded-2xl border border-border">
                            <div className="flex items-center justify-between mb-1">
                              <span className="text-[11px] font-bold text-muted-foreground uppercase">Avg Cycle Time</span>
                              <Clock className="w-3.5 h-3.5 text-blue-500" />
                            </div>
                            <div className="text-2xl font-bold text-foreground">
                              {analytics?.avgCycleTimeHours ?? 16} hrs
                            </div>
                            <div className="text-[10px] text-muted-foreground mt-1">
                              Average task turnaround speed
                            </div>
                          </div>

                          <div className="bg-muted/30 p-4 rounded-2xl border border-border">
                            <div className="flex items-center justify-between mb-1">
                              <span className="text-[11px] font-bold text-muted-foreground uppercase">Defect Density</span>
                              <AlertTriangle className="w-3.5 h-3.5 text-rose-500" />
                            </div>
                            <div className="text-2xl font-bold text-foreground">
                              {analytics?.defectRate ?? 0}
                            </div>
                            <div className="text-[10px] text-muted-foreground mt-1">
                              Defects per completed story
                            </div>
                          </div>
                        </div>

                        {/* Interactive SVG Velocity Chart */}
                        {analytics?.sprintHistory && analytics.sprintHistory.length > 0 && (() => {
                          const history = analytics.sprintHistory;
                          const maxSp = Math.max(16, ...history.map(s => Math.max(s.plannedSp, s.deliveredSp, analytics.effectiveCapacity))) + 4;
                          const width = 720;
                          const height = 220;
                          const padLeft = 45;
                          const padRight = 35;
                          const padTop = 25;
                          const padBottom = 35;
                          const chartW = width - padLeft - padRight;
                          const chartH = height - padTop - padBottom;
                          const step = chartW / history.length;
                          const capY = padTop + chartH - (analytics.effectiveCapacity / maxSp) * chartH;

                          const points = history.map((s, i) => {
                            const x = padLeft + (i + 0.5) * step;
                            const yPlanned = padTop + chartH - (s.plannedSp / maxSp) * chartH;
                            const yDelivered = padTop + chartH - (s.deliveredSp / maxSp) * chartH;
                            return { x, yPlanned, yDelivered, sprint: s };
                          });

                          const plannedPath = points.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x} ${p.yPlanned}`).join(' ');

                          return (
                            <div className="bg-card/70 border border-border rounded-2xl p-5 space-y-3 relative shadow-sm">
                              <div className="flex flex-wrap items-center justify-between gap-2">
                                <div>
                                  <h4 className="text-xs font-bold uppercase tracking-wider text-foreground flex items-center gap-1.5">
                                    <TrendingUp className="w-3.5 h-3.5 text-primary" />
                                    Sprint Velocity Progression & Commitment Accuracy
                                  </h4>
                                  <p className="text-[11px] text-muted-foreground mt-0.5">
                                    Delivered points (bars) vs. planned commitments (line) across sequential sprints.
                                  </p>
                                </div>

                                <div className="flex items-center gap-3 text-[10px] font-medium">
                                  <div className="flex items-center gap-1.5">
                                    <span className="w-2.5 h-2.5 rounded-sm bg-gradient-to-t from-cyan-600 to-blue-500 inline-block" />
                                    <span className="text-muted-foreground">Delivered SP</span>
                                  </div>
                                  <div className="flex items-center gap-1.5">
                                    <span className="w-3 h-0.5 bg-amber-400 inline-block" />
                                    <span className="text-muted-foreground">Planned SP Target</span>
                                  </div>
                                  <div className="flex items-center gap-1.5">
                                    <span className="w-3 border-b-2 border-dashed border-emerald-500 inline-block" />
                                    <span className="text-emerald-500 font-semibold">Capacity Target</span>
                                  </div>
                                </div>
                              </div>

                              <div className="w-full overflow-x-auto">
                                <svg viewBox={`0 0 ${width} ${height}`} className="w-full h-56 select-none">
                                  <defs>
                                    <linearGradient id="barGrad" x1="0" y1="0" x2="0" y2="1">
                                      <stop offset="0%" stopColor="#38bdf8" />
                                      <stop offset="100%" stopColor="#2563eb" />
                                    </linearGradient>
                                    <linearGradient id="plannedGlow" x1="0" y1="0" x2="1" y2="0">
                                      <stop offset="0%" stopColor="#f59e0b" />
                                      <stop offset="100%" stopColor="#f97316" />
                                    </linearGradient>
                                  </defs>

                                  {/* Grid Lines */}
                                  {[0, 5, 10, 15, 20, 25].filter(v => v <= maxSp).map(v => {
                                    const y = padTop + chartH - (v / maxSp) * chartH;
                                    return (
                                      <g key={v}>
                                        <line x1={padLeft} x2={width - padRight} y1={y} y2={y} stroke="currentColor" className="text-border" strokeDasharray="3 3" strokeWidth="1" />
                                        <text x={padLeft - 8} y={y + 3} textAnchor="end" className="text-[10px] fill-muted-foreground font-mono">{v}</text>
                                      </g>
                                    );
                                  })}

                                  {/* Capacity Reference Line */}
                                  <line x1={padLeft} x2={width - padRight} y1={capY} y2={capY} stroke="#10b981" strokeWidth="1.5" strokeDasharray="5 4" />
                                  <text x={width - padRight} y={capY - 6} textAnchor="end" fill="#10b981" className="text-[9px] font-mono font-bold">
                                    Cap: {Math.round(analytics.effectiveCapacity)} SP
                                  </text>

                                  {/* Delivered SP Bars */}
                                  {points.map((p) => {
                                    const barW = Math.min(26, Math.max(14, step * 0.45));
                                    const barH = (p.sprint.deliveredSp / maxSp) * chartH;
                                    const barY = padTop + chartH - barH;
                                    const isHovered = hoveredSprint?.sprintId === p.sprint.sprintId;

                                    return (
                                      <g 
                                        key={p.sprint.sprintId} 
                                        className="cursor-pointer"
                                        onMouseEnter={() => setHoveredSprint(p.sprint)}
                                        onMouseLeave={() => setHoveredSprint(null)}
                                      >
                                        <rect x={p.x - step/2} y={padTop} width={step} height={chartH + padBottom} fill="transparent" />
                                        {barH > 0 ? (
                                          <rect 
                                            x={p.x - barW / 2} 
                                            y={barY} 
                                            width={barW} 
                                            height={barH} 
                                            rx="4" 
                                            fill="url(#barGrad)"
                                            className={`transition-opacity duration-200 ${isHovered ? 'opacity-100 filter brightness-110' : 'opacity-85'}`}
                                          />
                                        ) : (
                                          <rect 
                                            x={p.x - barW / 2} 
                                            y={padTop + chartH - 4} 
                                            width={barW} 
                                            height={4} 
                                            rx="2" 
                                            fill="currentColor" 
                                            className="text-muted/60"
                                          />
                                        )}

                                        {p.sprint.deliveredSp > 0 && (
                                          <text x={p.x} y={barY - 5} textAnchor="middle" className="text-[10px] font-bold fill-foreground">
                                            {p.sprint.deliveredSp}
                                          </text>
                                        )}

                                        <text 
                                          x={p.x} 
                                          y={height - 12} 
                                          textAnchor="middle" 
                                          className={`text-[10px] font-mono ${isHovered ? 'fill-primary font-bold' : 'fill-muted-foreground'}`}
                                        >
                                          {p.sprint.sprintName.replace("Sprint ", "S")}
                                        </text>
                                      </g>
                                    );
                                  })}

                                  {/* Planned SP Line */}
                                  <path d={plannedPath} fill="none" stroke="url(#plannedGlow)" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
                                  {points.map((p) => (
                                    <circle
                                      key={`dot-${p.sprint.sprintId}`}
                                      cx={p.x}
                                      cy={p.yPlanned}
                                      r={hoveredSprint?.sprintId === p.sprint.sprintId ? "5" : "3.5"}
                                      className="fill-amber-400 stroke-background stroke-2 transition-all duration-150 cursor-pointer"
                                      onMouseEnter={() => setHoveredSprint(p.sprint)}
                                      onMouseLeave={() => setHoveredSprint(null)}
                                    />
                                  ))}
                                </svg>
                              </div>

                              {/* Hover Details Card */}
                              {hoveredSprint && (
                                <div className="p-3 bg-muted/70 border border-border rounded-xl flex flex-wrap items-center justify-between gap-3 text-xs animate-in fade-in duration-150">
                                  <div className="flex items-center gap-2">
                                    <span className="font-bold text-foreground">{hoveredSprint.sprintName}</span>
                                    <span className={`text-[10px] uppercase font-bold px-1.5 py-0.5 rounded ${
                                      hoveredSprint.status === 'COMPLETED' ? 'bg-emerald-500/10 text-emerald-500 border border-emerald-500/20' : 'bg-blue-500/10 text-blue-500 border border-blue-500/20'
                                    }`}>
                                      {hoveredSprint.status}
                                    </span>
                                  </div>
                                  <div className="flex items-center gap-4 text-xs font-mono flex-wrap">
                                    <span>Delivered: <strong className="text-cyan-400">{hoveredSprint.deliveredSp} SP</strong></span>
                                    <span>Planned: <strong className="text-amber-400">{hoveredSprint.plannedSp} SP</strong></span>
                                    <span>Tasks: <strong className="text-foreground">{hoveredSprint.tasksCount}</strong></span>
                                    <span>Defects: <strong className="text-rose-400">{hoveredSprint.defects}</strong></span>
                                    <span>Cycle Time: <strong className="text-foreground">{hoveredSprint.avgCycleTimeHours}h</strong></span>
                                  </div>
                                </div>
                              )}
                            </div>
                          );
                        })()}

                        {/* Domain Handling & Skill Specialization */}
                        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                          {/* Domain Experience Distribution */}
                          <div className="bg-muted/30 p-5 rounded-2xl border border-border space-y-3">
                            <div className="flex items-center justify-between">
                              <h4 className="text-xs font-bold uppercase tracking-wider text-foreground flex items-center gap-1.5">
                                <Target className="w-3.5 h-3.5 text-primary" />
                                Delivered Workload Domain Distribution
                              </h4>
                              <span className="text-[10px] text-muted-foreground font-mono">Weighted SP</span>
                            </div>

                            <div className="space-y-2.5">
                              {analytics?.domainDistribution && Object.entries(analytics.domainDistribution).map(([domain, pct]) => (
                                <div key={domain}>
                                  <div className="flex justify-between text-xs mb-1">
                                    <span className="font-semibold text-foreground flex items-center gap-1.5">
                                      {getSkillIcon(domain)}
                                      {domain} Domain
                                    </span>
                                    <span className="font-mono font-bold text-foreground">{pct}%</span>
                                  </div>
                                  <div className="h-2 w-full bg-muted rounded-full overflow-hidden">
                                    <div 
                                      className={`h-full rounded-full transition-all duration-500 ${
                                        domain === 'Frontend' ? 'bg-cyan-500' : domain === 'Backend' ? 'bg-emerald-500' : 'bg-purple-500'
                                      }`}
                                      style={{ width: `${pct}%` }}
                                    />
                                  </div>
                                </div>
                              ))}
                            </div>
                          </div>

                          {/* Skill Proficiencies */}
                          <div className="bg-muted/30 p-5 rounded-2xl border border-border space-y-3">
                            <div className="flex items-center justify-between">
                              <h4 className="text-xs font-bold uppercase tracking-wider text-foreground flex items-center gap-1.5">
                                <Layers className="w-3.5 h-3.5 text-primary" />
                                Technical Skill Proficiencies
                              </h4>
                              <span className="text-[10px] text-muted-foreground font-mono">Scale 1–10</span>
                            </div>

                            <div className="space-y-2.5">
                              <div>
                                <div className="flex justify-between text-xs mb-1">
                                  <span className="font-semibold text-foreground flex items-center gap-1.5">
                                    <Layout className="w-3.5 h-3.5 text-cyan-400" /> Frontend (UI/UX, CSS, Next.js)
                                  </span>
                                  <span className="font-mono font-bold">{selectedDev.raw.skill_frontend} / 10</span>
                                </div>
                                <div className="h-2 w-full bg-muted rounded-full overflow-hidden">
                                  <div className="h-full bg-cyan-500 rounded-full" style={{ width: `${selectedDev.raw.skill_frontend * 10}%` }} />
                                </div>
                              </div>

                              <div>
                                <div className="flex justify-between text-xs mb-1">
                                  <span className="font-semibold text-foreground flex items-center gap-1.5">
                                    <Server className="w-3.5 h-3.5 text-emerald-400" /> Backend (Spring, Python, APIs)
                                  </span>
                                  <span className="font-mono font-bold">{selectedDev.raw.skill_backend} / 10</span>
                                </div>
                                <div className="h-2 w-full bg-muted rounded-full overflow-hidden">
                                  <div className="h-full bg-emerald-500 rounded-full" style={{ width: `${selectedDev.raw.skill_backend * 10}%` }} />
                                </div>
                              </div>

                              <div>
                                <div className="flex justify-between text-xs mb-1">
                                  <span className="font-semibold text-foreground flex items-center gap-1.5">
                                    <Database className="w-3.5 h-3.5 text-purple-400" /> Database & SQL Architecture
                                  </span>
                                  <span className="font-mono font-bold">{selectedDev.raw.skill_db} / 10</span>
                                </div>
                                <div className="h-2 w-full bg-muted rounded-full overflow-hidden">
                                  <div className="h-full bg-purple-500 rounded-full" style={{ width: `${selectedDev.raw.skill_db * 10}%` }} />
                                </div>
                              </div>
                            </div>
                          </div>
                        </div>
                      </div>
                    )}

                    {/* ========================================================================= */}
                    {/* TAB 2: MULTI-SPRINT TASK LEDGER */}
                    {/* ========================================================================= */}
                    {modalTab === "sprints" && (
                      <div className="space-y-4">
                        <div className="flex items-center justify-between">
                          <div>
                            <h4 className="text-xs font-bold uppercase tracking-wider text-foreground">
                              Sprint-by-Sprint Execution Ledger
                            </h4>
                            <p className="text-[11px] text-muted-foreground mt-0.5">
                              Click any sprint to drill down into specific engineering tasks, complexity, and defects.
                            </p>
                          </div>
                          <span className="text-xs font-mono text-muted-foreground">
                            {analytics?.sprintHistory?.length || 0} Sprints Tracked
                          </span>
                        </div>

                        <div className="space-y-3">
                          {analytics?.sprintHistory?.map((sprint) => {
                            const isExpanded = expandedSprintId === sprint.sprintId;
                            return (
                              <div 
                                key={sprint.sprintId}
                                className="bg-muted/30 border border-border rounded-2xl overflow-hidden transition-all duration-200"
                              >
                                <div 
                                  onClick={() => setExpandedSprintId(isExpanded ? null : sprint.sprintId)}
                                  className="p-4 flex items-center justify-between cursor-pointer hover:bg-muted/50 transition-colors"
                                >
                                  <div className="flex items-center gap-3">
                                    <div className="w-9 h-9 rounded-xl bg-card border border-border flex items-center justify-center font-bold text-xs">
                                      {sprint.sprintName.replace("Sprint ", "S")}
                                    </div>
                                    <div>
                                      <div className="flex items-center gap-2">
                                        <h5 className="font-bold text-xs text-foreground">{sprint.sprintName}</h5>
                                        <span className={`text-[9px] uppercase font-bold px-1.5 py-0.5 rounded ${
                                          sprint.status === 'COMPLETED' ? 'bg-emerald-500/10 text-emerald-500 border border-emerald-500/20' : 'bg-blue-500/10 text-blue-500 border border-blue-500/20'
                                        }`}>
                                          {sprint.status}
                                        </span>
                                      </div>
                                      <div className="text-[11px] text-muted-foreground mt-0.5 font-mono">
                                        {sprint.tasksCount} task(s) • {sprint.defects} defect(s) • Cycle time: {sprint.avgCycleTimeHours}h
                                      </div>
                                    </div>
                                  </div>

                                  <div className="flex items-center gap-4">
                                    <div className="text-right">
                                      <div className="text-xs font-bold text-foreground">
                                        {sprint.deliveredSp} / {sprint.plannedSp} SPs
                                      </div>
                                      <div className="text-[10px] text-muted-foreground font-mono">
                                        {sprint.plannedSp > 0 ? `${Math.round((sprint.deliveredSp / sprint.plannedSp) * 100)}% accuracy` : "Planned"}
                                      </div>
                                    </div>
                                    {isExpanded ? <ChevronUp className="w-4 h-4 text-muted-foreground" /> : <ChevronDown className="w-4 h-4 text-muted-foreground" />}
                                  </div>
                                </div>

                                {/* Expanded Task List */}
                                {isExpanded && (
                                  <div className="p-4 pt-0 border-t border-border/50 bg-background/50 space-y-2">
                                    <div className="text-[10px] font-bold uppercase tracking-wider text-muted-foreground py-2">
                                      Tasks Handled in this Sprint:
                                    </div>
                                    {sprint.tasks.length === 0 ? (
                                      <p className="text-xs text-muted-foreground italic py-1">No task records found.</p>
                                    ) : (
                                      sprint.tasks.map((task) => (
                                        <div 
                                          key={task.id}
                                          className="p-3 bg-card border border-border rounded-xl flex items-center justify-between text-xs gap-3"
                                        >
                                          <div className="flex items-center gap-2.5">
                                            <CheckCircle2 className={`w-3.5 h-3.5 ${task.defects > 0 ? 'text-amber-500' : 'text-emerald-500'}`} />
                                            <div>
                                              <div className="font-semibold text-foreground line-clamp-1">{task.taskType}</div>
                                              <div className="flex items-center gap-2 text-[10px] text-muted-foreground font-mono mt-0.5">
                                                <span>Complexity: {task.complexity}/5</span>
                                                <span>•</span>
                                                <span>FE: {task.reqFrontend} | BE: {task.reqBackend} | DB: {task.reqDb}</span>
                                                {task.defects > 0 && (
                                                  <>
                                                    <span>•</span>
                                                    <span className="text-rose-400 font-bold">{task.defects} Defect Logged</span>
                                                  </>
                                                )}
                                              </div>
                                            </div>
                                          </div>

                                          <div className="flex items-center gap-2 flex-shrink-0">
                                            <span className="text-xs font-mono font-bold bg-muted px-2 py-0.5 rounded border border-border">
                                              {task.storyPoints} SPs
                                            </span>
                                            <span className="text-[10px] font-bold text-primary bg-primary/10 px-2 py-0.5 rounded border border-primary/20">
                                              {task.status}
                                            </span>
                                          </div>
                                        </div>
                                      ))
                                    )}
                                  </div>
                                )}
                              </div>
                            );
                          })}
                        </div>
                      </div>
                    )}

                    {/* ========================================================================= */}
                    {/* TAB 3: AI COACHING & TALENT EVALUATION */}
                    {/* ========================================================================= */}
                    {modalTab === "coaching" && (
                      <div className="space-y-6">
                        {/* Executive AI Summary */}
                        <div className="p-5 rounded-2xl bg-gradient-to-r from-primary/10 via-primary/5 to-transparent border border-primary/20 space-y-2">
                          <div className="flex items-center gap-2 text-primary font-bold text-xs uppercase tracking-wider">
                            <Sparkles className="w-4 h-4 text-amber-500" />
                            AI Talent Intelligence Evaluation Summary
                          </div>
                          <p className="text-xs text-foreground leading-relaxed">
                            {analytics?.aiExecutiveSummary || selectedDev.raw.profile?.bio || "No summary available."}
                          </p>
                        </div>

                        {/* Strengths & Growth Areas Grid */}
                        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                          {/* Validated Strengths */}
                          <div className="bg-muted/30 p-5 rounded-2xl border border-border space-y-3">
                            <div className="flex items-center gap-2 text-emerald-500 font-bold text-xs uppercase tracking-wider">
                              <CheckCircle2 className="w-4 h-4" />
                              Validated Technical Strengths
                            </div>
                            <div className="space-y-2">
                              {analytics?.strengths?.map((str, idx) => (
                                <div key={idx} className="p-3 bg-card border border-border rounded-xl text-xs text-foreground flex items-start gap-2.5">
                                  <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 mt-1.5 flex-shrink-0" />
                                  <span>{str}</span>
                                </div>
                              ))}
                            </div>
                          </div>

                          {/* Growth & Development Areas */}
                          <div className="bg-muted/30 p-5 rounded-2xl border border-border space-y-3">
                            <div className="flex items-center gap-2 text-amber-500 font-bold text-xs uppercase tracking-wider">
                              <AlertTriangle className="w-4 h-4" />
                              Growth & Capacity Advisory Points
                            </div>
                            <div className="space-y-2">
                              {analytics?.growthAreas?.map((area, idx) => (
                                <div key={idx} className="p-3 bg-card border border-border rounded-xl text-xs text-foreground flex items-start gap-2.5">
                                  <span className="w-1.5 h-1.5 rounded-full bg-amber-500 mt-1.5 flex-shrink-0" />
                                  <span>{area}</span>
                                </div>
                              ))}
                            </div>
                          </div>
                        </div>

                        {/* Actionable Manager Recommendation */}
                        <div className="p-5 rounded-2xl bg-muted/40 border border-border space-y-2">
                          <div className="flex items-center gap-2 text-primary font-bold text-xs uppercase tracking-wider">
                            <Target className="w-4 h-4" />
                            Recommended Engineering Manager Action
                          </div>
                          <p className="text-xs text-muted-foreground leading-relaxed">
                            {analytics?.coachingRecommendation}
                          </p>
                        </div>
                      </div>
                    )}
                  </>
                )}
              </div>

              {/* Modal Footer */}
              <div className="p-4 border-t border-border bg-muted/40 flex items-center justify-between">
                <span className="text-[11px] text-muted-foreground italic flex items-center gap-1.5">
                  <UserCheck className="w-3.5 h-3.5 text-primary" />
                  Owner / Admin Governance View: Real-time telemetry synchronized with delivery database.
                </span>

                <button
                  onClick={() => setSelectedDev(null)}
                  className="px-5 py-2 bg-muted hover:bg-muted/80 text-foreground rounded-xl text-xs font-bold transition-colors cursor-pointer border border-border"
                >
                  Close
                </button>
              </div>
            </motion.div>
          </div>
        )}
      </AnimatePresence>
    </div>
  );
}
