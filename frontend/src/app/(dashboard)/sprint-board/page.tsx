"use client";

import { useState, useEffect } from "react";
import { getTokenStore } from "@/lib/api/token-store";
import { getActiveOrganization } from "@/lib/api/org-context";

async function authFetch(url: string, options: RequestInit = {}) {
  const token = getTokenStore().get();
  const orgId = getActiveOrganization();
  const headers = new Headers(options.headers || {});
  
  if (token && !headers.has("Authorization")) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  if (orgId && !headers.has("X-Organization-Id")) {
    headers.set("X-Organization-Id", orgId);
  }
  
  return fetch(url, { ...options, headers });
}
import { motion, AnimatePresence } from "framer-motion";
import { 
  Layout, 
  CheckCircle2, 
  Clock, 
  ListTodo, 
  AlertCircle, 
  User, 
  ChevronRight,
  TrendingUp,
  AlertTriangle,
  Check
} from "lucide-react";

interface Task {
  id: number;
  task_type: string;
  task_complexity: number;
  story_points: number;
  status: string;
  developer: {
    dev_id: string;
    profile: {
      firstName: string;
      lastName: string;
    } | null;
  } | null;
  blocked: boolean;
  reopened: boolean;
}

interface Sprint {
  id: number;
  sprintId: string;
  status: string;
  startDate: string;
  endDate: string;
}

const statusColumns = [
  { id: "TODO", title: "To Do", icon: ListTodo, color: "text-slate-400", bg: "bg-slate-400/10" },
  { id: "IN_PROGRESS", title: "In Progress", icon: Clock, color: "text-amber-400", bg: "bg-amber-400/10" },
  { id: "COMPLETED", title: "Done", icon: CheckCircle2, color: "text-emerald-400", bg: "bg-emerald-400/10" },
];

