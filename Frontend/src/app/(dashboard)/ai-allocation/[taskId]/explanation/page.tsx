"use client";

import { motion } from "framer-motion";
import { 
  ArrowLeft, 
  BrainCircuit, 
  Target, 
  Activity, 
  History,
  TrendingUp,
  Award,
  AlertCircle
} from "lucide-react";
import Image from "next/image";
import Link from "next/link";

// Mock Data for the Explanation
const explanationData = {
  taskId: "T-1042",
  title: "Implement OAuth2 Authentication",
  developer: {
    name: "Alex Chen",
    role: "Senior Full Stack",
    avatar: "https://i.pravatar.cc/150?img=11",
  },
  overallMatch: 94,
  primaryReason: "Alex has a perfect skill match for Security and OAuth2, with a strong historical track record of delivering similar authentication modules ahead of schedule.",
  skillMatch: [
    { skill: "OAuth2", required: 90, actual: 95 },
    { skill: "Security Concepts", required: 85, actual: 92 },
    { skill: "Node.js", required: 70, actual: 88 },
    { skill: "React", required: 60, actual: 85 },
  ],
  workload: {
    currentLoad: 65,
    taskImpact: 15, // Adds 15% to workload
    projectedLoad: 80,
    optimalThreshold: 85
  },
  historical: {
    similarTasksCompleted: 12,
    avgCompletionTime: "3.2 Days",
    qualityScore: 98,
    reworkRate: "1.2%"
  }
};

