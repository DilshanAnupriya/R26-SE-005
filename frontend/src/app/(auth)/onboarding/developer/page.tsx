"use client";
import { backendOrigin } from "@/lib/env";

import { Suspense, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { 
  Cpu, 
  Database, 
  Layout, 
  Briefcase, 
  Clock, 
  Loader2, 
  ArrowRight, 
  Award,
  Code2
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Badge } from "@/components/ui/badge";
import { AuthCard } from "@/features/auth/components/auth-card";
import { useSession } from "@/components/session-provider";
import { organizationApi } from "@/features/organizations/api";

const COMMON_SKILLS = [
  "React", "TypeScript", "JavaScript", "Next.js", "Node.js",
  "Java", "Spring Boot", "Python", "FastAPI", "Go", "PostgreSQL",
  "MySQL", "MongoDB", "Redis", "Docker", "Kubernetes", "AWS"
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
      // 1. Save developer profile in PMS
      await organizationApi.updateDeveloperProfile(api, user.id, {
        seniority,
        yearsExperience,
        weeklyCapacityHours,
        availabilityRate,
      });

      // 2. Save skills
      const skillEntries = selectedTechs.map((name) => ({
        skillName: name,
        proficiency: 3,
      }));
      if (skillEntries.length > 0) {
        await organizationApi.updateDeveloperSkills(api, user.id, skillEntries);
      }

      // 3. Sync to Resource Optimizer developer pool
      const backendUrl = backendOrigin;
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
      }).catch((e) => console.warn("Optimizer sync skipped:", e));

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

  const SkillSlider = ({ label, value, onChange, icon: Icon }: any) => (
    <div className="space-y-2 rounded-md border p-3 bg-muted/20">
      <div className="flex items-center justify-between">
        <div className="flex items-center space-x-2">
          <Icon className="w-3.5 h-3.5 text-muted-foreground" />
          <span className="text-xs font-medium text-foreground">{label}</span>
        </div>
        <Badge variant="outline" className="text-[10px] font-mono">{value}/10</Badge>
      </div>
      <input
        type="range"
        min="1"
        max="10"
        value={value}
        onChange={(e) => onChange(parseInt(e.target.value))}
        className="w-full h-1.5 bg-muted rounded-lg appearance-none cursor-pointer accent-primary"
      />
    </div>
  );

  return (
    <AuthCard
      title="Developer Profile Setup"
      description="Configure your technical background to enable AI sprint workload matching."
      className="max-w-lg"
    >
      {error && (
        <div className="rounded-md border border-destructive/20 bg-destructive/10 p-3 text-xs text-destructive mb-4">
          {error}
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-5">
        {/* Role & Experience */}
        <div className="space-y-3">
          <div className="flex items-center space-x-1.5 text-xs font-medium text-muted-foreground border-b pb-1.5">
            <Briefcase className="w-3.5 h-3.5" />
            <span>Professional Details</span>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <Label className="text-xs">Specialization</Label>
              <select
                value={specialization}
                onChange={(e) => setSpecialization(e.target.value)}
                className="w-full px-2.5 py-1.5 border rounded-md text-xs bg-background focus:outline-none focus:ring-2 focus:ring-primary"
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
              <Label className="text-xs">Seniority</Label>
              <select
                value={seniority}
                onChange={(e) => setSeniority(e.target.value)}
                className="w-full px-2.5 py-1.5 border rounded-md text-xs bg-background focus:outline-none focus:ring-2 focus:ring-primary"
              >
                <option value="JUNIOR">Junior</option>
                <option value="MID">Mid-Level</option>
                <option value="SENIOR">Senior</option>
                <option value="LEAD">Lead / Principal</option>
              </select>
            </div>

            <div className="space-y-1.5">
              <Label className="text-xs">Experience (Years)</Label>
              <Input
                type="number"
                min="0"
                max="40"
                step="0.5"
                value={yearsExperience}
                onChange={(e) => setYearsExperience(parseFloat(e.target.value) || 0)}
                className="h-8 text-xs"
              />
            </div>

            <div className="space-y-1.5">
              <Label className="text-xs">Weekly Capacity (Hrs)</Label>
              <div className="relative">
                <Clock className="w-3.5 h-3.5 text-muted-foreground absolute left-2.5 top-2.5" />
                <Input
                  type="number"
                  min="5"
                  max="60"
                  value={weeklyCapacityHours}
                  onChange={(e) => setWeeklyCapacityHours(parseInt(e.target.value) || 40)}
                  className="pl-8 h-8 text-xs"
                />
              </div>
            </div>
          </div>

          <div className="space-y-1.5">
            <Label className="text-xs">Short Bio</Label>
            <Textarea
              placeholder="Brief description of your background and technical interests..."
              value={bio}
              onChange={(e) => setBio(e.target.value)}
              className="text-xs min-h-[60px]"
            />
          </div>
        </div>

        {/* Skill Proficiency Assessment */}
        <div className="space-y-3">
          <div className="flex items-center space-x-1.5 text-xs font-medium text-muted-foreground border-b pb-1.5">
            <Award className="w-3.5 h-3.5" />
            <span>Skill Proficiency (1 - 10)</span>
          </div>

          <div className="grid grid-cols-3 gap-2">
            <SkillSlider
              label="Frontend"
              value={skillFrontend}
              onChange={setSkillFrontend}
              icon={Layout}
            />
            <SkillSlider
              label="Backend"
              value={skillBackend}
              onChange={setSkillBackend}
              icon={Cpu}
            />
            <SkillSlider
              label="Database"
              value={skillDb}
              onChange={setSkillDb}
              icon={Database}
            />
          </div>
        </div>

        {/* Technologies & Stacks */}
        <div className="space-y-2.5">
          <div className="flex items-center space-x-1.5 text-xs font-medium text-muted-foreground border-b pb-1.5">
            <Code2 className="w-3.5 h-3.5" />
            <span>Technologies & Tools</span>
          </div>
          <div className="flex flex-wrap gap-1.5">
            {COMMON_SKILLS.map((tech) => {
              const active = selectedTechs.includes(tech);
              return (
                <button
                  key={tech}
                  type="button"
                  onClick={() => toggleTech(tech)}
                  className={`px-2.5 py-1 rounded-md text-xs font-medium border transition-colors ${
                    active
                      ? "bg-primary text-primary-foreground border-primary"
                      : "bg-background text-muted-foreground border-border hover:text-foreground"
                  }`}
                >
                  {tech}
                </button>
              );
            })}
          </div>
        </div>

        <Button
          type="submit"
          disabled={submitting}
          className="w-full font-medium"
        >
          {submitting ? (
            <span className="flex items-center space-x-2">
              <Loader2 className="w-4 h-4 animate-spin" />
              <span>Saving Profile...</span>
            </span>
          ) : (
            <span className="flex items-center space-x-1.5">
              <span>Complete Setup & Open Workspace</span>
              <ArrowRight className="w-4 h-4" />
            </span>
          )}
        </Button>
      </form>
    </AuthCard>
  );
}

export default function DeveloperOnboardingPage() {
  return (
    <Suspense fallback={<div className="text-center text-muted-foreground py-12 text-xs">Loading onboarding...</div>}>
      <DeveloperOnboardingContent />
    </Suspense>
  );
}
