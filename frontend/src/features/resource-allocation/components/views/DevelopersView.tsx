"use client";

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
  UserCheck
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

interface UIDeveloper {
  id: number;
  raw: DeveloperData;
  name: string;
  role: string;
  avatar: string;
  skills: string[];
  workload: number;
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
  const [searchQuery, setSearchQuery] = useState("");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      authFetch("http://localhost:8080/api/developers").then(res => res.json()),
      authFetch("http://localhost:8080/api/tasks").then(res => res.json()).catch(() => [])
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

          return {
            id: dev.id,
            raw: dev,
            name: fullName || dev.dev_id,
            role,
            avatar: `https://ui-avatars.com/api/?name=${encodeURIComponent(fullName || dev.dev_id)}&background=random`,
            skills,
            workload: dev.current_workload,
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
      {/* Header Controls */}
      <div className="flex justify-between items-center w-full">
        <div>
          <h2 className="text-lg font-bold text-white tracking-wide">Developer Profiles & AI Telemetry</h2>
          <p className="text-xs text-slate-400 mt-0.5">Click any developer card to inspect details, skills, and tasks. To edit your own profile, click your profile icon in the top right header.</p>
        </div>
        <div className="flex items-center space-x-3">
          <div className="relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
            <input 
              type="text" 
              placeholder="Search by name, skill, or ID..." 
              className="pl-9 pr-4 py-2 bg-slate-900/50 border border-card-border rounded-xl text-xs focus:outline-none focus:ring-1 focus:ring-primary/50 text-foreground w-72"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
          <button className="p-2 bg-slate-900/50 border border-card-border rounded-xl hover:bg-slate-800 transition-colors text-slate-300">
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
          <div key={i} className="glass p-5 rounded-2xl border border-card-border">
            <div className="text-sm text-slate-400 mb-1">{stat.label}</div>
            <div className="text-2xl font-bold text-foreground">{stat.value}</div>
            <div className="text-xs text-emerald-500 mt-1">{stat.sub}</div>
          </div>
        ))}
      </div>

      {/* Developer Grid */}
      {loading ? (
        <div className="flex justify-center items-center py-20 text-slate-400">
          <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary mr-3"></div>
          Loading developers...
        </div>
      ) : filteredDevelopers.length === 0 ? (
        <div className="text-center py-20 border border-dashed border-card-border rounded-2xl bg-slate-950/30 p-8">
          <Code2 className="w-12 h-12 mx-auto text-primary/60 mb-3" />
          <h3 className="text-xl font-semibold text-foreground">No developers found</h3>
          <p className="text-sm mt-2 text-slate-400 max-w-md mx-auto">
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
              className="glass rounded-2xl border border-card-border overflow-hidden group hover:border-primary/50 hover:shadow-lg hover:shadow-primary/5 transition-all duration-300 h-full cursor-pointer relative"
            >
              <div className="p-6">
                <div className="flex justify-between items-start mb-4">
                  <div className="flex items-center space-x-4">
                    <div className="relative w-12 h-12 rounded-full overflow-hidden border-2 border-primary/20 group-hover:border-primary/60 transition-colors">
                      <Image src={dev.avatar} alt={dev.name} fill className="object-cover" />
                    </div>
                    <div>
                      <div className="flex items-center gap-2">
                        <h3 className="font-bold text-foreground text-base group-hover:text-primary transition-colors">{dev.name}</h3>
                        <span className="text-[10px] font-mono text-slate-500 bg-slate-900 px-1.5 py-0.5 rounded border border-slate-800">
                          {dev.raw.dev_id}
                        </span>
                      </div>
                      <p className="text-xs text-slate-400 mt-0.5">{dev.role}</p>
                    </div>
                  </div>

                  <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full border ${
                    dev.status === 'Available' ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20' : 'bg-amber-500/10 text-amber-400 border-amber-500/20'
                  }`}>
                    {dev.status}
                  </span>
                </div>

                <div className="space-y-4">
                  <div>
                    <div className="text-[10px] font-bold text-slate-500 mb-2 uppercase tracking-wider">Top Skills</div>
                    <div className="flex flex-wrap gap-1.5">
                      {dev.skills.map((skill, i) => (
                        <span key={i} className="inline-flex items-center px-2 py-1 rounded-lg text-xs font-semibold bg-slate-900 text-slate-300 border border-slate-800">
                          {getSkillIcon(skill)}
                          {skill}
                        </span>
                      ))}
                    </div>
                  </div>

                  <div className="grid grid-cols-2 gap-4 pt-4 border-t border-slate-800/50">
                    <div>
                      <div className="flex justify-between text-xs mb-1">
                        <span className="text-slate-400 text-[11px]">Workload</span>
                        <span className="font-bold text-white">{dev.workload} SPs</span>
                      </div>
                      <div className="h-1.5 w-full bg-slate-800 rounded-full overflow-hidden">
                        <div 
                          className={`h-full rounded-full ${dev.workload > 15 ? 'bg-red-500' : dev.workload < 8 ? 'bg-emerald-500' : 'bg-blue-500'}`} 
                          style={{ width: `${Math.min(100, Math.round((dev.workload / (dev.raw.experience_level * 10 || 20)) * 100))}%` }}
                        />
                      </div>
                      <div className="mt-2 text-[10px] text-slate-500">
                        {dev.availability.toFixed(0)}% Bandwidth Open
                      </div>
                    </div>
                    
                    <div>
                      <div className="flex justify-between text-xs mb-1">
                        <span className="text-slate-400 text-[11px]">Performance</span>
                        <span className="font-bold text-white">{dev.performance}%</span>
                      </div>
                      <div className="flex items-center space-x-1">
                        {[1, 2, 3, 4, 5].map((star) => (
                          <Star 
                            key={star} 
                            className={`w-3.5 h-3.5 ${star <= Math.round(dev.performance / 20) ? 'text-yellow-500 fill-yellow-500' : 'text-slate-700'}`} 
                          />
                        ))}
                      </div>
                      <div className="mt-2 text-[10px] text-slate-500 uppercase tracking-wide flex items-center">
                        <Activity className="w-3 h-3 mr-1 text-primary" />
                        {dev.projects} Active Task{dev.projects === 1 ? '' : 's'}
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </motion.div>
          </div>
        ))}
      </motion.div>
      )}

      {/* ========================================================================= */}
      {/* DEVELOPER DETAIL & METRICS MODAL */}
      {/* ========================================================================= */}
      <AnimatePresence>
        {selectedDev && (
          <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm">
            <motion.div
              initial={{ opacity: 0, scale: 0.95, y: 10 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.95, y: 10 }}
              className="glass border border-slate-700/60 rounded-3xl w-full max-w-3xl overflow-hidden shadow-2xl max-h-[90vh] flex flex-col bg-slate-950"
            >
              {/* Modal Header */}
              <div className="p-6 border-b border-slate-800 flex items-start justify-between bg-slate-900/40">
                <div className="flex items-center space-x-4">
                  <div className="relative w-16 h-16 rounded-2xl overflow-hidden border-2 border-primary/40 ring-4 ring-primary/10">
                    <Image src={selectedDev.avatar} alt={selectedDev.name} fill className="object-cover" />
                  </div>
                  <div>
                    <div className="flex items-center gap-2">
                      <h2 className="text-xl font-bold text-white capitalize">{selectedDev.name}</h2>
                      <span className="text-xs font-mono font-bold text-primary bg-primary/10 px-2 py-0.5 rounded-md border border-primary/20">
                        {selectedDev.raw.dev_id}
                      </span>
                      <span className={`text-[10px] font-bold uppercase px-2 py-0.5 rounded-full border ${
                        selectedDev.status === 'Available' ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20' : 'bg-amber-500/10 text-amber-400 border-amber-500/20'
                      }`}>
                        {selectedDev.status}
                      </span>
                    </div>
                    <div className="flex items-center gap-3 mt-1 text-xs text-slate-400">
                      <span className="font-semibold text-slate-300">{selectedDev.role}</span>
                      <span>•</span>
                      <span>{selectedDev.raw.profile?.specialization || "Full-Stack Engineer"}</span>
                      {selectedDev.raw.profile?.email && (
                        <>
                          <span>•</span>
                          <span className="flex items-center gap-1 text-slate-400">
                            <Mail className="w-3 h-3 text-slate-500" />
                            {selectedDev.raw.profile.email}
                          </span>
                        </>
                      )}
                    </div>
                  </div>
                </div>

                <button
                  onClick={() => setSelectedDev(null)}
                  className="p-2 rounded-xl text-slate-400 hover:text-white hover:bg-slate-800 transition-colors"
                >
                  <X className="w-5 h-5" />
                </button>
              </div>

              {/* Modal Body */}
              <div className="p-6 overflow-y-auto space-y-6 flex-grow">
                {/* 4 Key Metric Cards */}
                <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                  <div className="bg-slate-900/60 p-4 rounded-2xl border border-slate-800">
                    <div className="flex items-center justify-between mb-1">
                      <span className="text-[11px] font-bold text-slate-400 uppercase">Consistency</span>
                      <Zap className="w-3.5 h-3.5 text-yellow-400" />
                    </div>
                    <div className="text-xl font-bold text-white">{selectedDev.performance}%</div>
                    <div className="text-[10px] text-slate-500 mt-1">Delivery stability score</div>
                  </div>

                  <div className="bg-slate-900/60 p-4 rounded-2xl border border-slate-800">
                    <div className="flex items-center justify-between mb-1">
                      <span className="text-[11px] font-bold text-slate-400 uppercase">Growth Rate</span>
                      <TrendingUp className="w-3.5 h-3.5 text-emerald-400" />
                    </div>
                    <div className="text-xl font-bold text-white">{(selectedDev.raw.learning_rate * 100).toFixed(0)}%</div>
                    <div className="text-[10px] text-slate-500 mt-1">Skill adaptability index</div>
                  </div>

                  <div className="bg-slate-900/60 p-4 rounded-2xl border border-slate-800">
                    <div className="flex items-center justify-between mb-1">
                      <span className="text-[11px] font-bold text-slate-400 uppercase">Workload</span>
                      <Briefcase className="w-3.5 h-3.5 text-blue-400" />
                    </div>
                    <div className="text-xl font-bold text-white">{selectedDev.workload} <span className="text-xs text-slate-400">/ {(selectedDev.raw.experience_level || 2) * 10} SPs</span></div>
                    <div className="text-[10px] text-slate-500 mt-1">Sprint capacity utilization</div>
                  </div>

                  <div className="bg-slate-900/60 p-4 rounded-2xl border border-slate-800">
                    <div className="flex items-center justify-between mb-1">
                      <span className="text-[11px] font-bold text-slate-400 uppercase">Availability</span>
                      <Award className="w-3.5 h-3.5 text-purple-400" />
                    </div>
                    <div className="text-xl font-bold text-white">{selectedDev.availability.toFixed(1)}%</div>
                    <div className="text-[10px] text-slate-500 mt-1">Free sprint bandwidth</div>
                  </div>
                </div>

                {/* Technical Skill Proficiency Bars */}
                <div className="bg-slate-900/40 p-5 rounded-2xl border border-slate-800/80 space-y-4">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <Layers className="w-4 h-4 text-primary" />
                      <h4 className="text-xs font-bold uppercase tracking-wider text-white">Technical Skill Proficiencies</h4>
                    </div>
                    <span className="text-[10px] text-slate-500 font-mono">Scale 1–10 (ML Verified)</span>
                  </div>

                  <div className="space-y-3">
                    <div>
                      <div className="flex justify-between text-xs mb-1">
                        <span className="font-semibold text-slate-300 flex items-center gap-1.5">
                          <Layout className="w-3.5 h-3.5 text-cyan-400" />
                          Frontend Engineering (React, Next.js, UI/UX, CSS)
                        </span>
                        <span className="font-bold text-white">{selectedDev.raw.skill_frontend} / 10</span>
                      </div>
                      <div className="h-2 w-full bg-slate-800 rounded-full overflow-hidden">
                        <div 
                          className="h-full rounded-full bg-gradient-to-r from-cyan-500 to-blue-500 transition-all duration-500" 
                          style={{ width: `${selectedDev.raw.skill_frontend * 10}%` }}
                        />
                      </div>
                    </div>

                    <div>
                      <div className="flex justify-between text-xs mb-1">
                        <span className="font-semibold text-slate-300 flex items-center gap-1.5">
                          <Server className="w-3.5 h-3.5 text-emerald-400" />
                          Backend & Distributed APIs (Spring Boot, Java, Python, REST)
                        </span>
                        <span className="font-bold text-white">{selectedDev.raw.skill_backend} / 10</span>
                      </div>
                      <div className="h-2 w-full bg-slate-800 rounded-full overflow-hidden">
                        <div 
                          className="h-full rounded-full bg-gradient-to-r from-emerald-500 to-teal-500 transition-all duration-500" 
                          style={{ width: `${selectedDev.raw.skill_backend * 10}%` }}
                        />
                      </div>
                    </div>

                    <div>
                      <div className="flex justify-between text-xs mb-1">
                        <span className="font-semibold text-slate-300 flex items-center gap-1.5">
                          <Database className="w-3.5 h-3.5 text-purple-400" />
                          Database Architecture & SQL (PostgreSQL, Migrations, Indexing)
                        </span>
                        <span className="font-bold text-white">{selectedDev.raw.skill_db} / 10</span>
                      </div>
                      <div className="h-2 w-full bg-slate-800 rounded-full overflow-hidden">
                        <div 
                          className="h-full rounded-full bg-gradient-to-r from-purple-500 to-pink-500 transition-all duration-500" 
                          style={{ width: `${selectedDev.raw.skill_db * 10}%` }}
                        />
                      </div>
                    </div>
                  </div>
                </div>

                {/* Assigned Tasks Section */}
                <div className="bg-slate-900/40 p-5 rounded-2xl border border-slate-800/80 space-y-3">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <Sparkles className="w-4 h-4 text-amber-400" />
                      <h4 className="text-xs font-bold uppercase tracking-wider text-white">
                        Active Assigned Tasks ({selectedDevTasks.length})
                      </h4>
                    </div>
                    <span className="text-[10px] text-slate-400 font-bold">
                      Total: {selectedDevTasks.reduce((acc, t) => acc + (t.story_points || 0), 0)} SPs
                    </span>
                  </div>

                  {selectedDevTasks.length === 0 ? (
                    <p className="text-xs text-slate-500 italic py-2">
                      No tasks currently assigned to this developer in the active sprint.
                    </p>
                  ) : (
                    <div className="space-y-2">
                      {selectedDevTasks.map((task) => (
                        <div 
                          key={task.id}
                          className="bg-slate-950/80 p-3.5 rounded-xl border border-slate-800 flex items-center justify-between"
                        >
                          <div className="flex items-center gap-3">
                            <CheckCircle2 className="w-4 h-4 text-emerald-400 flex-shrink-0" />
                            <div>
                              <div className="text-xs font-bold text-white line-clamp-1">{task.task_type}</div>
                              <div className="flex items-center gap-2 mt-0.5 text-[10px] text-slate-500">
                                <span>Complexity: {task.task_complexity}/5</span>
                                <span>•</span>
                                <span>FE: {task.req_frontend} | BE: {task.req_backend} | DB: {task.req_db}</span>
                              </div>
                            </div>
                          </div>
                          <div className="flex items-center gap-2">
                            <span className="text-xs font-bold text-white bg-slate-800 px-2 py-0.5 rounded-lg border border-slate-700">
                              {task.story_points} SPs
                            </span>
                            <span className="text-[10px] font-bold text-primary bg-primary/10 px-2 py-0.5 rounded border border-primary/20">
                              {task.status || "TODO"}
                            </span>
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>

                {/* Bio / Profile Note */}
                {selectedDev.raw.profile?.bio && (
                  <div className="p-4 rounded-2xl bg-slate-900/30 border border-slate-800 text-xs text-slate-400">
                    <span className="font-bold text-slate-300 block mb-1">Developer Bio:</span>
                    {selectedDev.raw.profile.bio}
                  </div>
                )}
              </div>

              {/* Modal Footer */}
              <div className="p-4 border-t border-slate-800 bg-slate-900/40 flex items-center justify-between">
                <span className="text-[11px] text-slate-500 italic flex items-center gap-1.5">
                  <UserCheck className="w-3.5 h-3.5 text-primary" />
                  To edit your personal profile and skills, use the Profile option in the top right menu.
                </span>

                <button
                  onClick={() => setSelectedDev(null)}
                  className="px-5 py-2 bg-slate-800 hover:bg-slate-700 text-white rounded-xl text-xs font-bold transition-colors"
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
