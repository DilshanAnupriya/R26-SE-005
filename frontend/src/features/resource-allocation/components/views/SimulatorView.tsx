"use client";

import { useState, useEffect } from "react";
import { getTokenStore } from "@/lib/api/token-store";
import { getActiveOrganization } from "@/lib/api/org-context";
import { useSession } from "@/components/session-provider";
import { cn } from "@/lib/utils";
import { motion, AnimatePresence } from "framer-motion";
import { 
  AlertTriangle, 
  TrendingUp,
  Clock,
  GripVertical,
  Plus,
  RefreshCw,
  Search,
  Wand2,
  ShieldAlert,
  Scale,
  CheckCircle2,
  Sparkles,
} from "lucide-react";

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

interface Task {
  id: number;
  title: string;
  storyPoints: number;
  type: "Frontend" | "Backend" | "Database";
  priority: "High" | "Medium" | "Low";
}

interface Developer {
  id: number;
  firstName: string;
  lastName: string;
  specialization: string;
  efficiency: number;
  capacity: number;
}

export default function SimulatorView() {
  const { user, hasPermission } = useSession();
  const roleCode = user?.activeRole?.toUpperCase() || "MEMBER";
  const canManageAllocations = 
    roleCode === "OWNER" || 
    roleCode === "ADMIN" || 
    roleCode === "MANAGER" || 
    hasPermission("sprint:manage");

  const [tasks, setTasks] = useState<Task[]>([]);
  const [developers, setDevelopers] = useState<Developer[]>([]);
  const [sprints, setSprints] = useState<any[]>([]);
  const [selectedSprintId, setSelectedSprintId] = useState<number | null>(null);
  const [assignments, setAssignments] = useState<Record<number, number>>({});
  const [simulationMetrics, setSimulationMetrics] = useState({
    totalAssignedPoints: 0,
    predictedVelocity: 0,
    healthScore: 0,
    giniIndex: 0,
    giniStatus: "OPTIMAL",
    overloadedDevelopersCount: 0,
    developerLoads: [] as any[]
  });
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState("");

  useEffect(() => {
    const fetchSprintsAndDevs = async () => {
      try {
        const [sprintsRes, devsRes] = await Promise.all([
          authFetch("http://localhost:8080/api/sprints"),
          authFetch("http://localhost:8080/api/developers")
        ]);
        
        const sprintsData = await sprintsRes.json();
        const devsData = await devsRes.json();
        
        if (Array.isArray(sprintsData)) {
          setSprints(sprintsData);
          if (sprintsData.length > 0 && !selectedSprintId) {
            setSelectedSprintId(sprintsData[0].id);
          }
        } else {
          setSprints([]);
        }

        if (Array.isArray(devsData)) {
          setDevelopers(devsData.map((d: any) => ({
            id: d.id,
            firstName: d.profile?.firstName || d.profile?.first_name || d.dev_id || "Developer",
            lastName: d.profile?.lastName || d.profile?.last_name || "",
            specialization: d.profile?.specialization || "Fullstack",
            efficiency: d.consistency || 1.0,
            capacity: (d.base_velocity && d.base_velocity > 0)
              ? Math.round(d.base_velocity * 10) / 10
              : (d.experience_level || 2) * 10
          })));
        } else {
          setDevelopers([]);
        }
      } catch (err) {
        console.error("Error fetching initial data:", err);
      } finally {
        setLoading(false);
      }
    };
    fetchSprintsAndDevs();
  }, []);

  useEffect(() => {
    if (!selectedSprintId) return;

    const fetchTasks = async () => {
      try {
        const tasksRes = await authFetch(`http://localhost:8080/api/tasks/sprint/${selectedSprintId}`);
        const tasksData = await tasksRes.json();
        
        if (Array.isArray(tasksData)) {
          setTasks(tasksData.map((t: any) => ({
            id: t.id,
            title: t.task_type || "Unknown Task",
            storyPoints: t.story_points || 0,
            type: (t.req_frontend || 0) > (t.req_backend || 0) ? "Frontend" : "Backend",
            priority: (t.task_complexity || 3) >= 4 ? "High" : ((t.task_complexity || 3) >= 3 ? "Medium" : "Low")
          })));

          const initialAssignments: Record<number, number> = {};
          tasksData.forEach((t: any) => {
            if (t.developer?.id) {
              initialAssignments[t.id] = t.developer.id;
            }
          });
          setAssignments(initialAssignments);
        } else {
          setTasks([]);
          setAssignments({});
        }
      } catch (err) {
        console.error("Error fetching tasks for sprint:", err);
      }
    };
    fetchTasks();
  }, [selectedSprintId]);

  useEffect(() => {
    const runSimulation = async () => {
      const assignmentList = Object.entries(assignments).map(([taskId, devId]) => ({
        developerId: devId,
        taskId: Number(taskId)
      }));

      if (assignmentList.length === 0) {
        setSimulationMetrics({
          totalAssignedPoints: 0,
          predictedVelocity: 0,
          healthScore: 0,
          giniIndex: 0,
          giniStatus: "OPTIMAL",
          overloadedDevelopersCount: 0,
          developerLoads: []
        });
        return;
      }

      try {
        const res = await authFetch("http://localhost:8080/api/simulator/calculate", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(assignmentList)
        });
        const data = await res.json();
        setSimulationMetrics({
          totalAssignedPoints: data.totalStoryPoints || 0,
          predictedVelocity: data.predictedVelocity || 0,
          healthScore: data.healthScore || 0,
          giniIndex: typeof data.giniIndex === "number" ? data.giniIndex : 0,
          giniStatus: data.giniStatus || "OPTIMAL",
          overloadedDevelopersCount: data.overloadedDevelopersCount || 0,
          developerLoads: Array.isArray(data.developerLoads) ? data.developerLoads : []
        });
      } catch (err) {
        console.error("Simulation error:", err);
      }
    };

    runSimulation();
  }, [assignments]);

  const handleApplyConfiguration = async () => {
    if (!canManageAllocations) return;
    const assignmentList = Object.entries(assignments).map(([taskId, devId]) => ({
      developerId: devId,
      taskId: Number(taskId)
    }));

    if (assignmentList.length === 0) return;

    setLoading(true);
    try {
      const res = await authFetch("http://localhost:8080/api/simulator/apply", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(assignmentList)
      });
      
      if (res.ok) {
        alert("Configuration applied successfully!");
      } else {
        throw new Error("Failed to apply configuration");
      }
    } catch (err) {
      console.error("Apply error:", err);
      alert("Error applying configuration. Please try again.");
    } finally {
      setLoading(false);
    }
  };

  const devLoad: Record<number, number> = {};
  Object.entries(assignments).forEach(([taskId, devId]) => {
    const task = tasks.find(t => t.id === Number(taskId));
    if (task) devLoad[devId] = (devLoad[devId] || 0) + task.storyPoints;
  });

  const { totalAssignedPoints, predictedVelocity, healthScore, giniIndex, giniStatus, overloadedDevelopersCount } = simulationMetrics;
  const overallHealth = healthScore;

  const handleAssign = (taskId: number, devId: number) => {
    if (!canManageAllocations) return;
    if (taskId === null) {
      const draggedId = Number(localStorage.getItem("draggedTaskId"));
      if (draggedId) {
        setAssignments(prev => ({ ...prev, [draggedId]: devId }));
        localStorage.removeItem("draggedTaskId");
      }
      return;
    }
    setAssignments(prev => ({ ...prev, [taskId]: devId }));
  };

  const onDragStart = (e: React.DragEvent, taskId: number) => {
    if (!canManageAllocations) return;
    e.dataTransfer.setData("taskId", taskId.toString());
    localStorage.setItem("draggedTaskId", taskId.toString());
  };

  const onDrop = (e: React.DragEvent, devId: number) => {
    e.preventDefault();
    if (!canManageAllocations) return;
    const taskId = e.dataTransfer.getData("taskId");
    if (taskId) {
      setAssignments(prev => ({ ...prev, [Number(taskId)]: devId }));
    } else {
      handleAssign(null as any, devId);
    }
  };

  const handleAutoOptimize = async () => {
    if (!canManageAllocations) return;
    setLoading(true);
    try {
      const payload = {
        tasks: tasks.filter(t => !assignments[t.id]),
        developerIds: developers.map(d => d.id),
        currentAssignments: assignments
      };

      const res = await authFetch("http://localhost:8080/api/simulator/auto-optimize", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
      });
      
      const newAssignments = await res.json();
      const updatedAssignments = { ...assignments };
      
      if (Array.isArray(newAssignments)) {
        newAssignments.forEach(a => {
          updatedAssignments[a.taskId] = a.developerId;
        });
      }
      
      setAssignments(updatedAssignments);
    } catch (err) {
      console.error("Auto-optimize error:", err);
    } finally {
      setLoading(false);
    }
  };

  const handleUnassign = (taskId: number) => {
    if (!canManageAllocations) return;
    setAssignments(prev => {
      const newMap = { ...prev };
      delete newMap[taskId];
      return newMap;
    });
  };

  if (loading) {
    return (
      <div className="w-full flex items-center justify-center py-20">
        <div className="flex flex-col items-center space-y-4">
          <RefreshCw className="w-10 h-10 text-primary animate-spin" />
          <p className="text-slate-400 font-medium">Initializing Simulator Environment...</p>
        </div>
      </div>
    );
  }

  return (
    <div className="w-full space-y-8">
      {/* Header */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-6 w-full">
        <div className="space-y-4">
          <div className="flex items-center space-x-4">
            <div className="glass px-4 py-2 rounded-xl border border-border flex items-center space-x-3">
              <span className="text-[10px] font-bold text-muted-foreground uppercase tracking-widest">Active Sprint:</span>
              <select 
                value={selectedSprintId || ""} 
                onChange={(e) => setSelectedSprintId(Number(e.target.value))}
                className="bg-transparent text-sm font-bold text-foreground focus:outline-none cursor-pointer"
              >
                {sprints.map(s => (
                  <option key={s.id} value={s.id} className="bg-card text-foreground">
                    {s.sprintId}
                  </option>
                ))}
              </select>
            </div>
            <p className="text-muted-foreground text-sm">Drag tasks to developers to see real-time impact.</p>
          </div>
        </div>

        <div className="flex items-center space-x-4">
          <button 
            onClick={handleAutoOptimize}
            disabled={!canManageAllocations}
            className={cn(
              "px-6 py-2 rounded-xl text-sm font-bold text-white transition-all flex items-center gap-2",
              canManageAllocations
                ? "bg-indigo-600 hover:bg-indigo-700 shadow-lg shadow-indigo-500/20 cursor-pointer"
                : "bg-indigo-600/40 opacity-60 cursor-not-allowed shadow-none"
            )}
            title={canManageAllocations ? "Auto-fill sprint assignments with AI" : "AI Smart Fill restricted to Owners, Admins, and Managers"}
          >
            <Wand2 className="w-4 h-4" />
            <span>AI Smart Fill</span>
          </button>
          <button 
            onClick={() => setAssignments({})}
            disabled={!canManageAllocations}
            className={cn(
              "glass px-4 py-2 rounded-xl text-sm font-semibold flex items-center space-x-2 text-foreground transition-colors border border-border",
              canManageAllocations ? "hover:bg-muted cursor-pointer" : "opacity-60 cursor-not-allowed"
            )}
            title={canManageAllocations ? "Reset assignments in simulation" : "Restricted to Managers & Admins"}
          >
            <RefreshCw className="w-4 h-4" />
            <span>Reset Simulation</span>
          </button>
          <button 
            onClick={handleApplyConfiguration}
            disabled={!canManageAllocations}
            className={cn(
              "px-6 py-2 rounded-xl text-sm font-bold text-white transition-all",
              canManageAllocations
                ? "bg-primary hover:bg-primary/90 shadow-lg shadow-primary/20 cursor-pointer"
                : "bg-primary/40 opacity-60 cursor-not-allowed shadow-none"
            )}
            title={canManageAllocations ? "Apply simulated assignments to live sprint" : "Applying simulated assignments is restricted to Owners, Admins, and Managers"}
          >
            Apply Configuration
          </button>
        </div>
      </div>

      {/* Main Simulation Area */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 h-[calc(100vh-250px)]">
        
        {/* Left Sidebar: Stats & Health */}
        <div className="lg:col-span-3 space-y-4 overflow-y-auto pr-1">
          <div className="glass p-5 rounded-2xl border border-border space-y-4">
            <div className="flex items-center justify-between pb-2 border-b border-border">
              <h3 className="text-xs font-bold text-muted-foreground uppercase tracking-wider flex items-center gap-1.5">
                <Sparkles className="w-3.5 h-3.5 text-primary" />
                Sprint Health
              </h3>
              <span className="text-[10px] font-mono font-bold text-primary bg-primary/10 px-2 py-0.5 rounded-full border border-primary/20">
                {Object.keys(assignments).length} Assigned
              </span>
            </div>
            
            {/* Circular Match Gauge */}
            <div className="text-center py-1">
              <div className="relative inline-flex items-center justify-center">
                <svg className="w-28 h-28 transform -rotate-90" viewBox="0 0 112 112">
                  <circle className="text-muted/30 stroke-current" strokeWidth="7" fill="transparent" r="48" cx="56" cy="56" />
                  <circle 
                    className="text-primary transition-all duration-500" 
                    strokeWidth="7" 
                    strokeDasharray={301.6}
                    strokeDashoffset={301.6 - (301.6 * Math.min(overallHealth, 100)) / 100}
                    strokeLinecap="round" 
                    stroke="currentColor" 
                    fill="transparent" 
                    r="48" cx="56" cy="56" 
                  />
                </svg>
                <div className="absolute flex flex-col items-center">
                  <span className="text-xl font-bold text-foreground font-mono">{Math.round(overallHealth || 0)}%</span>
                  <span className="text-[9px] text-muted-foreground font-semibold uppercase tracking-tight">Match Score</span>
                </div>
              </div>
            </div>

            {/* Quick Stats: 2-Column Grid */}
            <div className="grid grid-cols-2 gap-2">
              <div className="bg-card/70 p-3 rounded-xl border border-border">
                <div className="flex justify-between items-center mb-0.5">
                  <span className="text-[10px] text-muted-foreground">Pred. Velocity</span>
                  <TrendingUp className="w-3 h-3 text-emerald-400" />
                </div>
                <div className="text-sm font-bold text-foreground font-mono">
                  {(predictedVelocity || 0).toFixed(1)} <span className="text-[10px] text-muted-foreground font-normal">SP</span>
                </div>
              </div>
              <div className="bg-card/70 p-3 rounded-xl border border-border">
                <div className="flex justify-between items-center mb-0.5">
                  <span className="text-[10px] text-muted-foreground">Total Points</span>
                  <Clock className="w-3 h-3 text-blue-400" />
                </div>
                <div className="text-sm font-bold text-foreground font-mono">
                  {totalAssignedPoints} <span className="text-[10px] text-muted-foreground font-normal">SP</span>
                </div>
              </div>
            </div>

            {/* Gini Workload Fairness Card */}
            <div className="bg-card/70 p-3.5 rounded-xl border border-border space-y-2">
              <div className="flex justify-between items-center">
                <div className="flex items-center gap-1.5 text-xs text-foreground font-semibold">
                  <Scale className="w-3.5 h-3.5 text-indigo-400" />
                  <span>Gini Fairness</span>
                </div>
                <span className={cn(
                  "text-[10px] font-bold px-2 py-0.5 rounded-full border",
                  giniStatus === "OPTIMAL" 
                    ? "bg-emerald-500/10 text-emerald-400 border-emerald-500/20"
                    : (giniStatus === "MODERATE"
                      ? "bg-amber-500/10 text-amber-400 border-amber-500/20"
                      : "bg-rose-500/10 text-rose-400 border-rose-500/20")
                )}>
                  {giniStatus === "OPTIMAL" ? "Optimal Equity" : (giniStatus === "MODERATE" ? "Moderate" : "High Disparity")}
                </span>
              </div>

              <div className="flex items-baseline justify-between">
                <div className="text-lg font-bold text-foreground font-mono">
                  {giniIndex.toFixed(3)}
                </div>
                <span className="text-[10px] text-muted-foreground">
                  Target &le; 0.280
                </span>
              </div>

              {/* Horizontal Gini Visual Meter */}
              <div className="space-y-1">
                <div className="relative h-2 w-full bg-slate-800 rounded-full overflow-hidden">
                  <div 
                    className={cn(
                      "h-full rounded-full transition-all duration-500",
                      giniStatus === "OPTIMAL"
                        ? "bg-gradient-to-r from-emerald-500 to-teal-400"
                        : (giniStatus === "MODERATE"
                          ? "bg-gradient-to-r from-amber-500 to-yellow-400"
                          : "bg-gradient-to-r from-rose-500 to-red-500")
                    )}
                    style={{ width: `${Math.min(100, Math.max(6, giniIndex * 100))}%` }}
                  />
                  {/* Target boundary line at 28% */}
                  <div 
                    className="absolute top-0 bottom-0 w-0.5 bg-white/60 pointer-events-none"
                    style={{ left: "28%" }}
                    title="Target: 0.280"
                  />
                </div>
                <div className="grid grid-cols-3 text-[9px] text-muted-foreground font-mono">
                  <span className="text-left">0.00 (Fair)</span>
                  <span className="text-center font-sans font-semibold text-indigo-400/90">&le; 0.28</span>
                  <span className="text-right">1.00 (Max)</span>
                </div>
              </div>
              <p className="text-[10px] text-muted-foreground leading-snug">
                Lorenz Gini index evaluates story point balance across team members.
              </p>
            </div>
          </div>

          {/* Simulation Alerts Card */}
          <div className="glass p-4 rounded-2xl border border-amber-500/30 bg-amber-500/5 space-y-2.5">
            <div className="flex items-center justify-between text-amber-500 dark:text-amber-400">
              <div className="flex items-center space-x-1.5">
                <AlertTriangle className="w-4 h-4" />
                <h4 className="font-bold text-xs uppercase tracking-wider">Simulation Alerts</h4>
              </div>
              {overloadedDevelopersCount > 0 && (
                <span className="text-[10px] font-bold px-1.5 py-0.5 rounded bg-rose-500/20 text-rose-400 border border-rose-500/30">
                  {overloadedDevelopersCount} Overloaded
                </span>
              )}
            </div>
            <div className="space-y-2 max-h-48 overflow-y-auto pr-1">
              {Object.entries(devLoad).map(([devId, load]) => {
                const dev = developers.find(d => d.id === Number(devId));
                if (dev && load > dev.capacity) {
                  const excess = Math.round((load - dev.capacity) * 10) / 10;
                  const devCap = Math.round(dev.capacity * 10) / 10;
                  const formattedLoad = Math.round(load * 10) / 10;
                  return (
                    <div key={devId} className="text-xs text-muted-foreground border-l-2 border-red-500 pl-2.5 py-1 bg-red-500/5 rounded-r">
                      <span className="font-bold text-red-500 dark:text-red-400">{dev.firstName} {dev.lastName}</span> is overloaded ({formattedLoad}/{devCap} SPs, +{excess} SP excess).
                    </div>
                  );
                }
                return null;
              })}
              {Object.keys(assignments).length === 0 ? (
                <p className="text-xs text-muted-foreground italic">Drag backlog tasks onto developers to simulate capacity and workload equity...</p>
              ) : overloadedDevelopersCount === 0 && (
                <div className="text-xs text-emerald-400 flex items-center gap-1.5 py-1">
                  <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400 shrink-0" />
                  <span>All assignments are within safe capacities.</span>
                </div>
              )}
            </div>
          </div>
        </div>

        {/* Center: Developer Drop Zones */}
        <div className="lg:col-span-6 space-y-6 overflow-y-auto pr-2 scrollbar-hide pb-10">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {developers.map((dev) => {
              const load = devLoad[dev.id] || 0;
              const capacity = dev.capacity > 0 ? dev.capacity : 20;
              const formattedCapacity = Math.round(capacity * 10) / 10;
              const formattedLoad = Math.round(load * 10) / 10;
              const utilizationPct = Math.round((load / capacity) * 100);
              const isOverloaded = load > capacity;
              const excess = Math.round((load - capacity) * 10) / 10;

              return (
                <div 
                  key={dev.id} 
                  onDragOver={(e) => e.preventDefault()}
                  onDrop={(e) => onDrop(e as any, dev.id)}
                  className={`glass p-5 rounded-3xl border ${isOverloaded ? 'border-red-500/50 shadow-lg shadow-red-500/10' : 'border-border'} min-h-[220px] flex flex-col transition-all hover:bg-muted/30`}
                >
                  <div className="flex items-center justify-between mb-2 pb-3 border-b border-border">
                    <div className="flex items-center space-x-3">
                      <div className="w-10 h-10 rounded-full bg-muted flex items-center justify-center text-primary font-bold">
                        {dev.firstName[0]}{dev.lastName[0]}
                      </div>
                      <div>
                        <div className="text-sm font-bold text-foreground">{dev.firstName} {dev.lastName}</div>
                        <div className="text-[10px] text-muted-foreground font-bold uppercase tracking-widest">{dev.specialization}</div>
                      </div>
                    </div>
                    <div className="text-right">
                      <div className={`text-xs font-bold ${isOverloaded ? 'text-red-500' : (utilizationPct >= 85 ? 'text-amber-500' : 'text-emerald-500 dark:text-emerald-400')}`}>
                        {formattedLoad} / {formattedCapacity} SPs
                      </div>
                      <div className="text-[10px] text-muted-foreground font-bold uppercase">{utilizationPct}% Utilized</div>
                    </div>
                  </div>

                  {/* Capacity Progress Bar with Micro-animation */}
                  <div className="mb-3 space-y-1">
                    <div className="h-2 w-full bg-slate-800 rounded-full overflow-hidden">
                      <motion.div
                        className={cn(
                          "h-full rounded-full transition-all duration-300",
                          isOverloaded
                            ? "bg-gradient-to-r from-amber-500 to-rose-500"
                            : (utilizationPct >= 85
                              ? "bg-gradient-to-r from-yellow-500 to-amber-500"
                              : "bg-gradient-to-r from-emerald-500 to-teal-400")
                        )}
                        initial={{ width: 0 }}
                        animate={{ width: `${Math.min(100, utilizationPct)}%` }}
                      />
                    </div>
                    {isOverloaded && (
                      <div className="text-[10px] text-rose-400 font-semibold flex items-center gap-1">
                        <AlertTriangle className="w-3 h-3 text-rose-400 shrink-0" />
                        <span>+{excess} SP Over Capacity (Burnout Hazard)</span>
                      </div>
                    )}
                  </div>

                <div className="flex-grow space-y-2">
                  {Object.entries(assignments)
                    .filter(([_, dId]) => dId === dev.id)
                    .map(([tId, _]) => {
                      const task = tasks.find(t => t.id === Number(tId));
                      if (!task) return null;
                      return (
                        <motion.div 
                          layoutId={`task-${task.id}`}
                          key={task.id} 
                          className="bg-card p-3 rounded-xl border border-border flex items-center justify-between group shadow-sm"
                        >
                          <div className="flex items-center space-x-3">
                            <GripVertical className="w-4 h-4 text-muted-foreground" />
                            <div>
                              <div className="text-xs font-medium text-foreground line-clamp-1">{task.title}</div>
                              <div className="flex items-center gap-2 mt-0.5">
                                <span className="text-[10px] text-muted-foreground font-bold uppercase">{task.type}</span>
                                <span className={`text-[9px] px-1.5 py-0.5 rounded font-bold uppercase border ${
                                  task.priority === 'High' 
                                    ? 'bg-red-500/10 text-red-500 dark:text-red-400 border-red-500/20' 
                                    : task.priority === 'Medium'
                                    ? 'bg-amber-500/10 text-amber-500 dark:text-amber-400 border-amber-500/20'
                                    : 'bg-emerald-500/10 text-emerald-500 dark:text-emerald-400 border-emerald-500/20'
                                }`}>
                                  {task.priority}
                                </span>
                              </div>
                            </div>
                          </div>
                          {canManageAllocations && (
                            <button 
                              onClick={() => handleUnassign(task.id)}
                              className="opacity-0 group-hover:opacity-100 transition-opacity p-1 text-muted-foreground hover:text-red-500 cursor-pointer"
                              title="Unassign task"
                            >
                              <Plus className="w-4 h-4 rotate-45" />
                            </button>
                          )}
                        </motion.div>
                      );
                    })}
                  
                  {canManageAllocations && (
                    <div className="h-12 border-2 border-dashed border-border rounded-xl flex items-center justify-center text-muted-foreground text-xs font-medium italic">
                      Drop to assign
                    </div>
                  )}
                </div>
              </div>
            );
          })}
          </div>
        </div>

        {/* Right Column: Backlog List */}
        <div className="lg:col-span-3 h-full overflow-hidden">
          <div className="glass p-6 rounded-3xl border border-border h-full flex flex-col">
            <div className="flex items-center justify-between mb-6">
              <h3 className="text-sm font-bold text-foreground uppercase tracking-wider">Backlog Pool</h3>
              <span className="bg-primary/20 text-primary text-[10px] px-2 py-0.5 rounded-full font-bold">
                {tasks.filter(t => !assignments[t.id]).length} Available
              </span>
            </div>

            <div className="relative mb-4">
              <Search className="absolute left-3 top-2.5 w-4 h-4 text-muted-foreground" />
              <input 
                type="text" 
                placeholder="Filter tasks..."
                className="w-full bg-card border border-border rounded-xl pl-10 pr-4 py-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-primary/50"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
              />
            </div>

            <div className="flex-grow overflow-y-auto space-y-3 pr-2 scrollbar-hide">
              <AnimatePresence>
                {tasks
                  .filter(t => !assignments[t.id])
                  .filter(t => {
                    const title = t.title || (t as any).task_type || "";
                    return title.toLowerCase().includes(searchQuery.toLowerCase());
                  })
                  .map((task) => (
                    <motion.div
                      layoutId={`task-${task.id}`}
                      key={task.id}
                      draggable={canManageAllocations}
                      onDragStart={(e) => onDragStart(e as any, task.id)}
                      initial={{ opacity: 0, x: 20 }}
                      animate={{ opacity: 1, x: 0 }}
                      exit={{ opacity: 0, scale: 0.95 }}
                      className={cn(
                        "bg-card p-4 rounded-2xl border border-border transition-all group shadow-sm",
                        canManageAllocations ? "hover:border-primary/50 cursor-grab active:cursor-grabbing" : "cursor-default"
                      )}
                    >
                      <div className="flex justify-between items-start mb-2">
                        <span className={`text-[10px] px-2 py-0.5 rounded-full font-bold uppercase border ${
                          task.priority === 'High' 
                            ? 'bg-red-500/10 text-red-500 dark:text-red-400 border-red-500/20' 
                            : task.priority === 'Medium'
                            ? 'bg-amber-500/10 text-amber-500 dark:text-amber-400 border-amber-500/20'
                            : 'bg-emerald-500/10 text-emerald-500 dark:text-emerald-400 border-emerald-500/20'
                        }`}>
                          {task.priority} Priority
                        </span>
                        <div className="text-xs font-bold text-foreground bg-muted px-2 py-0.5 rounded-lg border border-border">
                          {task.storyPoints} SPs
                        </div>
                      </div>
                      <h4 className="text-xs font-bold text-foreground mb-3 leading-relaxed">
                        {task.title || (task as any).task_type || "Untitled Task"}
                      </h4>
                      
                      <div className="flex items-center justify-between pt-3 border-t border-border">
                        <span className="text-[10px] text-muted-foreground font-bold uppercase tracking-wider">{task.type}</span>
                        {canManageAllocations && (
                          <div className="flex -space-x-1 overflow-hidden">
                            {developers.slice(0, 4).map(dev => (
                              <button
                                key={dev.id}
                                onClick={() => handleAssign(task.id, dev.id)}
                                className="w-6 h-6 rounded-full bg-muted border border-border flex items-center justify-center text-[8px] font-bold text-foreground hover:bg-primary hover:text-white transition-all ring-2 ring-card"
                                title={`Assign to ${dev.firstName}`}
                              >
                                {dev.firstName[0]}
                              </button>
                            ))}
                            {developers.length > 4 && (
                              <div className="w-6 h-6 rounded-full bg-muted border border-border flex items-center justify-center text-[8px] font-bold text-muted-foreground ring-2 ring-card">
                                +{developers.length - 4}
                              </div>
                            )}
                          </div>
                        )}
                      </div>
                    </motion.div>
                  ))}
              </AnimatePresence>
            </div>
          </div>
        </div>

      </div>
    </div>
  );
}
