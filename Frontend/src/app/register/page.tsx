"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { motion } from "framer-motion";
import { 
  User, 
  Mail, 
  Lock, 
  Briefcase, 
  ArrowRight, 
  CheckCircle2, 
  Sparkles,
  ChevronRight
} from "lucide-react";

export default function RegisterPage() {
  const router = useRouter();
  const [formData, setFormData] = useState({
    firstName: "",
    lastName: "",
    email: "",
    password: "",
    specialization: "Fullstack Developer",
    phoneNumber: ""
  });
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    
    // Store in localStorage for the next step (onboarding)
    localStorage.setItem("registration_data", JSON.stringify(formData));
    
    // Simulate a brief delay for UX
    setTimeout(() => {
      setLoading(false);
      router.push("/register/setup");
    }, 1000);
  };

  return (
    <div className="min-h-screen bg-background flex flex-col lg:flex-row">
      {/* Left Side: Branding & Info */}
      <div className="lg:w-1/2 relative overflow-hidden bg-slate-950 flex flex-col justify-center px-12 py-20 border-r border-slate-800/50">
        <div className="absolute top-[-10%] left-[-10%] w-[60%] h-[60%] rounded-full bg-primary/10 blur-[120px] animate-pulse-slow" />
        <div className="absolute bottom-[-10%] right-[-10%] w-[60%] h-[60%] rounded-full bg-secondary/10 blur-[120px] animate-pulse-slow" />
        
        <motion.div
          initial={{ opacity: 0, x: -20 }}
          animate={{ opacity: 1, x: 0 }}
          transition={{ duration: 0.6 }}
          className="relative z-10"
        >
          <div className="inline-flex items-center space-x-2 text-primary mb-8">
            <div className="w-10 h-10 rounded-xl bg-primary/20 flex items-center justify-center">
              <Sparkles className="w-6 h-6" />
            </div>
            <span className="text-2xl font-bold tracking-tight">Agile Optimizer</span>
          </div>

          <h1 className="text-5xl font-bold text-white mb-6 leading-tight">
            Join the next generation of <br />
            <span className="text-gradient">Resource Optimization.</span>
          </h1>
          
          <p className="text-slate-400 text-lg mb-12 max-w-md">
            Create your account to unlock AI-driven task allocation, 
            personalized performance insights, and seamless sprint management.
          </p>

          <div className="space-y-6">
            {[
              "AI-Powered Skill Matching",
              "Dynamic Workload Balancing",
              "Predictive Sprint Analytics",
              "Personalized Developer Profiles"
            ].map((feature, i) => (
              <div key={i} className="flex items-center space-x-3 text-slate-300">
                <div className="w-5 h-5 rounded-full bg-emerald-500/20 flex items-center justify-center text-emerald-500">
                  <CheckCircle2 className="w-3.5 h-3.5" />
                </div>
                <span className="font-medium">{feature}</span>
              </div>
            ))}
          </div>
        </motion.div>
      </div>

      {/* Right Side: Form */}
      <div className="lg:w-1/2 flex flex-col justify-center items-center px-6 py-20 bg-slate-950/20">
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6, delay: 0.2 }}
          className="w-full max-w-md"
        >
          <div className="text-center mb-10">
            <h2 className="text-3xl font-bold mb-3 text-white">Create Account</h2>
            <p className="text-slate-400">Step 1 of 2: Basic Information</p>
          </div>

          <form onSubmit={handleSubmit} className="space-y-5">
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <label className="text-sm font-medium text-slate-400 ml-1">First Name</label>
                <div className="relative group">
                  <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-slate-500 group-focus-within:text-primary transition-colors">
                    <User className="w-4.5 h-4.5" />
                  </div>
                  <input
                    type="text"
                    required
                    className="w-full pl-10 pr-4 py-3 bg-slate-900/50 border border-slate-800 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all text-white placeholder:text-slate-600"
                    placeholder="John"
                    value={formData.firstName}
                    onChange={(e) => setFormData({ ...formData, firstName: e.target.value })}
                  />
                </div>
              </div>
              <div className="space-y-2">
                <label className="text-sm font-medium text-slate-400 ml-1">Last Name</label>
                <div className="relative group">
                  <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-slate-500 group-focus-within:text-primary transition-colors">
                    <User className="w-4.5 h-4.5" />
                  </div>
                  <input
                    type="text"
                    required
                    className="w-full pl-10 pr-4 py-3 bg-slate-900/50 border border-slate-800 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all text-white placeholder:text-slate-600"
                    placeholder="Doe"
                    value={formData.lastName}
                    onChange={(e) => setFormData({ ...formData, lastName: e.target.value })}
                  />
                </div>
              </div>
            </div>

            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-400 ml-1">Email Address</label>
              <div className="relative group">
                <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-slate-500 group-focus-within:text-primary transition-colors">
                  <Mail className="w-4.5 h-4.5" />
                </div>
                <input
                  type="email"
                  required
                  className="w-full pl-10 pr-4 py-3 bg-slate-900/50 border border-slate-800 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all text-white placeholder:text-slate-600"
                  placeholder="john@example.com"
                  value={formData.email}
                  onChange={(e) => setFormData({ ...formData, email: e.target.value })}
                />
              </div>
            </div>

            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-400 ml-1">Password</label>
              <div className="relative group">
                <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-slate-500 group-focus-within:text-primary transition-colors">
                  <Lock className="w-4.5 h-4.5" />
                </div>
                <input
                  type="password"
                  required
                  className="w-full pl-10 pr-4 py-3 bg-slate-900/50 border border-slate-800 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all text-white placeholder:text-slate-600"
                  placeholder="••••••••"
                  value={formData.password}
                  onChange={(e) => setFormData({ ...formData, password: e.target.value })}
                />
              </div>
            </div>

            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-400 ml-1">Specialization</label>
              <div className="relative group">
                <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-slate-500 group-focus-within:text-primary transition-colors">
                  <Briefcase className="w-4.5 h-4.5" />
                </div>
                <select
                  className="w-full pl-10 pr-4 py-3 bg-slate-900/50 border border-slate-800 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all text-white appearance-none cursor-pointer"
                  value={formData.specialization}
                  onChange={(e) => setFormData({ ...formData, specialization: e.target.value })}
                >
                  <option value="Fullstack Developer">Fullstack Developer</option>
                  <option value="Backend Engineer">Backend Engineer</option>
                  <option value="Frontend Developer">Frontend Developer</option>
                  <option value="Data Scientist">Data Scientist</option>
                  <option value="DevOps Engineer">DevOps Engineer</option>
                  <option value="UI/UX Designer">UI/UX Designer</option>
                </select>
                <div className="absolute inset-y-0 right-0 pr-3.5 flex items-center pointer-events-none text-slate-500">
                  <ChevronRight className="w-4 h-4 rotate-90" />
                </div>
              </div>
            </div>

            <button
              type="submit"
              disabled={loading}
              className="w-full py-4 mt-4 bg-primary hover:bg-primary-dark text-white font-bold rounded-xl shadow-lg shadow-primary/20 transition-all flex items-center justify-center space-x-2 group disabled:opacity-70"
            >
              <span>{loading ? "Creating Account..." : "Continue to Profile Setup"}</span>
              {!loading && <ArrowRight className="w-5 h-5 group-hover:translate-x-1 transition-transform" />}
            </button>

            <p className="text-center text-slate-500 text-sm mt-6">
              Already have an account?{" "}
              <Link href="/login" className="text-primary hover:underline font-semibold">
                Sign in
              </Link>
            </p>
          </form>
        </motion.div>
      </div>
    </div>
  );
}
