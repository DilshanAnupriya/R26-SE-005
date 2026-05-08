"use client";

import Link from "next/link";
import { motion } from "framer-motion";
import { 
  Users, 
  BarChart3, 
  Target, 
  Zap, 
  ArrowRight,
  LayoutDashboard,
  Cpu,
  ShieldCheck
} from "lucide-react";

export default function Home() {
  const container = {
    hidden: { opacity: 0 },
    show: {
      opacity: 1,
      transition: {
        staggerChildren: 0.1
      }
    }
  };

  const item = {
    hidden: { y: 20, opacity: 0 },
    show: { y: 0, opacity: 1 }
  };

  return (
    <div className="relative overflow-hidden bg-background">
      {/* Background Blobs */}
      <div className="absolute top-[-10%] left-[-10%] w-[40%] h-[40%] rounded-full bg-primary/20 blur-[120px] animate-pulse-slow" />
      <div className="absolute bottom-[-10%] right-[-10%] w-[40%] h-[40%] rounded-full bg-secondary/20 blur-[120px] animate-pulse-slow" />

      {/* Hero Section */}
      <section className="relative pt-24 pb-16 px-6 sm:pt-32 sm:pb-24">
        <div className="container mx-auto text-center">
          <motion.div
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.5 }}
          >
            <span className="inline-flex items-center rounded-full px-4 py-1 text-sm font-medium bg-primary/10 text-primary border border-primary/20 mb-6">
              <Zap className="w-4 h-4 mr-2" />
              Powered by AI Optimization
            </span>
            <h1 className="text-5xl sm:text-7xl font-bold tracking-tight mb-8">
              Maximize Your Team&apos;s <br />
              <span className="text-gradient">Potential</span>
            </h1>
            <p className="max-w-2xl mx-auto text-lg text-slate-400 mb-10">
              The intelligent resource allocation system that optimizes developer tasks, 
              sprint planning, and team performance through advanced machine learning algorithms.
            </p>
            <div className="flex flex-wrap justify-center gap-4">
              <Link 
                href="/allocation"
                className="px-8 py-3 rounded-lg bg-primary hover:bg-primary-dark text-white font-semibold transition-all flex items-center shadow-lg shadow-primary/25"
              >
                Go to Dashboard
                <ArrowRight className="ml-2 w-5 h-5" />
              </Link>
              <button className="px-8 py-3 rounded-lg glass glass-hover text-white font-semibold transition-all">
                View Live Demo
              </button>
            </div>
          </motion.div>
        </div>
      </section>

      {/* Dashboard Preview Section */}
      <section className="py-16 px-6">
        <div className="container mx-auto">
          <motion.div 
            initial={{ opacity: 0, scale: 0.95 }}
            whileInView={{ opacity: 1, scale: 1 }}
            viewport={{ once: true }}
            className="glass rounded-2xl p-4 sm:p-8 shadow-2xl relative overflow-hidden"
          >
            <div className="flex items-center justify-between mb-8 border-b border-card-border pb-4">
              <div className="flex items-center space-x-2">
                <LayoutDashboard className="text-primary w-6 h-6" />
                <h2 className="text-xl font-semibold">Optimization Dashboard</h2>
              </div>
              <div className="flex space-x-2">
                <div className="w-3 h-3 rounded-full bg-red-500/50" />
                <div className="w-3 h-3 rounded-full bg-yellow-500/50" />
                <div className="w-3 h-3 rounded-full bg-green-500/50" />
              </div>
            </div>
            
            <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
              {/* Mock Stats */}
              {[
                { label: "Resource Efficiency", value: "94.2%", icon: BarChart3, color: "text-emerald-500" },
                { label: "Active Developers", value: "24", icon: Users, color: "text-blue-500" },
                { label: "Task Velocity", value: "+18%", icon: Zap, color: "text-yellow-500" },
              ].map((stat, i) => (
                <div key={i} className="bg-slate-900/50 rounded-xl p-6 border border-card-border">
                  <div className="flex items-center justify-between mb-4">
                    <stat.icon className={`${stat.color} w-6 h-6`} />
                    <span className="text-xs font-medium text-slate-500 uppercase">Current</span>
                  </div>
                  <div className="text-3xl font-bold">{stat.value}</div>
                  <div className="text-sm text-slate-400 mt-1">{stat.label}</div>
                </div>
              ))}
            </div>

            <div className="mt-8 p-6 bg-slate-900/50 rounded-xl border border-card-border">
              <div className="h-48 flex items-center justify-center text-slate-500 italic">
                {/* Visual Placeholder for a Chart */}
                <div className="flex items-end space-x-2 h-32 w-full max-w-md">
                  {[40, 70, 45, 90, 65, 80, 50, 95].map((h, i) => (
                    <motion.div 
                      key={i}
                      initial={{ height: 0 }}
                      whileInView={{ height: `${h}%` }}
                      transition={{ delay: i * 0.1, duration: 1 }}
                      className="flex-grow bg-primary/40 rounded-t-sm" 
                    />
                  ))}
                </div>
              </div>
              <div className="text-center mt-4 text-sm font-medium text-slate-400">
                AI Optimization Progress (Sprint 24)
              </div>
            </div>
          </motion.div>
        </div>
      </section>

      {/* Features Grid */}
      <section className="py-24 px-6 bg-slate-950/50">
        <div className="container mx-auto">
          <div className="text-center mb-16">
            <h2 className="text-3xl sm:text-4xl font-bold mb-4">Everything you need to <span className="text-gradient">Succeed</span></h2>
            <p className="text-slate-400 max-w-xl mx-auto"> Powerful tools designed to streamline your development workflow and maximize output.</p>
          </div>

          <motion.div 
            variants={container}
            initial="hidden"
            whileInView="show"
            viewport={{ once: true }}
            className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-8"
          >
            {[
              { title: "Intelligent Matching", desc: "AI algorithms match tasks to the best available developer based on skill and load.", icon: Cpu },
              { title: "Resource Tracking", desc: "Real-time visibility into your team&apos;s capacity and workload distribution.", icon: Target },
              { title: "Secure & Scalable", desc: "Enterprise-grade security ensuring your project data is always protected.", icon: ShieldCheck },
              { title: "Smart Sprints", desc: "Automatically generate optimized sprint plans that meet your deadlines.", icon: Zap },
            ].map((feature, i) => (
              <motion.div 
                key={i}
                variants={item}
                className="glass p-8 rounded-2xl glass-hover group"
              >
                <div className="w-12 h-12 rounded-xl bg-primary/10 flex items-center justify-center mb-6 group-hover:bg-primary/20 transition-colors">
                  <feature.icon className="text-primary w-6 h-6" />
                </div>
                <h3 className="text-xl font-semibold mb-3">{feature.title}</h3>
                <p className="text-slate-400 text-sm leading-relaxed">{feature.desc}</p>
              </motion.div>
            ))}
          </motion.div>
        </div>
      </section>

      {/* CTA Section */}
      <section className="py-24 px-6 relative">
        <div className="container mx-auto max-w-4xl glass p-12 rounded-3xl text-center relative overflow-hidden">
          <div className="absolute inset-0 bg-gradient-to-br from-primary/10 to-secondary/10 -z-10" />
          <h2 className="text-3xl sm:text-5xl font-bold mb-6">Ready to optimize your workflow?</h2>
          <p className="text-slate-400 mb-10 text-lg">Join hundreds of agile teams already delivering projects faster with our AI-powered platform.</p>
          <button className="px-10 py-4 rounded-xl bg-white text-black font-bold hover:bg-slate-100 transition-all shadow-xl shadow-white/10">
            Start Your Journey
          </button>
        </div>
      </section>
    </div>
  );
}
