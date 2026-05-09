"use client";

import { motion } from "framer-motion";
import { 
  Bot, 
  ArrowRight, 
  CheckCircle2, 
  AlertCircle,
  Clock,
  Briefcase
} from "lucide-react";
import Image from "next/image";
import Link from "next/link";

// Mock Data for AI Allocations
const allocations = [
  {
    taskId: "T-1042",
    title: "Implement OAuth2 Authentication",
    priority: "High",
    complexity: "8 Story Points",
    recommendedDeveloper: {
      id: 1,
      name: "Alex Chen",
      role: "Senior Full Stack",
      avatar: "https://i.pravatar.cc/150?img=11",
      matchScore: 94
    },
    status: "Pending Approval",
    reasoning: "Alex has high proficiency in OAuth2 and Security implementations.",
    alternatives: 4
  },
  {
    taskId: "T-1045",
    title: "Optimize Database Queries for Dashboard",
    priority: "High",
    complexity: "5 Story Points",
    recommendedDeveloper: {
      id: 3,
      name: "David Kumar",
      role: "Backend Architect",
      avatar: "https://i.pravatar.cc/150?img=12",
      matchScore: 88
    },
    status: "Approved",
    reasoning: "David has previously optimized the core analytics pipeline.",
    alternatives: 2
  },
  {
    taskId: "T-1048",
    title: "Design New Analytics Components",
    priority: "Medium",
    complexity: "3 Story Points",
    recommendedDeveloper: {
      id: 2,
      name: "Sarah Miller",
      role: "Frontend Engineer",
      avatar: "https://i.pravatar.cc/150?img=5",
      matchScore: 98
    },
    status: "Pending Approval",
    reasoning: "Sarah is currently the domain expert for the frontend UI library.",
    alternatives: 3
  },
  {
    taskId: "T-1050",
    title: "Setup CI/CD for Staging Environment",
    priority: "Critical",
    complexity: "8 Story Points",
    recommendedDeveloper: {
      id: 5,
      name: "James Wilson",
      role: "DevOps Engineer",
      avatar: "https://i.pravatar.cc/150?img=3",
      matchScore: 91
    },
    status: "Approved",
    reasoning: "James is the only available DevOps engineer with required AWS permissions.",
    alternatives: 1
  }
];

export default function AIAllocationPage() {
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
      <div className="max-w-6xl mx-auto space-y-8">
        
        {/* Header */}
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
          <div>
            <h1 className="text-3xl font-bold text-foreground flex items-center">
              <Bot className="w-8 h-8 text-primary mr-3" />
              AI Task Allocation
            </h1>
            <p className="text-slate-400 mt-2 max-w-2xl">
              Review task assignments recommended by our optimization engine. The AI balances skill match, current workload, and historical project performance to suggest the ideal developer.
            </p>
          </div>
          
          <button className="px-5 py-2.5 bg-primary hover:bg-primary-dark text-white rounded-lg text-sm font-medium transition-colors shadow-lg shadow-primary/20 flex items-center gap-2 whitespace-nowrap">
            <CheckCircle2 className="w-4 h-4" />
            Approve All Pending
          </button>
        </div>

        {/* List */}
        <motion.div 
          variants={container}
          initial="hidden"
          animate="show"
          className="space-y-4"
        >
          {allocations.map((alloc) => (
            <motion.div 
              key={alloc.taskId} 
              variants={item} 
              className="glass p-6 rounded-xl border border-card-border hover:border-primary/40 transition-all group"
            >
              <div className="flex flex-col lg:flex-row items-start lg:items-center justify-between gap-6">
                
                {/* Task Details */}
                <div className="flex-1">
                  <div className="flex items-center gap-3 mb-2">
                    <span className="text-xs font-mono px-2 py-1 rounded bg-slate-800 text-slate-300 border border-slate-700">
                      {alloc.taskId}
                    </span>
                    <span className={`text-xs px-2 py-1 rounded-full font-medium ${
                      alloc.priority === 'Critical' ? 'bg-red-500/10 text-red-500 border border-red-500/20' :
                      alloc.priority === 'High' ? 'bg-orange-500/10 text-orange-500 border border-orange-500/20' :
                      'bg-blue-500/10 text-blue-500 border border-blue-500/20'
                    }`}>
                      {alloc.priority} Priority
                    </span>
                    <span className="flex items-center text-xs text-slate-400">
                      <Clock className="w-3 h-3 mr-1" />
                      {alloc.complexity}
                    </span>
                  </div>
                  <h3 className="text-xl font-semibold text-foreground mb-1">{alloc.title}</h3>
                  <div className="flex items-start gap-2 text-sm text-slate-400">
                    <Briefcase className="w-4 h-4 mt-0.5 shrink-0" />
                    <span>{alloc.reasoning}</span>
                  </div>
                </div>

                {/* Arrow connector on desktop */}
                <div className="hidden lg:flex items-center justify-center w-12 text-slate-600">
                  <ArrowRight className="w-6 h-6 group-hover:text-primary transition-colors" />
                </div>

                {/* Recommended Dev */}
                <div className="w-full lg:w-[350px] bg-slate-900/40 rounded-lg p-4 border border-card-border flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    <div className="relative w-10 h-10 rounded-full overflow-hidden border border-slate-700">
                      <Image src={alloc.recommendedDeveloper.avatar} alt={alloc.recommendedDeveloper.name} fill className="object-cover" />
                    </div>
                    <div>
                      <div className="text-sm font-medium text-foreground">{alloc.recommendedDeveloper.name}</div>
                      <div className="text-xs text-slate-400">{alloc.recommendedDeveloper.role}</div>
                    </div>
                  </div>
                  
                  <div className="text-right">
                    <div className="text-2xl font-bold text-emerald-400">{alloc.recommendedDeveloper.matchScore}%</div>
                    <div className="text-[10px] text-slate-500 uppercase font-semibold">Match Score</div>
                    {alloc.alternatives > 0 && (
                      <div className="text-xs text-blue-400 mt-1">+{alloc.alternatives} Alternatives</div>
                    )}
                  </div>
                </div>

                {/* Actions */}
                <div className="flex lg:flex-col gap-3 w-full lg:w-auto mt-4 lg:mt-0">
                  <Link 
                    href={`/ai-allocation/${alloc.taskId}/explanation`}
                    className="flex-1 lg:flex-none text-center px-4 py-2 bg-slate-800 hover:bg-slate-700 text-white rounded-lg text-sm font-medium transition-colors border border-slate-700 hover:border-slate-600"
                  >
                    View Details & Alternatives
                  </Link>
                  {alloc.status === 'Pending Approval' ? (
                    <button className="flex-1 lg:flex-none flex items-center justify-center gap-2 px-4 py-2 bg-primary/20 text-primary hover:bg-primary/30 rounded-lg text-sm font-medium transition-colors border border-primary/30">
                      <CheckCircle2 className="w-4 h-4" />
                      Approve
                    </button>
                  ) : (
                    <div className="flex-1 lg:flex-none flex items-center justify-center gap-2 px-4 py-2 text-emerald-500 rounded-lg text-sm font-medium">
                      <CheckCircle2 className="w-4 h-4" />
                      Approved
                    </div>
                  )}
                </div>

              </div>
            </motion.div>
          ))}
        </motion.div>

      </div>
    </div>
  );
}
