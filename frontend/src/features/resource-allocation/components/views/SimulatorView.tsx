"use client";

import { useState, useEffect } from "react";
import { getTokenStore } from "@/lib/api/token-store";
import { getActiveOrganization } from "@/lib/api/org-context";
import { motion, AnimatePresence } from "framer-motion";
import { 
  AlertTriangle, 
  TrendingUp,
  Clock,
  GripVertical,
  Plus,
  RefreshCw,
  Search,
  Wand2
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
  const [tasks, setTasks] = useState<Task[]>([]);
  const [developers, setDevelopers] = useState<Developer[]>([]);
  const [sprints, setSprints] = useState<any[]>([]);
  const [selectedSprintId, setSelectedSprintId] = useState<number | null>(null);
  const [assignments, setAssignments] = useState<Record<number, number>>({});
  const [simulationMetrics, setSimulationMetrics] = useState({
    totalAssignedPoints: 0,
    predictedVelocity: 0,
    healthScore: 0
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
            capacity: (d.experience_level || 2) * 10 
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
          const unassignedTasks = tasksData.filter((t: any) => t.developer === null);
          
          setTasks(unassignedTasks.map((t: any) => ({
            id: t.id,
            title: t.task_type || "Unknown Task",
            storyPoints: t.story_points || 0,
            type: t.req_frontend > t.req_backend ? "Frontend" : "Backend",
            priority: t.task_complexity > 4 ? "High" : (t.task_complexity > 2 ? "Medium" : "Low")
          })));
        } else {
          setTasks([]);
        }
        
        setAssignments({});
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
        setSimulationMetrics({ totalAssignedPoints: 0, predictedVelocity: 0, healthScore: 0 });
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
          healthScore: data.healthScore || 0
        });
      } catch (err) {
        console.error("Simulation error:", err);
      }
    };

    runSimulation();
  }, [assignments]);

  const handleApplyConfiguration = async () => {
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

  const { totalAssignedPoints, predictedVelocity, healthScore } = simulationMetrics;
  const overallHealth = healthScore;

  const handleAssign = (taskId: number, devId: number) => {
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
    e.dataTransfer.setData("taskId", taskId.toString());
    localStorage.setItem("draggedTaskId", taskId.toString());
  };

  const onDrop = (e: React.DragEvent, devId: number) => {
    e.preventDefault();
    const taskId = e.dataTransfer.getData("taskId");
    if (taskId) {
      setAssignments(prev => ({ ...prev, [Number(taskId)]: devId }));
    } else {
      handleAssign(null as any, devId);
    }
  };

  const handleAutoOptimize = async () => {
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
            <div className="glass px-4 py-2 rounded-xl border border-card-border flex items-center space-x-3">
              <span className="text-[10px] font-bold text-slate-500 uppercase tracking-widest">Active Sprint:</span>
              <select 
                value={selectedSprintId || ""} 
                onChange={(e) => setSelectedSprintId(Number(e.target.value))}
                className="bg-transparent text-sm font-bold text-white focus:outline-none cursor-pointer"
              >
                {sprints.map(s => (
                  <option key={s.id} value={s.id} className="bg-slate-900 text-white">
                    {s.sprintId}
                  </option>
                ))}
              </select>
            </div>
            <p className="text-slate-400 text-sm">Drag tasks to developers to see real-time impact.</p>
          </div>
        </div>

        <div className="flex items-center space-x-4">
          <button 
            onClick={handleAutoOptimize}
            className="bg-indigo-600 hover:bg-indigo-700 px-6 py-2 rounded-xl text-sm font-bold text-white transition-all shadow-lg shadow-indigo-500/20 flex items-center gap-2"
          >
            <Wand2 className="w-4 h-4" />
            <span>AI Smart Fill</span>
          </button>
          <button 
            onClick={() => setAssignments({})}
            className="glass px-4 py-2 rounded-xl text-sm font-semibold flex items-center space-x-2 hover:bg-slate-800 transition-colors"
          >
            <RefreshCw className="w-4 h-4" />
            <span>Reset Simulation</span>
          </button>
          <button 
            onClick={handleApplyConfiguration}
            className="bg-primary hover:bg-primary-dark px-6 py-2 rounded-xl text-sm font-bold text-white transition-all shadow-lg shadow-primary/20"
          >
            Apply Configuration
          </button>
        </div>
      </div>

      {/* Main Simulation Area */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 h-[calc(100vh-250px)]">
        
        {/* Left Sidebar: Stats & Health */}
        <div className="lg:col-span-3 space-y-6 overflow-y-auto pr-2 scrollbar-hide">
          <div className="glass p-6 rounded-3xl border border-card-border sticky top-0">
            <h3 className="text-sm font-bold text-slate-500 uppercase tracking-wider mb-6">Sprint Health</h3>
            
            <div className="space-y-6">
              <div className="text-center">
                <div className="relative inline-flex items-center justify-center">
                  <svg className="w-32 h-32">
                    <circle className="text-slate-800" strokeWidth="8" stroke="currentColor" fill="transparent" r="58" cx="64" cy="64" />
                    <circle 
                      className="text-primary transition-all duration-500" 
                      strokeWidth="8" 
                      strokeDasharray={364.4}
                      strokeDashoffset={364.4 - (364.4 * Math.min(overallHealth, 100)) / 100}
                      strokeLinecap="round" 
                      stroke="currentColor" 
                      fill="transparent" 
                      r="58" cx="64" cy="64" 
                    />
                  </svg>
                  <div className="absolute flex flex-col items-center">
                    <span className="text-2xl font-bold text-white">{Math.round(overallHealth || 0)}%</span>
                    <span className="text-[10px] text-slate-500 font-bold uppercase tracking-tight">Match Score</span>
                  </div>
                </div>
              </div>

              <div className="grid grid-cols-1 gap-4">
                <div className="bg-slate-900/50 p-4 rounded-2xl border border-slate-800/50">
                  <div className="flex justify-between items-center mb-1">
                    <span className="text-xs text-slate-400">Predicted Velocity</span>
                    <TrendingUp className="w-3 h-3 text-emerald-500" />
                  </div>
                  <div className="text-xl font-bold text-white">{(predictedVelocity || 0).toFixed(1)} <span className="text-xs text-slate-500">SPs</span></div>
                </div>
                <div className="bg-slate-900/50 p-4 rounded-2xl border border-slate-800/50">
                  <div className="flex justify-between items-center mb-1">
                    <span className="text-xs text-slate-400">Total Points</span>
                    <Clock className="w-3 h-3 text-blue-500" />
                  </div>
                  <div className="text-xl font-bold text-white">{totalAssignedPoints} <span className="text-xs text-slate-500">SPs</span></div>
                </div>
              </div>
            </div>
          </div>

          <div className="glass p-6 rounded-3xl border border-card-border bg-yellow-500/5">
            <div className="flex items-center space-x-2 text-yellow-500 mb-4">
              <AlertTriangle className="w-5 h-5" />
              <h4 className="font-bold text-sm uppercase italic">Simulation Alerts</h4>
            </div>
            <div className="space-y-3">
              {Object.entries(devLoad).map(([devId, load]) => {
                const dev = developers.find(d => d.id === Number(devId));
                if (dev && load > dev.capacity) {
                  return (
                    <div key={devId} className="text-xs text-slate-400 border-l-2 border-red-500 pl-3 py-1">
                      <span className="font-bold text-red-400">{dev.firstName}</span> is overloaded ({load}/{dev.capacity} SPs).
                    </div>
                  );
                }
                return null;
              })}
              {Object.keys(assignments).length === 0 && (
                <p className="text-xs text-slate-500 italic">Assign tasks to see impact analysis...</p>
              )}
            </div>
          </div>
        </div>

        {/* Center: Developer Drop Zones */}
        <div className="lg:col-span-6 space-y-6 overflow-y-auto pr-2 scrollbar-hide pb-10">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {developers.map((dev) => (
              <div 
                key={dev.id} 
                onDragOver={(e) => e.preventDefault()}
                onDrop={(e) => onDrop(e as any, dev.id)}
                className={`glass p-5 rounded-3xl border ${devLoad[dev.id] > dev.capacity ? 'border-red-500/30' : 'border-card-border'} min-h-[200px] flex flex-col transition-all hover:bg-slate-900/40`}
              >
                <div className="flex items-center justify-between mb-4 pb-3 border-b border-slate-800/50">
                  <div className="flex items-center space-x-3">
                    <div className="w-10 h-10 rounded-full bg-slate-800 flex items-center justify-center text-primary font-bold">
                      {dev.firstName[0]}{dev.lastName[0]}
                    </div>
                    <div>
                      <div className="text-sm font-bold text-white">{dev.firstName} {dev.lastName}</div>
                      <div className="text-[10px] text-slate-500 font-bold uppercase tracking-widest">{dev.specialization}</div>
                    </div>
                  </div>
                  <div className="text-right">
                    <div className={`text-xs font-bold ${devLoad[dev.id] > dev.capacity ? 'text-red-500' : 'text-emerald-500'}`}>
                      {devLoad[dev.id] || 0} / {dev.capacity} SPs
                    </div>
                    <div className="text-[10px] text-slate-600 font-bold uppercase">Load</div>
                  </div>
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
                          className="bg-slate-900/80 p-3 rounded-xl border border-slate-800 flex items-center justify-between group"
                        >
                          <div className="flex items-center space-x-3">
                            <GripVertical className="w-4 h-4 text-slate-700" />
                            <div>
                              <div className="text-xs font-medium text-white line-clamp-1">{task.title}</div>
                              <div className="text-[10px] text-slate-500 font-bold uppercase">{task.type}</div>
                            </div>
                          </div>
                          <button 
                            onClick={() => handleUnassign(task.id)}
                            className="opacity-0 group-hover:opacity-100 transition-opacity p-1 hover:text-red-400"
                          >
                            <Plus className="w-4 h-4 rotate-45" />
                          </button>
                        </motion.div>
                      );
                    })}
                  
                  <div className="h-12 border-2 border-dashed border-slate-800/50 rounded-xl flex items-center justify-center text-slate-700 text-xs font-medium italic">
                    Drop to assign
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* Right Column: Backlog List */}
        <div className="lg:col-span-3 h-full overflow-hidden">
          <div className="glass p-6 rounded-3xl border border-card-border h-full flex flex-col">
            <div className="flex items-center justify-between mb-6">
              <h3 className="text-sm font-bold text-white uppercase tracking-wider">Backlog Pool</h3>
              <span className="bg-primary/20 text-primary text-[10px] px-2 py-0.5 rounded-full font-bold">
                {tasks.filter(t => !assignments[t.id]).length} Available
              </span>
            </div>

            <div className="relative mb-4">
              <Search className="absolute left-3 top-2.5 w-4 h-4 text-slate-500" />
              <input 
                type="text" 
                placeholder="Filter tasks..."
                className="w-full bg-slate-900 border border-slate-800 rounded-xl pl-10 pr-4 py-2 text-xs text-white focus:outline-none focus:ring-1 focus:ring-primary/50"
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
                      draggable
                      onDragStart={(e) => onDragStart(e as any, task.id)}
                      initial={{ opacity: 0, x: 20 }}
                      animate={{ opacity: 1, x: 0 }}
                      exit={{ opacity: 0, scale: 0.95 }}
                      className="bg-slate-950 p-4 rounded-2xl border border-slate-800 hover:border-primary/40 transition-all cursor-grab active:cursor-grabbing group"
                    >
                      <div className="flex justify-between items-start mb-2">
                        <span className={`text-[10px] px-2 py-0.5 rounded-full font-bold uppercase ${
                          task.priority === 'High' ? 'bg-red-500/10 text-red-500' : 'bg-blue-500/10 text-blue-500'
                        }`}>
                          {task.priority}
                        </span>
                        <div className="text-xs font-bold text-white bg-slate-800 px-2 py-0.5 rounded-lg">
                          {task.storyPoints} SPs
                        </div>
                      </div>
                      <h4 className="text-xs font-bold text-slate-200 mb-3 leading-relaxed">
                        {task.title || (task as any).task_type || "Untitled Task"}
                      </h4>
                      
                      <div className="flex items-center justify-between pt-3 border-t border-slate-900">
                        <span className="text-[10px] text-slate-500 font-bold uppercase tracking-wider">{task.type}</span>
                        <div className="flex -space-x-1 overflow-hidden">
                          {developers.slice(0, 4).map(dev => (
                            <button
                              key={dev.id}
                              onClick={() => handleAssign(task.id, dev.id)}
                              className="w-6 h-6 rounded-full bg-slate-800 border border-slate-900 flex items-center justify-center text-[8px] font-bold hover:bg-primary hover:text-white transition-all ring-2 ring-slate-950"
                              title={`Assign to ${dev.firstName}`}
                            >
                              {dev.firstName[0]}
                            </button>
                          ))}
                          {developers.length > 4 && (
                            <div className="w-6 h-6 rounded-full bg-slate-900 border border-slate-800 flex items-center justify-center text-[8px] font-bold text-slate-500 ring-2 ring-slate-950">
                              +{developers.length - 4}
                            </div>
                          )}
                        </div>
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