export default function ExplanationPage({ params }: { params: { taskId: string } }) {
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
      <div className="max-w-5xl mx-auto space-y-8">
        
        {/* Header Navigation */}
        <Link 
          href="/ai-allocation" 
          className="inline-flex items-center text-sm text-slate-400 hover:text-primary transition-colors mb-4"
        >
          <ArrowLeft className="w-4 h-4 mr-2" />
          Back to Allocations
        </Link>

        <div className="flex flex-col md:flex-row gap-6 items-start justify-between">
          <div>
            <div className="flex items-center gap-3 mb-2">
              <span className="text-xs font-mono px-2 py-1 rounded bg-primary/10 text-primary border border-primary/20">
                {explanationData.taskId}
              </span>
              <span className="text-sm text-slate-400 flex items-center">
                <BrainCircuit className="w-4 h-4 mr-1 text-emerald-400" />
                AI Confidence: High
              </span>
            </div>
            <h1 className="text-3xl font-bold text-foreground mb-1">{explanationData.title}</h1>
            <p className="text-slate-400 text-lg">Allocation Reasoning Report</p>
          </div>

          <div className="flex items-center gap-4 bg-slate-900/50 p-4 rounded-xl border border-card-border">
            <div className="relative w-12 h-12 rounded-full overflow-hidden border-2 border-emerald-500/50">
              <Image src={explanationData.developer.avatar} alt={explanationData.developer.name} fill className="object-cover" />
            </div>
            <div>
              <div className="text-sm text-slate-400">Allocated to</div>
              <div className="text-lg font-semibold text-foreground">{explanationData.developer.name}</div>
            </div>
            <div className="ml-4 pl-4 border-l border-slate-700 text-right">
              <div className="text-3xl font-bold text-emerald-400">{explanationData.overallMatch}%</div>
              <div className="text-xs text-slate-500 uppercase font-semibold">Match Score</div>
            </div>
          </div>
        </div>

        {/* Primary Reason */}
        <motion.div 
          initial={{ opacity: 0, y: 10 }}
          animate={{ opacity: 1, y: 0 }}
          className="glass p-6 rounded-xl border border-emerald-500/30 bg-emerald-500/5 relative overflow-hidden"
        >
          <div className="absolute top-0 left-0 w-1 h-full bg-emerald-500" />
          <h2 className="text-lg font-semibold text-emerald-400 flex items-center mb-2">
            <Award className="w-5 h-5 mr-2" />
            Why {explanationData.developer.name.split(' ')[0]} was selected
          </h2>
          <p className="text-slate-300 leading-relaxed text-lg">
            {explanationData.primaryReason}
          </p>
        </motion.div>

        {/* Detailed Breakdown Grids */}
        <motion.div 
          variants={container}
          initial="hidden"
          animate="show"
          className="grid grid-cols-1 md:grid-cols-2 gap-6"
        >
          {/* Skill Match Breakdown */}
          <motion.div variants={item} className="glass p-6 rounded-xl border border-card-border">
            <h3 className="text-lg font-semibold flex items-center mb-6">
              <Target className="w-5 h-5 mr-2 text-primary" />
              Skill Match Breakdown
            </h3>
            <div className="space-y-5">
              {explanationData.skillMatch.map((skill, idx) => (
                <div key={idx}>
                  <div className="flex justify-between text-sm mb-1.5">
                    <span className="font-medium text-slate-300">{skill.skill}</span>
                    <span className="text-slate-400 text-xs">Required: {skill.required}</span>
                  </div>
                  <div className="relative h-2.5 bg-slate-800 rounded-full overflow-hidden">
                    {/* Required Indicator Line */}
                    <div className="absolute top-0 bottom-0 w-0.5 bg-slate-500 z-10" style={{ left: `${skill.required}%` }} />
                    {/* Actual Skill Bar */}
                    <motion.div 
                      initial={{ width: 0 }}
                      animate={{ width: `${skill.actual}%` }}
                      transition={{ duration: 1, delay: 0.2 + (idx * 0.1) }}
                      className={`absolute top-0 bottom-0 left-0 rounded-full ${
                        skill.actual >= skill.required ? 'bg-emerald-500' : 'bg-yellow-500'
                      }`}
                    />
                  </div>
                  <div className="text-right text-xs mt-1 font-medium text-foreground">
                    {skill.actual} / 100
                  </div>
                </div>
              ))}
            </div>
          </motion.div>

          <div className="space-y-6">
            {/* Workload Consideration */}
            <motion.div variants={item} className="glass p-6 rounded-xl border border-card-border">
              <h3 className="text-lg font-semibold flex items-center mb-6">
                <Activity className="w-5 h-5 mr-2 text-blue-400" />
                Workload Consideration
              </h3>
              
              <div className="mb-4">
                <div className="flex justify-between text-sm mb-2">
                  <span className="text-slate-400">Current Load vs. Projected</span>
                  <span className="font-mono text-blue-400">{explanationData.workload.projectedLoad}%</span>
                </div>
                <div className="h-4 bg-slate-800 rounded-full overflow-hidden flex">
                  <div className="h-full bg-slate-600" style={{ width: `${explanationData.workload.currentLoad}%` }} />
                  <motion.div 
                    initial={{ width: 0 }}
                    animate={{ width: `${explanationData.workload.taskImpact}%` }}
                    transition={{ duration: 1, delay: 0.5 }}
                    className="h-full bg-blue-500"
                  />
                </div>
                <div className="flex justify-between text-xs mt-2 text-slate-500">
                  <span>0%</span>
                  <span className="text-yellow-500">Optimal ({explanationData.workload.optimalThreshold}%)</span>
                  <span>100%</span>
                </div>
              </div>

              <div className="flex items-start gap-3 bg-blue-500/10 border border-blue-500/20 rounded-lg p-3">
                <AlertCircle className="w-5 h-5 text-blue-400 shrink-0 mt-0.5" />
                <p className="text-sm text-slate-300">
                  Adding this task will increase {explanationData.developer.name.split(' ')[0]}&apos;s workload to {explanationData.workload.projectedLoad}%, keeping them just under the optimal threshold, preventing burnout while maximizing utilization.
                </p>
              </div>
            </motion.div>

            {/* Historical Performance Influence */}
            <motion.div variants={item} className="glass p-6 rounded-xl border border-card-border">
              <h3 className="text-lg font-semibold flex items-center mb-4">
                <History className="w-5 h-5 mr-2 text-purple-400" />
                Historical Performance Influence
              </h3>
              
              <div className="grid grid-cols-2 gap-4">
                <div className="bg-slate-900/50 p-4 rounded-lg">
                  <div className="text-xs text-slate-400 mb-1">Similar Tasks</div>
                  <div className="text-2xl font-bold text-foreground">{explanationData.historical.similarTasksCompleted}</div>
                </div>
                <div className="bg-slate-900/50 p-4 rounded-lg">
                  <div className="text-xs text-slate-400 mb-1">Avg Completion Time</div>
                  <div className="text-2xl font-bold text-foreground">{explanationData.historical.avgCompletionTime}</div>
                </div>
                <div className="bg-slate-900/50 p-4 rounded-lg">
                  <div className="text-xs text-slate-400 mb-1">Quality Score</div>
                  <div className="text-2xl font-bold text-emerald-400">{explanationData.historical.qualityScore}/100</div>
                </div>
                <div className="bg-slate-900/50 p-4 rounded-lg">
                  <div className="text-xs text-slate-400 mb-1">Rework Rate</div>
                  <div className="text-2xl font-bold text-purple-400">{explanationData.historical.reworkRate}</div>
                </div>
              </div>
            </motion.div>
          </div>
        </motion.div>

      </div>
    </div>
  );
}
