"use client";

import { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import { motion, AnimatePresence } from "framer-motion";
import { 
  Cpu, 
  Database, 
  Layout, 
  Award, 
  Phone, 
  Calendar,
  ArrowLeft,
  CheckCircle2,
  AlertCircle,
  Loader2,
  Wand2,
  Sparkles
} from "lucide-react";

export default function ProfileSetupPage() {
  const router = useRouter();
  const [prevData, setPrevData] = useState<any>(null);
  const [formData, setFormData] = useState({
    age: 25,
    phoneNumber: "",
    bio: "",
    devId: "DEV-" + Math.floor(1000 + Math.random() * 9000),
    experienceLevel: 3,
    skillFrontend: 5,
    skillBackend: 5,
    skillDb: 5,
    currentWorkload: 0,
    availability: 1.0,
    currentTasks: 0,
    consistency: 0.85,
    learningRate: 0.1
  });
  
  const [loading, setLoading] = useState(false);
  const [success, setSuccess] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    const data = localStorage.getItem("registration_data");
    if (!data) {
      router.push("/register");
      return;
    }
    setPrevData(JSON.parse(data));
  }, [router]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError("");

    const fullRequest = {
      ...prevData,
      ...formData
    };

    try {
      const response = await fetch("http://localhost:8080/api/developers", {
        method: "POST",
        headers: {
          "Content-Type": "application/json"
        },
        body: JSON.stringify(fullRequest)
      });

      if (response.ok) {
        setSuccess(true);
        localStorage.removeItem("registration_data");
        setTimeout(() => {
          router.push("/ai-allocation");
        }, 2000);
      } else {
        setError("Registration failed. Please try again.");
      }
    } catch (err) {
      setError("Server connection error. Please check if backend is running.");
    } finally {
      setLoading(false);
    }
  };

  const SkillSlider = ({ label, value, onChange, icon: Icon, color }: any) => (
    <div className="space-y-4 p-4 rounded-2xl bg-slate-900/50 border border-slate-800/50">
      <div className="flex items-center justify-between">
        <div className="flex items-center space-x-3">
          <div className={`w-8 h-8 rounded-lg ${color}/10 flex items-center justify-center ${color.replace('bg-', 'text-')}`}>
            <Icon className="w-5 h-5" />
          </div>
          <span className="font-medium text-slate-300">{label}</span>
        </div>
        <span className="text-xl font-bold text-white">{value}</span>
      </div>
      <input 
        type="range" 
        min="1" 
        max="10" 
        value={value}
        onChange={(e) => onChange(parseInt(e.target.value))}
        className="w-full h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer accent-primary"
      />
      <div className="flex justify-between text-[10px] text-slate-500 font-bold uppercase tracking-widest">
        <span>Novice</span>
        <span>Expert</span>
      </div>
    </div>
  );

  return (
    <div className="min-h-screen bg-background relative overflow-hidden flex items-center justify-center p-6">
      {/* Background Blobs */}
      <div className="absolute top-[-10%] left-[-10%] w-[40%] h-[40%] rounded-full bg-primary/10 blur-[120px]" />
      <div className="absolute bottom-[-10%] right-[-10%] w-[40%] h-[40%] rounded-full bg-secondary/10 blur-[120px]" />

      <motion.div
        initial={{ opacity: 0, scale: 0.95 }}
        animate={{ opacity: 1, scale: 1 }}
        className="w-full max-w-4xl glass rounded-3xl overflow-hidden shadow-2xl relative z-10"
      >
        <div className="flex flex-col md:flex-row min-h-[600px]">
          {/* Sidebar */}
          <div className="md:w-1/3 bg-slate-950/50 p-10 border-r border-slate-800/50 flex flex-col">
            <button 
              onClick={() => router.back()}
              className="flex items-center space-x-2 text-slate-400 hover:text-white transition-colors mb-12"
            >
              <ArrowLeft className="w-4 h-4" />
              <span className="text-sm font-medium">Go Back</span>
            </button>

            <div className="flex-grow">
              <div className="w-16 h-16 rounded-2xl bg-primary/20 flex items-center justify-center text-primary mb-6">
                <Wand2 className="w-8 h-8" />
              </div>
              <h2 className="text-2xl font-bold text-white mb-4">Complete Your Profile</h2>
              <p className="text-slate-400 text-sm leading-relaxed">
                Tell us about your technical skills and experience. This data helps our AI optimize your task assignments.
              </p>
            </div>

            <div className="space-y-4">
              <div className="flex items-center space-x-3 text-emerald-500">
                <CheckCircle2 className="w-5 h-5" />
                <span className="text-sm font-semibold">Account Verified</span>
              </div>
              <div className="flex items-center space-x-3 text-primary">
                <div className="w-5 h-5 rounded-full border-2 border-primary border-t-transparent animate-spin" />
                <span className="text-sm font-semibold">Building ML Profile</span>
              </div>
            </div>
          </div>

          {/* Form Area */}
          <div className="md:w-2/3 p-10 bg-slate-950/20">
            <AnimatePresence mode="wait">
              {success ? (
                <motion.div 
                  key="success"
                  initial={{ opacity: 0, y: 20 }}
                  animate={{ opacity: 1, y: 0 }}
                  className="h-full flex flex-col items-center justify-center text-center space-y-6"
                >
                  <div className="w-20 h-20 rounded-full bg-emerald-500/20 flex items-center justify-center text-emerald-500">
                    <CheckCircle2 className="w-10 h-10" />
                  </div>
                  <div>
                    <h3 className="text-3xl font-bold text-white mb-2">Registration Complete!</h3>
                    <p className="text-slate-400">Welcome to the team, {prevData?.firstName}. Redirecting you to the dashboard...</p>
                  </div>
                </motion.div>
              ) : (
                <motion.form 
                  key="form"
                  initial={{ opacity: 0 }}
                  animate={{ opacity: 1 }}
                  onSubmit={handleSubmit} 
                  className="space-y-8"
                >
                  {error && (
                    <div className="p-4 bg-red-500/10 border border-red-500/20 rounded-xl flex items-center space-x-3 text-red-500 text-sm">
                      <AlertCircle className="w-5 h-5 flex-shrink-0" />
                      <span>{error}</span>
                    </div>
                  )}

                  <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                    <div className="space-y-2">
                      <label className="text-sm font-medium text-slate-400 ml-1">Phone Number</label>
                      <div className="relative group">
                        <Phone className="absolute left-3.5 top-3.5 w-4.5 h-4.5 text-slate-500" />
                        <input
                          type="tel"
                          className="w-full pl-10 pr-4 py-3 bg-slate-900/50 border border-slate-800 rounded-xl focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all text-white"
                          placeholder="+1 (555) 000-0000"
                          value={formData.phoneNumber}
                          onChange={(e) => setFormData({ ...formData, phoneNumber: e.target.value })}
                        />
                      </div>
                    </div>
                    <div className="space-y-2">
                      <label className="text-sm font-medium text-slate-400 ml-1">Age</label>
                      <div className="relative group">
                        <Calendar className="absolute left-3.5 top-3.5 w-4.5 h-4.5 text-slate-500" />
                        <input
                          type="number"
                          className="w-full pl-10 pr-4 py-3 bg-slate-900/50 border border-slate-800 rounded-xl focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all text-white"
                          value={formData.age}
                          onChange={(e) => setFormData({ ...formData, age: parseInt(e.target.value) })}
                        />
                      </div>
                    </div>
                  </div>

                  <div className="space-y-2">
                    <label className="text-sm font-medium text-slate-400 ml-1">Short Bio</label>
                    <textarea
                      className="w-full px-4 py-3 bg-slate-900/50 border border-slate-800 rounded-xl focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all text-white min-h-[100px]"
                      placeholder="Tell us about your background and interests..."
                      value={formData.bio}
                      onChange={(e) => setFormData({ ...formData, bio: e.target.value })}
                    />
                  </div>

                  <div className="space-y-6">
                    <div className="flex items-center justify-between border-b border-slate-800 pb-2">
                      <h4 className="font-bold text-white uppercase tracking-wider text-xs">Technical Skill Proficiency</h4>
                      <div className="flex items-center space-x-2 text-primary">
                        <Award className="w-4 h-4" />
                        <span className="text-[10px] font-bold uppercase tracking-widest">Self-Assessment</span>
                      </div>
                    </div>
                    
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <SkillSlider 
                        label="Frontend" 
                        value={formData.skillFrontend} 
                        onChange={(val: any) => setFormData({...formData, skillFrontend: val})}
                        icon={Layout}
                        color="bg-blue-500"
                      />
                      <SkillSlider 
                        label="Backend" 
                        value={formData.skillBackend} 
                        onChange={(val: any) => setFormData({...formData, skillBackend: val})}
                        icon={Cpu}
                        color="bg-purple-500"
                      />
                      <SkillSlider 
                        label="Database" 
                        value={formData.skillDb} 
                        onChange={(val: any) => setFormData({...formData, skillDb: val})}
                        icon={Database}
                        color="bg-emerald-500"
                      />
                      <div className="space-y-4 p-4 rounded-2xl bg-slate-900/50 border border-slate-800/50">
                        <div className="flex items-center justify-between">
                          <div className="flex items-center space-x-3">
                            <div className="w-8 h-8 rounded-lg bg-yellow-500/10 flex items-center justify-center text-yellow-500">
                              <Award className="w-5 h-5" />
                            </div>
                            <span className="font-medium text-slate-300">Exp Level</span>
                          </div>
                          <span className="text-xl font-bold text-white">{formData.experienceLevel}</span>
                        </div>
                        <input 
                          type="range" 
                          min="1" 
                          max="5" 
                          value={formData.experienceLevel}
                          onChange={(e) => setFormData({...formData, experienceLevel: parseInt(e.target.value)})}
                          className="w-full h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer accent-primary"
                        />
                        <div className="flex justify-between text-[10px] text-slate-500 font-bold uppercase tracking-widest">
                          <span>Junior</span>
                          <span>Senior</span>
                        </div>
                      </div>
                    </div>
                  </div>

                  <button
                    type="submit"
                    disabled={loading}
                    className="w-full py-4 bg-primary hover:bg-primary-dark text-white font-bold rounded-xl shadow-lg shadow-primary/20 transition-all flex items-center justify-center space-x-2 group disabled:opacity-70"
                  >
                    {loading ? (
                      <>
                        <Loader2 className="w-5 h-5 animate-spin" />
                        <span>Creating Your Profile...</span>
                      </>
                    ) : (
                      <>
                        <Sparkles className="w-5 h-5" />
                        <span>Finish & Launch Dashboard</span>
                      </>
                    )}
                  </button>
                </motion.form>
              )}
            </AnimatePresence>
          </div>
        </div>
      </motion.div>
    </div>
  );
}
