"use client";

import { useState, useEffect } from "react";
import { useParams, useRouter } from "next/navigation";
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
import { motion, AnimatePresence } from "framer-motion";
import { 
  User, 
  Mail, 
  Phone, 
  Briefcase, 
  Calendar, 
  MapPin, 
  Award, 
  Code2, 
  Database, 
  Layout, 
  BrainCircuit,
  Activity,
  Star,
  CheckCircle2,
  Clock,
  ChevronLeft,
  Loader2,
  Trophy,
  Target,
  Zap,
  TrendingUp,
  AlertCircle
} from "lucide-react";
import Image from "next/image";
import Link from "next/link";
import { 
  Radar, RadarChart, PolarGrid, PolarAngleAxis, PolarRadiusAxis, 
  ResponsiveContainer, LineChart, Line, XAxis, YAxis, CartesianGrid, 
  Tooltip, Legend, AreaChart, Area, BarChart, Bar
} from 'recharts';

// --- Types ---

interface Task {
  id: number;
  task_type: string;
  task_complexity: number;
  story_points: number;
  status: string;
  created_at: string;
  started_at?: string;
  completed_at?: string;
}

interface DeveloperProfile {
  id: number;
  firstName: string;
  lastName: string;
  age: number;
  email: string;
  phoneNumber: string;
  specialization: string;
  bio: string;
  developerMetrics: {
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
  };
}

// --- Components ---

function MetricCard({ label, value, sub, icon: Icon, color }: any) {
  return (
    <div className="glass p-5 rounded-2xl border border-card-border relative overflow-hidden group">
      <div className={`absolute top-0 right-0 w-16 h-16 ${color}/10 rounded-bl-full flex items-center justify-center translate-x-4 -translate-y-4 group-hover:translate-x-2 group-hover:-translate-y-2 transition-transform`}>
        <Icon className={`w-6 h-6 ${color.replace('bg-', 'text-')}`} />
      </div>
      <div className="text-xs text-slate-400 font-semibold uppercase tracking-wider mb-2">{label}</div>
      <div className="text-3xl font-bold text-white mb-1">{value}</div>
      <div className="text-[10px] text-slate-500 font-medium">{sub}</div>
    </div>
  );
}

