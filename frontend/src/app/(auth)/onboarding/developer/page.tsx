"use client";

import { Suspense, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { motion } from "framer-motion";
import { 
  Cpu, 
  Database, 
  Layout, 
  Award, 
  Briefcase, 
  Clock, 
  Sparkles, 
  Loader2, 
  ArrowRight, 
  CheckCircle2, 
  Wand2,
  Code2
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { useSession } from "@/components/session-provider";
import { organizationApi } from "@/features/organizations/api";

const COMMON_SKILLS = [
  "React", "TypeScript", "JavaScript", "Next.js", "Vue.js", "Node.js",
  "Java", "Spring Boot", "Python", "FastAPI", "Go", "PostgreSQL",
  "MySQL", "MongoDB", "Redis", "Docker", "Kubernetes", "AWS", "GraphQL"
];

function DeveloperOnboardingContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const orgSlug = searchParams.get("org") || "";
  const { user, api } = useSession();

  const [seniority, setSeniority] = useState("MID");
  const [yearsExperience, setYearsExperience] = useState(3);
  const [specialization, setSpecialization] = useState("Fullstack Developer");
  const [weeklyCapacityHours, setWeeklyCapacityHours] = useState(40);
  const [availabilityRate, setAvailabilityRate] = useState(1.0);
  const [bio, setBio] = useState("");

  const [skillFrontend, setSkillFrontend] = useState(6);
  const [skillBackend, setSkillBackend] = useState(6);
  const [skillDb, setSkillDb] = useState(5);
  const [selectedTechs, setSelectedTechs] = useState<string[]>(["React", "TypeScript", "Node.js"]);

  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const toggleTech = (tech: string) => {
    setSelectedTechs((prev) =>
      prev.includes(tech) ? prev.filter((t) => t !== tech) : [...prev, tech]
    );
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!user) return;

    setSubmitting(true);
    setError(null);

    try {
      // 1. Save declared developer profile to SprintIQ PMS Core
      await organizationApi.updateDeveloperProfile(api, user.id, {
        seniority,
        yearsExperience,
        weeklyCapacityHours,
        availabilityRate,
      });

      // 2. Save skills
      const skillEntries = selectedTechs.map((name) => ({
        skillName: name,
        proficiency: 3, // working proficiency
      }));
      if (skillEntries.length > 0) {
        await organizationApi.updateDeveloperSkills(api, user.id, skillEntries);
      }

      // 3. Sync to Resource Optimizer developer pool
      const backendUrl = process.env.NEXT_PUBLIC_BACKEND_URL || "http://localhost:8080";
      const names = user.fullName.split(" ");
      const firstName = names[0] || "Developer";
      const lastName = names.slice(1).join(" ") || "";

      await fetch(`${backendUrl.replace(/\/$/, "")}/api/developers`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          firstName,
          lastName,
          email: user.email,
          specialization,
          bio,
          age: 28,
          phoneNumber: "",
          password: "N/A",
          devId: "DEV-" + user.id.slice(0, 6).toUpperCase(),
          experienceLevel: seniority === "LEAD" ? 5 : seniority === "SENIOR" ? 4 : seniority === "MID" ? 3 : 2,
          skillFrontend,
          skillBackend,
          skillDb,
          currentWorkload: 0,
          availability: availabilityRate,
          currentTasks: 0,
          consistency: 0.85,
          learningRate: 0.1,
        }),
      }).catch((e) => console.warn("Optimizer pool sync skipped:", e));

      // 4. Redirect to organization workspace
      if (orgSlug) {
        router.push(`/app/${orgSlug}/dashboard`);
      } else {
        router.push("/app");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to save profile.");
      setSubmitting(false);
    }
  };

  const SkillSlider = ({ label, value, onChange, icon: Icon, color }: any) => (
    <div className="space-y-3 p-4 rounded-2xl bg-slate-900/50 border border-slate-800/80">
      <div className="flex items-center justify-between">
        <div className="flex items-center space-x-2.5">
          <div className={`w-7 h-7 rounded-lg ${color}/10 flex items-center justify-center ${color.replace("bg-", "text-")}`}>
            <Icon className="w-4 h-4" />
          </div>
          <span className="text-sm font-medium text-slate-300">{label}</span>
        </div>
        <span className="text-lg font-bold text-white">{value}/10</span>
      </div>
      <input
        type="range"
        min="1"
        max="10"
        value={value}
        onChange={(e) => onChange(parseInt(e.target.value))}
        className="w-full h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer accent-primary"
      />
      <div className="flex justify-between text-[10px] text-slate-500 font-bold uppercase tracking-wider">
        <span>Novice (1)</span>
        <span>Expert (10)</span>
      </div>
    </div>
  );

  return (
    <div className="min-h-screen bg-background flex items-center justify-center p-4 lg:p-8">
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        className="w-full max-w-3xl rounded-3xl border border-slate-800 bg-slate-950/80 backdrop-blur-xl p-8 lg:p-10 shadow-2xl space-y-8"
      >
        {/* Header */}
        <div className="space-y-3">
          <div className="inline-flex items-center space-x-2 px-3 py-1 rounded-full bg-primary/10 border border-primary/20 text-primary text-xs font-semibold">
            <Wand2 className="w-3.5 h-3.5" />
            <span>Developer Onboarding</span>
          </div>
          <h1 className="text-3xl font-bold text-white">
            Set Up Your <span className="text-gradient">Developer Profile</span>
          </h1>
          <p className="text-sm text-slate-400">
            SprintIQ uses your technical skills and capacity to match you with optimal sprint tasks and balance team workload.
          </p>
        </div>

        {error && (
          <div className="p-4 rounded-xl bg-destructive/10 border border-destructive/20 text-destructive text-sm">
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-8">
          {/* Section 1: Role & Experience */}
          <div className="space-y-4">
            <h3 className="text-xs font-bold uppercase tracking-wider text-slate-400 flex items-center space-x-2">
              <Briefcase className="w-4 h-4 text-primary" />
              <span>Professional Details</span>
            </h3>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div className="space-y-1.5">
                <Label className="text-xs text-slate-300">Specialization / Focus</Label>
                <select
                  value={specialization}
                  onChange={(e) => setSpecialization(e.target.value)}
                  className="w-full px-3.5 py-2.5 bg-slate-900/80 border border-slate-800 rounded-xl text-white text-sm focus:outline-none focus:border-primary"
                >
                  <option value="Fullstack Developer">Fullstack Developer</option>
                  <option value="Backend Engineer">Backend Engineer</option>
                  <option value="Frontend Developer">Frontend Developer</option>
                  <option value="DevOps Engineer">DevOps Engineer</option>
                  <option value="Mobile Developer">Mobile Developer</option>
                  <option value="Data Engineer">Data Engineer</option>
                </select>
              </div>

              <div className="space-y-1.5">
                <Label className="text-xs text-slate-300">Seniority Level</Label>
                <select
                  value={seniority}
                  onChange={(e) => setSeniority(e.target.value)}
                  className="w-full px-3.5 py-2.5 bg-slate-900/80 border border-slate-800 rounded-xl text-white text-sm focus:outline-none focus:border-primary"
                >
                  <option value="JUNIOR">Junior Developer</option>
                  <option value="MID">Mid-Level Developer</option>
                  <option value="SENIOR">Senior Developer</option>
                  <option value="LEAD">Lead / Principal</option>
                </select>
              </div>

              <div className="space-y-1.5">
                <Label className="text-xs text-slate-300">Years of Experience</Label>
                <Input
                  type="number"
                  min="0"
                  max="40"
                  step="0.5"
                  value={yearsExperience}
                  onChange={(e) => setYearsExperience(parseFloat(e.target.value) || 0)}
                  className="bg-slate-900/80 text-white border-slate-800"
                />
              </div>

              <div className="space-y-1.5">
                <Label className="text-xs text-slate-300">Weekly Capacity (Hours)</Label>
                <div className="relative">
                  <Clock className="w-4 h-4 text-slate-500 absolute left-3.5 top-3" />
                  <Input
                    type="number"
                    min="5"
                    max="60"
                    value={weeklyCapacityHours}
                    onChange={(e) => setWeeklyCapacityHours(parseInt(e.target.value) || 40)}
                    className="pl-10 bg-slate-900/80 text-white border-slate-800"
                  />
                </div>
              </div>
            </div>

            <div className="space-y-1.5">
              <Label className="text-xs text-slate-300">Bio / About You</Label>
              <Textarea
                placeholder="What technologies or architecture topics are you most passionate about?"
                value={bio}
                onChange={(e) => setBio(e.target.value)}
                className="bg-slate-900/80 text-white border-slate-800 min-h-[70px]"
              />
            </div>
          </div>

          {/* Section 2: Technical Proficiency Sliders */}
          <div className="space-y-4">
            <h3 className="text-xs font-bold uppercase tracking-wider text-slate-400 flex items-center space-x-2">
              <Award className="w-4 h-4 text-primary" />
              <span>Skill Proficiency Assessment (1 - 10)</span>
            </h3>

            <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
              <SkillSlider
                label="Frontend"
                value={skillFrontend}
                onChange={setSkillFrontend}
                icon={Layout}
                color="bg-blue-500"
              />
              <SkillSlider
                label="Backend"
                value={skillBackend}
                onChange={setSkillBackend}
                icon={Cpu}
                color="bg-purple-500"
              />
              <SkillSlider
                label="Database"
                value={skillDb}
                onChange={setSkillDb}
                icon={Database}
                color="bg-emerald-500"
              />
            </div>
          </div>

          {/* Section 3: Technologies & Stacks */}
          <div className="space-y-3">
            <h3 className="text-xs font-bold uppercase tracking-wider text-slate-400 flex items-center space-x-2">
              <Code2 className="w-4 h-4 text-primary" />
              <span>Technologies & Tools</span>
            </h3>
            <div className="flex flex-wrap gap-2">
              {COMMON_SKILLS.map((tech) => {
                const active = selectedTechs.includes(tech);
                return (
                  <button
                    key={tech}
                    type="button"
                    onClick={() => toggleTech(tech)}
                    className={`px-3 py-1.5 rounded-xl text-xs font-medium border transition-all ${
                      active
                        ? "bg-primary/20 border-primary text-white shadow-sm shadow-primary/20"
                        : "bg-slate-900/50 border-slate-800 text-slate-400 hover:text-white"
                    }`}
                  >
                    {tech}
                  </button>
                );
              })}
            </div>
          </div>

          {/* Submit */}
          <Button
            type="submit"
            disabled={submitting}
            className="w-full py-6 font-bold text-base shadow-xl shadow-primary/20 flex items-center justify-center space-x-2"
          >
            {submitting ? (
              <>
                <Loader2 className="w-5 h-5 animate-spin" />
                <span>Configuring Profile & AI Optimizer...</span>
              </>
            ) : (
              <>
                <span>Complete Onboarding & Enter Workspace</span>
                <ArrowRight className="w-5 h-5" />
              </>
            )}
          </Button>
        </form>
      </motion.div>
    </div>
  );
}

export default function DeveloperOnboardingPage() {
  return (
    <Suspense fallback={<Loader2 className="w-8 h-8 text-primary animate-spin mx-auto mt-20" />}>
      <DeveloperOnboardingContent />
    </Suspense>
  );
}
