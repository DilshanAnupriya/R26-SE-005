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
  AlertCircle,
  AlertTriangle,
  Loader2,
  CheckCircle2
} from "lucide-react";
import Image from "next/image";
import Link from "next/link";
import { useState, useEffect, use } from "react";

export default function ExplanationPage({ params }: { params: Promise<{ taskId: string }> }) {
  const unwrappedParams = use(params);
  const taskId = unwrappedParams.taskId;
  
  const [allocation, setAllocation] = useState<any>(null);
  const [explanation, setExplanation] = useState<any>(null);
  const [alternatives, setAlternatives] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchData = async () => {
      try {
        // 1. Fetch current allocation directly by ID
        const allocRes = await fetch(`http://localhost:8080/api/allocation/${taskId}`);
        if (!allocRes.ok) throw new Error("Allocation not found");
        const currentAlloc = await allocRes.json();
        setAllocation(currentAlloc);

        // 2. Fetch all other developers ranked for this specific task
        const taskIdRaw = currentAlloc.task?.id;
        if (taskIdRaw) {
          const peersRes = await fetch(`http://localhost:8080/api/allocation/task/${taskIdRaw}`);
          const peers = await peersRes.json();
          
          const taskAlts = peers.filter((a: any) => a.id !== currentAlloc.id);
          taskAlts.sort((a: any, b: any) => b.finalScore - a.finalScore);
          setAlternatives(taskAlts);
        }

        // 3. Fetch AI explanation breakdown
        const explRes = await fetch(`http://localhost:8080/api/rank/explain/${taskId}`, {
          method: "POST",
          headers: { "Content-Type": "application/json" }
        });
        const explData = await explRes.json();
        setExplanation(explData.explanation);

      } catch (error) {
        console.error("Error fetching explanation:", error);
      } finally {
        setLoading(false);
      }
    };
    fetchData();
  }, [taskId]);

  const container = {
    hidden: { opacity: 0 },
    show: { opacity: 1, transition: { staggerChildren: 0.1 } }
  };

  const item = {
    hidden: { y: 20, opacity: 0 },
    show: { y: 0, opacity: 1 }
  };

  if (loading) {
    return (
      <div className="min-h-screen bg-background p-6 lg:p-10 flex items-center justify-center">
        <Loader2 className="w-8 h-8 animate-spin text-primary" />
      </div>
    );
  }

  if (!allocation || !explanation) {
    return (
      <div className="min-h-screen bg-background p-6 lg:p-10 flex items-center justify-center text-white">
        Error loading explanation.
      </div>
    );
  }

  const mlPred = explanation.breakdown.ml_prediction;
  const skillMatch = explanation.breakdown.skill_match;
  const workloadBal = explanation.breakdown.workload_balance;
  const profile = allocation.developer?.profile;
  const devName = profile
    ? `${profile.firstName ?? ''} ${profile.lastName ?? ''}`.trim()
    : allocation.developer?.dev_id;
  const avatar = `https://ui-avatars.com/api/?name=${encodeURIComponent(devName || allocation.developer.dev_id)}&background=random`;

  return (
    <div className="min-h-screen bg-background p-6 lg:p-10">
      <div className="max-w-5xl mx-auto space-y-8">
        
        <Link 
          href="/allocation?tab=allocation" 
          className="inline-flex items-center text-sm text-slate-400 hover:text-primary transition-colors mb-4"
        >
          <ArrowLeft className="w-4 h-4 mr-2" />
          Back to Allocations
        </Link>

        <div className="flex flex-col md:flex-row gap-6 items-start justify-between">
          <div>
            <div className="flex items-center gap-3 mb-2">
              <span className="text-xs font-mono px-2 py-1 rounded bg-primary/10 text-primary border border-primary/20">
                T-{allocation.task.id}
              </span>
              <span className="text-sm text-slate-400 flex items-center">
                <BrainCircuit className="w-4 h-4 mr-1 text-emerald-400" />
                AI Confidence: {explanation.recommendation_confidence?.level || 'High'}
              </span>
            </div>
            <h1 className="text-3xl font-bold text-foreground mb-1">{allocation.task.task_type}</h1>
            <p className="text-slate-400 text-lg">Allocation Reasoning Report</p>
          </div>

          <div className="flex items-center gap-4 bg-slate-900/50 p-4 rounded-xl border border-card-border">
            <div className="relative w-12 h-12 rounded-full overflow-hidden border-2 border-emerald-500/50">
              <Image src={avatar} alt={devName} fill unoptimized className="object-cover" />
            </div>
            <div>
              <div className="text-sm text-slate-400">Allocated to</div>
              <div className="text-lg font-semibold text-foreground flex items-center gap-2">
                {devName}
                <span className="text-xs font-bold px-2 py-0.5 rounded-md bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                  Rank #{allocation.rankPosition}
                </span>
              </div>
            </div>
            <div className="ml-4 pl-4 border-l border-slate-700 text-right">
              <div className="text-3xl font-bold text-emerald-400">{explanation.final_score}%</div>
              <div className="text-xs text-slate-500 uppercase font-semibold">Match Score</div>
            </div>
          </div>
        </div>

        {/* AI Executive Summary */}
        <motion.div 
          initial={{ opacity: 0, y: 10 }}
          animate={{ opacity: 1, y: 0 }}
          className="glass p-6 rounded-xl border border-emerald-500/30 bg-emerald-500/5 relative overflow-hidden"
        >
          <div className="absolute top-0 left-0 w-1 h-full bg-emerald-500" />
          <h2 className="text-lg font-semibold text-emerald-400 flex items-center mb-2">
            <BrainCircuit className="w-5 h-5 mr-2" />
            Model Reasoning Summary
          </h2>
          <p className="text-slate-300 leading-relaxed text-lg italic font-medium">
            "{explanation.summary}"
          </p>
        </motion.div>

        <motion.div 
          variants={container}
          initial="hidden"
          animate="show"
          className="grid grid-cols-1 md:grid-cols-2 gap-6"
        >
          {/* Detailed Score Breakdown */}
          <motion.div variants={item} className="glass p-6 rounded-xl border border-card-border">
            <h3 className="text-lg font-semibold flex items-center mb-6">
              <Target className="w-5 h-5 mr-2 text-primary" />
              Composite Score Components
            </h3>
            <div className="space-y-5">
              {[
                { label: "Skill Match", val: skillMatch.value * 100, weight: skillMatch.weight, expl: skillMatch.explanation },
                { label: "Workload Balance", val: workloadBal.value * 100, weight: workloadBal.weight, expl: workloadBal.explanation },
                { label: "ML Performance", val: mlPred.value, weight: mlPred.weight, expl: mlPred.explanation },
              ].map((metric, idx) => (
                <div key={idx}>
                  <div className="flex justify-between text-sm mb-1.5">
                    <span className="font-medium text-slate-300">{metric.label} ({metric.weight})</span>
                    <span className="text-slate-400 text-xs text-right max-w-[200px]">{metric.expl}</span>
                  </div>
                  <div className="relative h-2.5 bg-slate-800 rounded-full overflow-hidden">
                    <motion.div 
                      initial={{ width: 0 }}
                      animate={{ width: `${Math.min(metric.val, 100)}%` }}
                      transition={{ duration: 1, delay: 0.2 + (idx * 0.1) }}
                      className="absolute top-0 bottom-0 left-0 rounded-full bg-emerald-500"
                    />
                  </div>
                  <div className="text-right text-xs mt-1 font-medium text-foreground">
                    {metric.val.toFixed(1)} / 100
                  </div>
                </div>
              ))}
            </div>
            
            {/* AI Identified Strengths */}
            {explanation.strengths && explanation.strengths.length > 0 && (
              <div className="mt-8 pt-8 border-t border-slate-800">
                 <h4 className="text-sm font-semibold text-emerald-400 mb-4 uppercase tracking-wider">Top AI Strengths</h4>
                 <ul className="space-y-3">
                    {explanation.strengths.map((s: any, idx: number) => (
                      <li key={idx} className="flex items-start gap-3 text-sm text-slate-300 bg-emerald-500/5 p-3 rounded-lg border border-emerald-500/10">
                        <Award className="w-4 h-4 text-emerald-500 mt-0.5 shrink-0" />
                        <div>
                          <div className="font-bold text-white mb-0.5">{s.strength}</div>
                          <div className="text-slate-400 text-xs">{s.detail}</div>
                        </div>
                      </li>
                    ))}
                 </ul>
              </div>
            )}
          </motion.div>

          <div className="space-y-6">
            {/* AI Identified Concerns */}
            <motion.div variants={item} className="glass p-6 rounded-xl border border-card-border">
              <h3 className="text-lg font-semibold flex items-center mb-6">
                <AlertCircle className="w-5 h-5 mr-2 text-yellow-400" />
                Risk Assessment
              </h3>
              
              {explanation.concerns && explanation.concerns.length > 0 ? (
                <div className="space-y-4">
                  {explanation.concerns.map((c: any, idx: number) => (
                    <div key={idx} className={`p-4 rounded-lg border flex items-start gap-3 ${
                      c.severity === 'high' ? 'bg-red-500/10 border-red-500/20' : 'bg-yellow-500/10 border-yellow-500/20'
                    }`}>
                      <AlertTriangle className={`w-5 h-5 shrink-0 mt-0.5 ${c.severity === 'high' ? 'text-red-400' : 'text-yellow-400'}`} />
                      <div>
                        <div className="text-sm font-bold text-white mb-1">{c.concern}</div>
                        <div className="text-xs text-slate-400 leading-relaxed">{c.detail}</div>
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <div className="flex items-center gap-3 bg-emerald-500/10 border border-emerald-500/20 rounded-lg p-4">
                  <CheckCircle2 className="w-5 h-5 text-emerald-400" />
                  <p className="text-sm text-slate-300">No significant risks or constraints identified by the AI for this allocation.</p>
                </div>
              )}
            </motion.div>

            {/* AI Confidence & Features */}
            <motion.div variants={item} className="glass p-6 rounded-xl border border-card-border">
              <h3 className="text-lg font-semibold flex items-center mb-6">
                <BrainCircuit className="w-5 h-5 mr-2 text-purple-400" />
                Model Confidence
              </h3>
              
              <div className="grid grid-cols-2 gap-4">
                <div className="bg-slate-900/50 p-4 rounded-lg col-span-2">
                  <div className="text-xs text-slate-400 mb-1">Confidence Detail</div>
                  <div className="text-lg font-medium text-emerald-400">{explanation.recommendation_confidence?.explanation}</div>
                </div>
                <div className="bg-slate-900/50 p-4 rounded-lg">
                  <div className="text-xs text-slate-400 mb-1">Developer Availability</div>
                  <div className="text-2xl font-bold text-foreground">{allocation.developer.availability?.toFixed(1)}%</div>
                </div>
                <div className="bg-slate-900/50 p-4 rounded-lg">
                  <div className="text-xs text-slate-400 mb-1">Learning Rate</div>
                  <div className="text-2xl font-bold text-purple-400">{allocation.developer.learning_rate?.toFixed(3)}</div>
                </div>
              </div>
            </motion.div>
          </div>
        </motion.div>

        {alternatives.length > 0 && (
          <motion.div variants={item} className="mt-8 pt-8 border-t border-slate-800">
            <h3 className="text-xl font-semibold text-foreground mb-4">Alternative Recommendations</h3>
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
              {alternatives.map((alt: any) => {
                const altProfile = alt.developer?.profile;
                const altName = altProfile
                  ? `${altProfile.firstName ?? altProfile.first_name ?? ''} ${altProfile.lastName ?? altProfile.last_name ?? ''}`.trim()
                  : alt.developer?.dev_id;

                return (
                  <div key={alt.id} className="bg-slate-900/40 p-4 rounded-xl border border-slate-800 hover:border-slate-700 transition-colors">
                    <div className="flex items-center gap-3 mb-3">
                      <div className="relative w-10 h-10 rounded-full overflow-hidden border border-slate-700">
                        <Image 
                          src={`https://ui-avatars.com/api/?name=${encodeURIComponent(altName || alt.developer.dev_id)}&background=random`} 
                          alt={altName || alt.developer.dev_id} 
                          fill 
                          unoptimized 
                          className="object-cover" 
                        />
                      </div>
                      <div>
                        <div className="text-sm font-medium text-foreground flex items-center gap-2">
                          {altName || alt.developer.dev_id}
                          <span className="text-[10px] font-bold px-1.5 py-0.5 rounded bg-slate-800 text-slate-300 border border-slate-700">
                            #{alt.rankPosition}
                          </span>
                        </div>
                        <div className="text-xs text-emerald-400 font-semibold">{alt.finalScore}% Match Score</div>
                      </div>
                    </div>
                  <div className="text-xs text-slate-400 space-y-1">
                    <div className="flex justify-between">
                      <span>Skill Match:</span>
                      <span className="text-slate-300">{(alt.skillMatchScore * 100).toFixed(1)}%</span>
                    </div>
                    <div className="flex justify-between">
                      <span>Workload Bal:</span>
                      <span className="text-slate-300">{(alt.workloadBalance * 100).toFixed(1)}%</span>
                    </div>
                    <div className="flex justify-between">
                      <span>ML Pred:</span>
                      <span className="text-slate-300">{alt.mlScore.toFixed(1)}%</span>
                    </div>
                  </div>
                  <Link 
                    href={`/ai-allocation/${alt.id}/explanation`}
                    className="mt-4 block w-full text-center px-3 py-2 bg-slate-800 hover:bg-slate-700 text-white rounded-lg text-xs font-medium transition-colors border border-slate-700"
                  >
                    View Details
                  </Link>
                </div>
              );
            })}
          </div>
        </motion.div>
        )}

      </div>
    </div>
  );
}
