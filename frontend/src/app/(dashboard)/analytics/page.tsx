"use client";

import { motion } from "framer-motion";
import { 
  BarChart3, 
  LineChart as LineChartIcon, 
  PieChart as PieChartIcon, 
  Activity,
  TrendingUp,
  Download
} from "lucide-react";
import { 
  LineChart, 
  Line, 
  BarChart, 
  Bar, 
  PieChart, 
  Pie, 
  Cell,
  AreaChart,
  Area,
  XAxis, 
  YAxis, 
  CartesianGrid, 
  Tooltip, 
  Legend, 
  ResponsiveContainer 
} from 'recharts';

// --- MOCK DATA ---

const productivityData = [
  { name: 'Sprint 1', frontend: 400, backend: 240, devops: 240 },
  { name: 'Sprint 2', frontend: 300, backend: 139, devops: 221 },
  { name: 'Sprint 3', frontend: 200, backend: 980, devops: 229 },
  { name: 'Sprint 4', frontend: 278, backend: 390, devops: 200 },
  { name: 'Sprint 5', frontend: 189, backend: 480, devops: 218 },
  { name: 'Sprint 6', frontend: 239, backend: 380, devops: 250 },
  { name: 'Sprint 7', frontend: 349, backend: 430, devops: 210 },
];

const velocityData = [
  { name: 'Sprint 1', planned: 40, completed: 38 },
  { name: 'Sprint 2', planned: 45, completed: 42 },
  { name: 'Sprint 3', planned: 42, completed: 45 },
  { name: 'Sprint 4', planned: 50, completed: 48 },
  { name: 'Sprint 5', planned: 55, completed: 52 },
  { name: 'Sprint 6', planned: 50, completed: 55 },
];

const taskCompletionData = [
  { name: '< 1 Day', value: 400 },
  { name: '1-3 Days', value: 300 },
  { name: '3-5 Days', value: 300 },
  { name: '> 5 Days', value: 200 },
];

const workloadData = [
  { name: 'Mon', capacity: 100, actual: 80 },
  { name: 'Tue', capacity: 100, actual: 95 },
  { name: 'Wed', capacity: 100, actual: 110 }, // Overload
  { name: 'Thu', capacity: 100, actual: 85 },
  { name: 'Fri', capacity: 100, actual: 70 },
];

const COLORS = ['#10b981', '#3b82f6', '#f59e0b', '#ef4444'];