export default function SprintBoardPage() {
  const [sprints, setSprints] = useState<Sprint[]>([]);
  const [selectedSprintId, setSelectedSprintId] = useState<number | null>(null);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [isCompleting, setIsCompleting] = useState(false);

  useEffect(() => {
    fetchSprints();
  }, []);

  useEffect(() => {
    if (selectedSprintId) {
      fetchTasks(selectedSprintId);
    }
  }, [selectedSprintId]);

  const fetchSprints = async () => {
    try {
      const response = await authFetch("http://localhost:8080/api/sprints");
      if (!response.ok) throw new Error("Failed to fetch sprints");
      const data = await response.json();
      if (Array.isArray(data)) {
        setSprints(data);
        if (data.length > 0) {
          setSelectedSprintId(data[0].id);
        }
      } else {
        console.error("Invalid sprints response:", data);
        setSprints([]);
      }
    } catch (err) {
      setError("Failed to fetch sprints");
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  const fetchTasks = async (sprintId: number) => {
    setLoading(true);
    try {
      const response = await authFetch(`http://localhost:8080/api/tasks/sprint/${sprintId}`);
      if (!response.ok) throw new Error("Failed to fetch tasks");
      const data = await response.json();
      setTasks(Array.isArray(data) ? data : []);
    } catch (err) {
      setError("Failed to fetch tasks");
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  const handleCompleteAllTasks = async () => {
    if (!selectedSprintId || !confirm("Mark all tasks in this sprint as completed?")) return;
    
    setIsCompleting(true);
    try {
      const response = await authFetch(`http://localhost:8080/api/tasks/sprint/${selectedSprintId}/complete-all`, {
        method: 'POST'
      });
      if (!response.ok) throw new Error("Failed to complete tasks");
      await fetchTasks(selectedSprintId);
    } catch (err) {
      alert("Failed to complete all tasks");
    } finally {
      setIsCompleting(false);
    }
  };

  const handleCompleteSprint = async () => {
    if (!selectedSprintId) return;
    
    setIsCompleting(true);
    try {
      const response = await authFetch(`http://localhost:8080/api/sprints/${selectedSprintId}/complete`, {
        method: 'POST'
      });
      if (!response.ok) {
        const errorData = await response.json();
        throw new Error(errorData.message || "Failed to complete sprint");
      }
      await fetchSprints(); // Refresh sprint status
      alert("Sprint completed successfully!");
    } catch (err: any) {
      alert(err.message || "Failed to complete sprint. Ensure all tasks are done.");
    } finally {
      setIsCompleting(false);
    }
  };

  const handleUpdateStatus = async (taskId: number, newStatus: string) => {
    try {
      const response = await authFetch(`http://localhost:8080/api/tasks/${taskId}/status?status=${newStatus}`, {
        method: 'PATCH'
      });
      if (!response.ok) throw new Error("Failed to update status");
      if (selectedSprintId) fetchTasks(selectedSprintId);
    } catch (err) {
      alert("Failed to update task status");
    }
  };

  const allTasksDone = tasks.length > 0 && tasks.every(t => t.status === "COMPLETED");
  const selectedSprint = sprints.find(s => s.id === selectedSprintId);

  return (
    <div className="container mx-auto px-6 py-8">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-6 mb-10">
        <div>
          <h1 className="text-4xl font-black tracking-tight mb-2">
            Sprint <span className="text-gradient">Management</span>
          </h1>
          <p className="text-slate-400">Track task progress and finalize your active sprints.</p>
        </div>

        <div className="flex items-center gap-4">
          <div className="glass px-4 py-2 rounded-xl flex items-center gap-3">
            <span className="text-sm font-medium text-slate-400">Active Sprint:</span>
            <select 
              value={selectedSprintId || ""} 
              onChange={(e) => setSelectedSprintId(Number(e.target.value))}
              className="bg-transparent text-white font-bold outline-none cursor-pointer"
            >
              {sprints.map(s => (
                <option key={s.id} value={s.id} className="bg-slate-900">
                  {s.sprintId} ({s.status})
                </option>
              ))}
            </select>
          </div>

          <button
            onClick={handleCompleteAllTasks}
            disabled={isCompleting || !selectedSprintId}
            className={`flex items-center gap-2 px-4 py-2.5 rounded-xl text-sm font-bold transition-all border border-card-border ${
              !isCompleting && selectedSprintId
                ? "bg-slate-800 hover:bg-slate-700 text-white"
                : "text-slate-500 opacity-50 cursor-not-allowed"
            }`}
          >
            <CheckCircle2 className="w-4 h-4" />
            <span>Complete All Tasks</span>
          </button>

          <button
            onClick={handleCompleteSprint}
            disabled={isCompleting || !selectedSprintId}
            className={`flex items-center gap-2 px-6 py-2.5 rounded-xl text-sm font-bold transition-all shadow-lg ${
              !isCompleting && selectedSprintId
                ? "bg-emerald-500 hover:bg-emerald-600 text-white shadow-emerald-500/20"
                : "bg-slate-800 text-slate-500 cursor-not-allowed opacity-50"
            }`}
          >
            {isCompleting ? (
              <div className="w-5 h-5 border-2 border-white/30 border-t-white rounded-full animate-spin" />
            ) : selectedSprint?.status === "COMPLETED" ? (
              <>
                <Check className="w-4 h-4" />
                Sprint Done
              </>
            ) : (
              "Complete Sprint"
            )}
          </button>
        </div>
      </div>

      {loading ? (
        <div className="flex items-center justify-center h-64">
          <div className="w-12 h-12 border-4 border-primary/20 border-t-primary rounded-full animate-spin" />
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
          {statusColumns.map((column) => {
            const columnTasks = tasks.filter(t => {
              const status = t.status?.toUpperCase();
              if (column.id === "TODO") return ["TODO", "PLANNED", "REOPENED"].includes(status);
              return status === column.id;
            });

            return (
              <div key={column.id} className="flex flex-col gap-4">
                <div className="flex items-center justify-between px-2">
                  <div className="flex items-center gap-2">
                    <div className={`p-1.5 rounded-lg ${column.bg} ${column.color}`}>
                      <column.icon className="w-4 h-4" />
                    </div>
                    <h3 className="font-bold text-slate-200">{column.title}</h3>
                    <span className="px-2 py-0.5 rounded-full bg-slate-800 text-xs font-bold text-slate-400">
                      {columnTasks.length}
                    </span>
                  </div>
                </div>

                <div className="flex flex-col gap-4 min-h-[500px] p-2 rounded-2xl bg-slate-900/30 border border-card-border/50">
                  <AnimatePresence mode="popLayout">
                    {columnTasks.map((task) => (
                      <motion.div
                        key={task.id}
                        layout
                        initial={{ opacity: 0, y: 10 }}
                        animate={{ opacity: 1, y: 0 }}
                        exit={{ opacity: 0, scale: 0.95 }}
                        className="glass p-4 rounded-xl border border-card-border/50 hover:border-primary/30 transition-all group cursor-pointer"
                      >
                        <div className="flex items-start justify-between mb-3">
                          <span className="px-2 py-1 rounded-md bg-slate-800 text-[10px] font-bold text-slate-400 uppercase tracking-wider">
                            {task.task_type}
                          </span>
                          {task.status === "PLANNED" && (
                            <span className="px-2 py-1 rounded-md bg-primary/20 text-[10px] font-bold text-primary uppercase tracking-wider border border-primary/30">
                              Planned
                            </span>
                          )}
                          <select 
                            value={task.status}
                            onChange={(e) => handleUpdateStatus(task.id, e.target.value)}
                            className="bg-slate-800 text-[10px] font-bold text-slate-300 rounded px-1.5 py-0.5 outline-none border border-transparent hover:border-primary/50 cursor-pointer"
                          >
                            <option value="TODO">TODO</option>
                            <option value="IN_PROGRESS">IN PROGRESS</option>
                            <option value="COMPLETED">DONE</option>
                            <option value="BLOCKED">BLOCKED</option>
                          </select>
                        </div>

                        <h4 className="font-semibold text-slate-100 mb-4 line-clamp-2 leading-relaxed group-hover:text-primary transition-colors">
                          Task ID: {task.id} - {task.task_type} Implementation
                        </h4>

                        <div className="flex items-center justify-between">
                          <div className="flex items-center gap-2">
                            <div className="w-7 h-7 rounded-full bg-gradient-to-br from-primary to-secondary flex items-center justify-center text-[10px] font-bold text-white shadow-lg">
                              {task.developer?.profile?.firstName?.charAt(0) || <User className="w-3 h-3" />}
                            </div>
                            <span className="text-xs font-medium text-slate-400">
                              {task.developer?.profile 
                                ? `${task.developer.profile.firstName} ${task.developer.profile.lastName}`
                                : (task.developer as any)?.devId || task.developer?.dev_id || "Unassigned"}
                            </span>
                          </div>
                          
                          <div className="flex items-center gap-1.5 px-2 py-1 rounded-lg bg-slate-800/50">
                            <span className="text-[10px] font-bold text-slate-500 uppercase">SP</span>
                            <span className="text-xs font-black text-slate-200">{task.story_points}</span>
                          </div>
                        </div>
                      </motion.div>
                    ))}
                  </AnimatePresence>

                  {columnTasks.length === 0 && (
                    <div className="flex flex-col items-center justify-center h-48 text-slate-600">
                      <div className="w-10 h-10 rounded-full border-2 border-dashed border-slate-800 flex items-center justify-center mb-2">
                        <ChevronRight className="w-4 h-4 opacity-20" />
                      </div>
                      <p className="text-xs font-medium italic">Empty</p>
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