function TaskCard({ task }: { task: Task }) {
  const getStatusColor = (status: string) => {
    switch (status) {
      case 'COMPLETED': return 'bg-emerald-500/10 text-emerald-500 border-emerald-500/20';
      case 'IN_PROGRESS': return 'bg-blue-500/10 text-blue-500 border-blue-500/20';
      case 'BLOCKED': return 'bg-red-500/10 text-red-500 border-red-500/20';
      default: return 'bg-slate-800 text-slate-400 border-slate-700';
    }
  };

  return (
    <div className="p-4 rounded-xl bg-slate-900/50 border border-slate-800 hover:border-primary/40 transition-colors">
      <div className="flex justify-between items-start mb-3">
        <span className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider border ${getStatusColor(task.status)}`}>
          {task.status}
        </span>
        <span className="text-[10px] text-slate-500 font-mono">T-{task.id}</span>
      </div>
      <h5 className="text-sm font-semibold text-slate-200 mb-2 line-clamp-1">{task.task_type}</h5>
      <div className="flex items-center gap-3 text-[10px] text-slate-500">
        <span className="flex items-center gap-1">
          <Clock className="w-3 h-3" />
          {task.story_points} pts
        </span>
        <span className="flex items-center gap-1">
          <Target className="w-3 h-3" />
          Complexity {task.task_complexity}
        </span>
      </div>
    </div>
  );
}

export default function DeveloperProfilePage() {
  const { id } = useParams();
  const router = useRouter();
  const [profile, setProfile] = useState<DeveloperProfile | null>(null);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchData = async () => {
      try {
        // Fetch profile using the developer ID
        const profileRes = await authFetch(`http://localhost:8080/api/developers/${id}/profile`);
        const profileData = await profileRes.json();
        setProfile(profileData);

        // Fetch tasks
        const tasksRes = await authFetch(`http://localhost:8080/api/tasks/developer/${id}`);
        const tasksData = await tasksRes.json();
        setTasks(Array.isArray(tasksData) ? tasksData : []);
      } catch (err) {
        console.error("Error fetching developer profile:", err);
      } finally {
        setLoading(false);
      }
    };

    fetchData();
  }, [id]);

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <Loader2 className="w-10 h-10 animate-spin text-primary" />
      </div>
    );
  }

  if (!profile) {
    return (
      <div className="min-h-screen flex flex-col items-center justify-center space-y-4">
        <AlertCircle className="w-12 h-12 text-red-500" />
        <h2 className="text-2xl font-bold text-white">Developer Not Found</h2>
        <button onClick={() => router.back()} className="text-primary hover:underline">Go Back</button>
      </div>
    );
  }

  const skillData = [
    { subject: 'Frontend', A: profile.developerMetrics.skill_frontend * 10, fullMark: 100 },
    { subject: 'Backend', A: profile.developerMetrics.skill_backend * 10, fullMark: 100 },
    { subject: 'Database', A: profile.developerMetrics.skill_db * 10, fullMark: 100 },
    { subject: 'Consistency', A: profile.developerMetrics.consistency * 100, fullMark: 100 },
    { subject: 'Learning', A: profile.developerMetrics.learning_rate * 1000, fullMark: 100 },
    { subject: 'Velocity', A: 75, fullMark: 100 }, // Mocked or calculated
  ];

  const performanceData = [
    { name: 'S1', performance: 65, workload: 40 },
    { name: 'S2', performance: 72, workload: 60 },
    { name: 'S3', performance: 85, workload: 55 },
    { name: 'S4', performance: profile.developerMetrics.consistency * 100, workload: profile.developerMetrics.current_workload },
  ];

  return (
    <div className="min-h-screen bg-background p-6 lg:p-10">
      <div className="max-w-7xl mx-auto space-y-8">
        
        {/* Header / Nav */}
        <div className="flex items-center justify-between">
          <button 
            onClick={() => router.back()}
            className="flex items-center gap-2 text-slate-400 hover:text-white transition-colors"
          >
            <ChevronLeft className="w-5 h-5" />
            <span className="font-medium">Back to Team</span>
          </button>
          
          <div className="flex gap-3">
            <button className="px-4 py-2 bg-slate-800 hover:bg-slate-700 text-white rounded-lg text-sm font-medium transition-colors border border-slate-700">
              Edit Profile
            </button>
            <button className="px-4 py-2 bg-primary hover:bg-primary-dark text-white rounded-lg text-sm font-medium transition-colors shadow-lg shadow-primary/20">
              Assign Task
            </button>
          </div>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
          
          {/* Left Column: Personal Info */}
          <div className="lg:col-span-1 space-y-8">
            <div className="glass rounded-3xl p-8 border border-card-border relative overflow-hidden">
              <div className="absolute top-0 left-0 w-full h-32 bg-gradient-to-br from-primary/20 to-secondary/20" />
              
              <div className="relative z-10 flex flex-col items-center text-center mt-8">
                <div className="relative w-32 h-32 rounded-full overflow-hidden border-4 border-slate-900 shadow-2xl mb-6">
                  <Image 
                    src={`https://ui-avatars.com/api/?name=${profile.firstName}+${profile.lastName}&background=random&size=200`}
                    alt={`${profile.firstName} ${profile.lastName}`}
                    fill
                    className="object-cover"
                  />
                </div>
                <h1 className="text-3xl font-bold text-white mb-2">{profile.firstName} {profile.lastName}</h1>
                <p className="text-primary font-semibold mb-6">{profile.specialization}</p>
                
                <div className="flex flex-wrap justify-center gap-2 mb-8">
                  <span className="px-3 py-1 rounded-full bg-slate-800 text-slate-300 text-xs font-medium border border-slate-700 flex items-center gap-1.5">
                    <MapPin className="w-3 h-3 text-red-400" /> Remote
                  </span>
                  <span className="px-3 py-1 rounded-full bg-slate-800 text-slate-300 text-xs font-medium border border-slate-700 flex items-center gap-1.5">
                    <Activity className="w-3 h-3 text-emerald-400" /> Active
                  </span>
                  <span className="px-3 py-1 rounded-full bg-slate-800 text-slate-300 text-xs font-medium border border-slate-700 flex items-center gap-1.5">
                    <Trophy className="w-3 h-3 text-yellow-400" /> Top Performer
                  </span>
                </div>

                <div className="w-full space-y-4 text-left">
                  <div className="flex items-center gap-4 p-3 rounded-xl bg-slate-900/50 border border-slate-800">
                    <div className="w-10 h-10 rounded-lg bg-blue-500/10 flex items-center justify-center text-blue-400">
                      <Mail className="w-5 h-5" />
                    </div>
                    <div>
                      <div className="text-[10px] text-slate-500 uppercase font-bold tracking-widest">Email Address</div>
                      <div className="text-sm text-slate-200">{profile.email}</div>
                    </div>
                  </div>
                  <div className="flex items-center gap-4 p-3 rounded-xl bg-slate-900/50 border border-slate-800">
                    <div className="w-10 h-10 rounded-lg bg-purple-500/10 flex items-center justify-center text-purple-400">
                      <Phone className="w-5 h-5" />
                    </div>
                    <div>
                      <div className="text-[10px] text-slate-500 uppercase font-bold tracking-widest">Phone Number</div>
                      <div className="text-sm text-slate-200">{profile.phoneNumber}</div>
                    </div>
                  </div>
                  <div className="flex items-center gap-4 p-3 rounded-xl bg-slate-900/50 border border-slate-800">
                    <div className="w-10 h-10 rounded-lg bg-emerald-500/10 flex items-center justify-center text-emerald-400">
                      <Calendar className="w-5 h-5" />
                    </div>
                    <div>
                      <div className="text-[10px] text-slate-500 uppercase font-bold tracking-widest">Member Since</div>
                      <div className="text-sm text-slate-200">October 2023</div>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <div className="glass rounded-3xl p-8 border border-card-border">
              <h3 className="text-lg font-bold text-white mb-4 flex items-center gap-2">
                <BrainCircuit className="w-5 h-5 text-primary" /> About Me
              </h3>
              <p className="text-sm text-slate-400 leading-relaxed italic">
                "{profile.bio || "No biography provided yet."}"
              </p>
            </div>
          </div>

          {/* Center/Right Column: Metrics & Charts */}
          <div className="lg:col-span-2 space-y-8">
            
            {/* Stats Grid */}
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
              <MetricCard 
                label="Consistency" 
                value={`${Math.round(profile.developerMetrics.consistency * 100)}%`} 
                sub="Last 3 sprints" 
                icon={TrendingUp} 
                color="bg-emerald-500" 
              />
              <MetricCard 
                label="Learning" 
                value={`${(profile.developerMetrics.learning_rate * 100).toFixed(1)}`} 
                sub="Velocity growth" 
                icon={Zap} 
                color="bg-yellow-500" 
              />
              <MetricCard 
                label="Workload" 
                value={`${profile.developerMetrics.current_workload}%`} 
                sub={`${profile.developerMetrics.current_tasks} active tasks`} 
                icon={Activity} 
                color="bg-blue-500" 
              />
              <MetricCard 
                label="Availability" 
                value={`${profile.developerMetrics.availability}%`} 
                sub="Daily capacity" 
                icon={Clock} 
                color="bg-purple-500" 
              />
            </div>

            {/* Charts Grid */}
            <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
              <div className="glass rounded-3xl p-8 border border-card-border h-[400px]">
                <h3 className="text-lg font-bold text-white mb-6">Skill Profile</h3>
                <ResponsiveContainer width="100%" height="100%">
                  <RadarChart cx="50%" cy="50%" outerRadius="80%" data={skillData}>
                    <PolarGrid stroke="#334155" />
                    <PolarAngleAxis dataKey="subject" tick={{ fill: '#94a3b8', fontSize: 12 }} />
                    <PolarRadiusAxis angle={30} domain={[0, 100]} tick={false} />
                    <Radar
                      name="Skills"
                      dataKey="A"
                      stroke="#8b5cf6"
                      fill="#8b5cf6"
                      fillOpacity={0.4}
                    />
                  </RadarChart>
                </ResponsiveContainer>
              </div>

              <div className="glass rounded-3xl p-8 border border-card-border h-[400px]">
                <h3 className="text-lg font-bold text-white mb-6">Performance Trend</h3>
                <ResponsiveContainer width="100%" height="100%">
                  <AreaChart data={performanceData}>
                    <defs>
                      <linearGradient id="colorPerf" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="5%" stopColor="#3b82f6" stopOpacity={0.3}/>
                        <stop offset="95%" stopColor="#3b82f6" stopOpacity={0}/>
                      </linearGradient>
                    </defs>
                    <CartesianGrid strokeDasharray="3 3" stroke="#334155" vertical={false} />
                    <XAxis dataKey="name" stroke="#64748b" fontSize={12} tickLine={false} axisLine={false} />
                    <YAxis stroke="#64748b" fontSize={12} tickLine={false} axisLine={false} />
                    <Tooltip 
                      contentStyle={{ backgroundColor: '#0f172a', border: '1px solid #334155', borderRadius: '8px' }}
                      itemStyle={{ color: '#f8fafc' }}
                    />
                    <Area type="monotone" dataKey="performance" stroke="#3b82f6" fillOpacity={1} fill="url(#colorPerf)" />
                    <Area type="monotone" dataKey="workload" stroke="#8b5cf6" fillOpacity={0} />
                  </AreaChart>
                </ResponsiveContainer>
              </div>
            </div>

            {/* Tasks Section */}
            <div className="glass rounded-3xl p-8 border border-card-border">
              <div className="flex items-center justify-between mb-8">
                <div>
                  <h3 className="text-xl font-bold text-white mb-1">Active Tasks</h3>
                  <p className="text-xs text-slate-500">Managing {tasks.length} tasks in the current sprint</p>
                </div>
                <button className="text-sm font-semibold text-primary hover:underline">View All Task History</button>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                {tasks.length > 0 ? (
                  tasks.map(task => <TaskCard key={task.id} task={task} />)
                ) : (
                  <div className="col-span-full py-12 flex flex-col items-center justify-center text-slate-600 border border-dashed border-slate-800 rounded-2xl">
                    <Activity className="w-12 h-12 mb-4 opacity-20" />
                    <p className="font-medium text-lg">No active tasks assigned</p>
                    <p className="text-sm">This developer is currently available for allocation.</p>
                  </div>
                )}
              </div>
            </div>

          </div>
        </div>

      </div>
    </div>
  );
}
