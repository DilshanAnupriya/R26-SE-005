"use client";

import { useState, useEffect } from "react";
import { motion } from "framer-motion";
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
  MoreVertical
} from "lucide-react";
import Image from "next/image";

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
}

interface UIDeveloper {
  id: number;
  name: string;
  role: string;
  avatar: string;
  skills: string[];
  workload: number;
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
  if (s.includes('react') || s.includes('next') || s.includes('tailwind') || s.includes('ui')) return <Layout className="w-3 h-3 mr-1" />;
  if (s.includes('node') || s.includes('java') || s.includes('spring') || s.includes('python')) return <Server className="w-3 h-3 mr-1" />;
  if (s.includes('sql') || s.includes('postgres') || s.includes('mongo') || s.includes('pandas')) return <Database className="w-3 h-3 mr-1" />;
  if (s.includes('ml') || s.includes('tensor') || s.includes('pytorch')) return <BrainCircuit className="w-3 h-3 mr-1" />;
  return <Code2 className="w-3 h-3 mr-1" />;
};

export default function DevelopersPage() {
  const [developers, setDevelopers] = useState<UIDeveloper[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch("http://localhost:8080/api/developers")
      .then(res => res.json())
      .then((data: DeveloperData[]) => {
        const mapped = data.map(dev => {
          const skills = [];
          if (dev.skill_frontend > 50) skills.push("Frontend");
          if (dev.skill_backend > 50) skills.push("Backend");
          if (dev.skill_db > 50) skills.push("Database");
          if (skills.length === 0) skills.push("General");

          let role = "Developer";
          if (dev.experience_level > 80) role = "Senior Engineer";
          else if (dev.experience_level > 50) role = "Mid-level Engineer";
          else role = "Junior Engineer";

          return {
            id: dev.id,
            name: dev.dev_id,
            role,
            avatar: `https://ui-avatars.com/api/?name=${dev.dev_id}&background=random`,
            skills,
            workload: dev.current_workload,
            performance: Math.round(dev.consistency * 100),
            status: dev.availability > 0.5 ? "Available" : "Busy",
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

  const container = {
    hidden: { opacity: 0 },
    show: {
      opacity: 1,
      transition: { staggerChildren: 0.1 }
    }
  };

  const item = {
    hidden: { y: 20, opacity: 0 },
    show: { y: 0, opacity: 1 }
  };

  return (
    <div className="min-h-screen bg-background p-6 lg:p-10">
      <div className="max-w-7xl mx-auto space-y-8">
        
        {/* Header */}
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
          <div>
            <h1 className="text-3xl font-bold text-foreground">Developer Intelligence</h1>
            <p className="text-slate-400 mt-1">Manage team skills, track performance, and monitor workloads.</p>
          </div>
          
          <div className="flex items-center space-x-3">
            <div className="relative">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
              <input 
                type="text" 
                placeholder="Search developers..." 
                className="pl-9 pr-4 py-2 bg-slate-900/50 border border-card-border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-foreground w-64"
              />
            </div>
            <button className="p-2 bg-slate-900/50 border border-card-border rounded-lg hover:bg-slate-800 transition-colors text-slate-300">
              <Filter className="w-4 h-4" />
            </button>
            <button className="px-4 py-2 bg-primary hover:bg-primary-dark text-white rounded-lg text-sm font-medium transition-colors shadow-lg shadow-primary/20">
              Add Developer
            </button>
          </div>
        </div>

        {/* Stats Row */}
        <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
          {[
            { label: "Total Developers", value: developers.length.toString(), sub: "Active team members" },
            { label: "Avg Performance", value: `${Math.round(developers.reduce((acc, dev) => acc + dev.performance, 0) / (developers.length || 1))}%`, sub: "Across all active tasks" },
            { label: "Available Capacity", value: `${Math.round((developers.filter(d => d.status === "Available").length / (developers.length || 1)) * 100)}%`, sub: `${developers.filter(d => d.status === "Available").length} devs available` },
            { label: "Skill Coverage", value: "85%", sub: "Across 42 technologies" },
          ].map((stat, i) => (
            <div key={i} className="glass p-5 rounded-xl border border-card-border">
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
        ) : (
          <motion.div 
            variants={container}
            initial="hidden"
            animate="show"
            className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6"
          >
          {developers.map((dev) => (
            <motion.div key={dev.id} variants={item} className="glass rounded-xl border border-card-border overflow-hidden group hover:border-primary/50 transition-colors duration-300">
              <div className="p-6">
                <div className="flex justify-between items-start mb-4">
                  <div className="flex items-center space-x-4">
                    <div className="relative w-12 h-12 rounded-full overflow-hidden border-2 border-primary/20 group-hover:border-primary/60 transition-colors">
                      <Image src={dev.avatar} alt={dev.name} fill className="object-cover" />
                    </div>
                    <div>
                      <h3 className="font-semibold text-foreground text-lg">{dev.name}</h3>
                      <p className="text-xs text-slate-400">{dev.role}</p>
                    </div>
                  </div>
                  <button className="text-slate-500 hover:text-slate-300">
                    <MoreVertical className="w-5 h-5" />
                  </button>
                </div>

                <div className="space-y-4">
                  {/* Skills */}
                  <div>
                    <div className="text-xs font-medium text-slate-500 mb-2 uppercase tracking-wider">Top Skills</div>
                    <div className="flex flex-wrap gap-2">
                      {dev.skills.map((skill, i) => (
                        <span key={i} className="inline-flex items-center px-2 py-1 rounded-md text-xs font-medium bg-slate-800 text-slate-300 border border-slate-700">
                          {getSkillIcon(skill)}
                          {skill}
                        </span>
                      ))}
                    </div>
                  </div>

                  {/* Metrics */}
                  <div className="grid grid-cols-2 gap-4 pt-4 border-t border-slate-800/50">
                    <div>
                      <div className="flex justify-between text-xs mb-1">
                        <span className="text-slate-400">Workload</span>
                        <span className="font-medium text-foreground">{dev.workload}%</span>
                      </div>
                      <div className="h-1.5 w-full bg-slate-800 rounded-full overflow-hidden">
                        <div 
                          className={`h-full rounded-full ${dev.workload > 80 ? 'bg-red-500' : dev.workload < 50 ? 'bg-green-500' : 'bg-blue-500'}`} 
                          style={{ width: `${dev.workload}%` }}
                        />
                      </div>
                      <div className={`mt-2 inline-flex px-2 py-0.5 rounded text-[10px] font-semibold uppercase tracking-wide border ${getWorkloadColor(dev.workload)}`}>
                        {dev.status}
                      </div>
                    </div>
                    
                    <div>
                      <div className="flex justify-between text-xs mb-1">
                        <span className="text-slate-400">Performance</span>
                        <span className="font-medium text-foreground">{dev.performance}</span>
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
                        <Activity className="w-3 h-3 mr-1" />
                        {dev.projects} Active Proj.
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </motion.div>
          ))}
        </motion.div>
        )}

      </div>
    </div>
  );
}