export default function AnalyticsPage() {
  const container = {
    hidden: { opacity: 0 },
    show: { opacity: 1, transition: { staggerChildren: 0.1 } }
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
            <h1 className="text-3xl font-bold text-foreground">Productivity Analytics</h1>
            <p className="text-slate-400 mt-1">System intelligence and evaluation metrics.</p>
          </div>
          
          <div className="flex items-center gap-3">
            <select className="px-4 py-2 bg-slate-900/50 border border-card-border rounded-lg text-sm focus:outline-none text-foreground">
              <option>Last 30 Days</option>
              <option>Last Quarter</option>
              <option>Year to Date</option>
            </select>
            <button className="p-2.5 bg-slate-900/50 hover:bg-slate-800 border border-card-border rounded-lg text-slate-300 transition-colors">
              <Download className="w-4 h-4" />
            </button>
          </div>
        </div>

        {/* Stats Row */}
        <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
          {[
            { label: "AI Prediction Accuracy", value: "96.4%", icon: Activity, trend: "+1.2%" },
            { label: "Avg Velocity Increase", value: "18%", icon: TrendingUp, trend: "+4%" },
            { label: "Time Saved / Sprint", value: "24h", icon: LineChartIcon, trend: "+2h" },
            { label: "Burnout Risk Score", value: "Low", icon: PieChartIcon, trend: "-15%" },
          ].map((stat, i) => (
            <div key={i} className="glass p-5 rounded-xl border border-card-border relative overflow-hidden">
              <div className="absolute top-0 right-0 p-4 opacity-10">
                <stat.icon className="w-12 h-12" />
              </div>
              <div className="text-sm text-slate-400 mb-2">{stat.label}</div>
              <div className="text-2xl font-bold text-foreground mb-1">{stat.value}</div>
              <div className={`text-xs font-medium ${stat.trend.startsWith('+') ? 'text-emerald-500' : 'text-blue-500'}`}>
                {stat.trend} from last period
              </div>
            </div>
          ))}
        </div>

        {/* Charts Grid */}
        <motion.div 
          variants={container}
          initial="hidden"
          animate="show"
          className="grid grid-cols-1 lg:grid-cols-2 gap-6"
        >
          {/* Developer Productivity Trends */}
          <motion.div variants={item} className="glass p-6 rounded-xl border border-card-border">
            <h3 className="text-lg font-semibold mb-6 flex items-center">
              <LineChartIcon className="w-5 h-5 mr-2 text-primary" />
              Developer Productivity Trends
            </h3>
            <div className="h-[300px] w-full">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={productivityData} margin={{ top: 5, right: 20, bottom: 5, left: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#334155" vertical={false} />
                  <XAxis dataKey="name" stroke="#94a3b8" fontSize={12} tickLine={false} />
                  <YAxis stroke="#94a3b8" fontSize={12} tickLine={false} axisLine={false} />
                  <Tooltip 
                    contentStyle={{ backgroundColor: '#0f172a', borderColor: '#1e293b', borderRadius: '8px' }}
                    itemStyle={{ color: '#e2e8f0' }}
                  />
                  <Legend iconType="circle" wrapperStyle={{ fontSize: '12px', paddingTop: '20px' }} />
                  <Line type="monotone" dataKey="frontend" stroke="#3b82f6" strokeWidth={3} dot={{ r: 4 }} activeDot={{ r: 6 }} />
                  <Line type="monotone" dataKey="backend" stroke="#10b981" strokeWidth={3} dot={{ r: 4 }} activeDot={{ r: 6 }} />
                  <Line type="monotone" dataKey="devops" stroke="#f59e0b" strokeWidth={3} dot={{ r: 4 }} activeDot={{ r: 6 }} />
                </LineChart>
              </ResponsiveContainer>
            </div>
          </motion.div>

          {/* Sprint Velocity Over Time */}
          <motion.div variants={item} className="glass p-6 rounded-xl border border-card-border">
            <h3 className="text-lg font-semibold mb-6 flex items-center">
              <BarChart3 className="w-5 h-5 mr-2 text-primary" />
              Sprint Velocity Over Time
            </h3>
            <div className="h-[300px] w-full">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={velocityData} margin={{ top: 5, right: 20, bottom: 5, left: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#334155" vertical={false} />
                  <XAxis dataKey="name" stroke="#94a3b8" fontSize={12} tickLine={false} />
                  <YAxis stroke="#94a3b8" fontSize={12} tickLine={false} axisLine={false} />
                  <Tooltip 
                    contentStyle={{ backgroundColor: '#0f172a', borderColor: '#1e293b', borderRadius: '8px' }}
                    cursor={{ fill: '#1e293b', opacity: 0.4 }}
                  />
                  <Legend iconType="circle" wrapperStyle={{ fontSize: '12px', paddingTop: '20px' }} />
                  <Bar dataKey="planned" fill="#3b4b66" radius={[4, 4, 0, 0]} />
                  <Bar dataKey="completed" fill="#10b981" radius={[4, 4, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </motion.div>

          {/* Workload Balance Graph */}
          <motion.div variants={item} className="glass p-6 rounded-xl border border-card-border">
            <h3 className="text-lg font-semibold mb-6 flex items-center">
              <Activity className="w-5 h-5 mr-2 text-primary" />
              Team Workload Balance
            </h3>
            <div className="h-[300px] w-full">
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={workloadData} margin={{ top: 5, right: 20, bottom: 5, left: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#334155" vertical={false} />
                  <XAxis dataKey="name" stroke="#94a3b8" fontSize={12} tickLine={false} />
                  <YAxis stroke="#94a3b8" fontSize={12} tickLine={false} axisLine={false} />
                  <Tooltip 
                    contentStyle={{ backgroundColor: '#0f172a', borderColor: '#1e293b', borderRadius: '8px' }}
                  />
                  <Legend iconType="circle" wrapperStyle={{ fontSize: '12px', paddingTop: '20px' }} />
                  <Area type="monotone" dataKey="capacity" stroke="#3b4b66" fill="#1e293b" fillOpacity={0.5} strokeWidth={2} />
                  <Area type="monotone" dataKey="actual" stroke="#8b5cf6" fill="#8b5cf6" fillOpacity={0.3} strokeWidth={2} />
                </AreaChart>
              </ResponsiveContainer>
            </div>
          </motion.div>

          {/* Task Completion Time Distribution */}
          <motion.div variants={item} className="glass p-6 rounded-xl border border-card-border">
            <h3 className="text-lg font-semibold mb-6 flex items-center">
              <PieChartIcon className="w-5 h-5 mr-2 text-primary" />
              Task Completion Time
            </h3>
            <div className="h-[300px] w-full flex items-center justify-center">
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie
                    data={taskCompletionData}
                    cx="50%"
                    cy="50%"
                    innerRadius={80}
                    outerRadius={110}
                    paddingAngle={5}
                    dataKey="value"
                    stroke="none"
                  >
                    {taskCompletionData.map((entry, index) => (
                      <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
                    ))}
                  </Pie>
                  <Tooltip 
                    contentStyle={{ backgroundColor: '#0f172a', borderColor: '#1e293b', borderRadius: '8px' }}
                    itemStyle={{ color: '#e2e8f0' }}
                  />
                  <Legend layout="vertical" verticalAlign="middle" align="right" iconType="circle" wrapperStyle={{ fontSize: '12px' }} />
                </PieChart>
              </ResponsiveContainer>
            </div>
          </motion.div>

        </motion.div>
      </div>
    </div>
  );
}
