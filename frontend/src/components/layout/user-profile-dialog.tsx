"use client";

import { useState, useEffect } from "react";
import { useSession } from "@/components/session-provider";
import { getTokenStore } from "@/lib/api/token-store";
import { getActiveOrganization } from "@/lib/api/org-context";
import { env } from "@/lib/env";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Badge } from "@/components/ui/badge";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { 
  User, 
  Mail, 
  Sliders, 
  Layout, 
  Server, 
  Database, 
  Save, 
  CheckCircle2, 
  Sparkles,
  Shield,
  ShieldCheck,
  Crown,
  Briefcase,
  Code2,
  Building2,
  Clock,
  ArrowRight,
  UserCheck
} from "lucide-react";
import { toast } from "sonner";

interface UserProfileDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

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

export function UserProfileDialog({ open, onOpenChange }: UserProfileDialogProps) {
  const { user } = useSession();
  const apiBase = env.NEXT_PUBLIC_API_URL.replace(/\/$/, "");
  
  const roleCode = user?.activeRole?.toUpperCase() || "MEMBER";
  const isOwner = roleCode === "OWNER";
  const isAdmin = roleCode === "ADMIN";
  const isManager = roleCode === "MANAGER";
  const isManagementRole = isOwner || isAdmin || isManager;

  const [activeTab, setActiveTab] = useState<string>(isManagementRole ? "account" : "developer");
  const [devId, setDevId] = useState<number | null>(null);
  const [isDeveloperEnabled, setIsDeveloperEnabled] = useState<boolean>(!isManagementRole);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  const [form, setForm] = useState({
    firstName: "",
    lastName: "",
    email: "",
    jobTitle: "",
    specialization: "Full-Stack Engineer",
    bio: "",
    experienceLevel: 4,
    skillFrontend: 5,
    skillBackend: 5,
    skillDb: 5,
  });

  useEffect(() => {
    if (!open || !user) return;

    // Reset default tab based on role
    setActiveTab(isManagementRole ? "account" : "developer");
    setLoading(true);

    const nameParts = user.fullName.trim().split(/\s+/);
    const initialFirst = nameParts[0] || "";
    const initialLast = nameParts.slice(1).join(" ") || "";

    setForm((prev) => ({
      ...prev,
      firstName: initialFirst,
      lastName: initialLast,
      email: user.email,
      jobTitle: isOwner ? "Workspace Owner" : isAdmin ? "Administrator" : isManager ? "Project Manager" : "Software Engineer",
    }));

    // Fetch developers from backend to find matching record
    authFetch(`${apiBase}/api/developers`)
      .then((res) => (res.ok ? res.json() : []))
      .then((data: any[]) => {
        if (Array.isArray(data)) {
          const userEmail = user.email.toLowerCase();
          const match = data.find((d: any) => {
            const dEmail = d.profile?.email?.toLowerCase();
            const dName = `${d.profile?.firstName ?? ''} ${d.profile?.lastName ?? ''}`.trim().toLowerCase();
            return (dEmail && dEmail === userEmail) || dName === user.fullName.toLowerCase();
          });

          if (match) {
            setDevId(match.id);
            setIsDeveloperEnabled(true);
            setForm((prev) => ({
              ...prev,
              firstName: match.profile?.firstName || initialFirst,
              lastName: match.profile?.lastName || initialLast,
              email: match.profile?.email || user.email,
              specialization: match.profile?.specialization || "Full-Stack Engineer",
              bio: match.profile?.bio || "",
              experienceLevel: match.experience_level || 4,
              skillFrontend: match.skill_frontend || 5,
              skillBackend: match.skill_backend || 5,
              skillDb: match.skill_db || 5,
            }));
          } else {
            setDevId(null);
            setIsDeveloperEnabled(!isManagementRole);
          }
        }
        setLoading(false);
      })
      .catch((err) => {
        console.error("Failed to load developer profile:", err);
        setLoading(false);
      });
  }, [open, user, apiBase, isManagementRole, isOwner, isAdmin, isManager]);

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);

    try {
      const fullUpdatedName = `${form.firstName} ${form.lastName}`.trim();

      // 1. Update Core User Profile if possible
      try {
        await authFetch(`${apiBase}/api/v1/users/me`, {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            fullName: fullUpdatedName,
            jobTitle: form.jobTitle,
          }),
        });
      } catch (userErr) {
        console.warn("Non-fatal: Could not update core user profile", userErr);
      }

      // 2. If Developer Profile is enabled or exists, update / create it in AI table
      if (isDeveloperEnabled) {
        if (devId) {
          const res = await authFetch(`${apiBase}/api/developers/${devId}`, {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              ...form,
              firstName: form.firstName,
              lastName: form.lastName,
            }),
          });
          if (!res.ok) throw new Error("Failed to update developer profile");
        } else {
          const res = await authFetch(`${apiBase}/api/developers`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              ...form,
              devId: `DEV-${Math.random().toString(36).substring(2, 8).toUpperCase()}`,
              currentWorkload: 0,
              availability: 100.0,
              currentTasks: 0,
              consistency: 0.85,
              learningRate: 0.1,
            }),
          });
          if (!res.ok) throw new Error("Failed to create developer profile");
        }
        toast.success("Profile and developer skills updated successfully!");
      } else {
        toast.success("Account profile updated successfully!");
      }

      onOpenChange(false);
    } catch (err: any) {
      console.error("Save error:", err);
      toast.error(err.message || "Failed to save profile changes.");
    } finally {
      setSaving(false);
    }
  };

  if (!user) return null;

  const initials = user.fullName
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((word) => word[0]?.toUpperCase() ?? "")
    .join("");

  const getRoleBadge = () => {
    switch (roleCode) {
      case "OWNER":
        return (
          <Badge className="bg-amber-500/15 text-amber-400 border-amber-500/30 gap-1 text-xs font-semibold py-0.5 px-2">
            <Crown className="w-3 h-3 text-amber-400" />
            Workspace Owner
          </Badge>
        );
      case "ADMIN":
        return (
          <Badge className="bg-indigo-500/15 text-indigo-400 border-indigo-500/30 gap-1 text-xs font-semibold py-0.5 px-2">
            <ShieldCheck className="w-3 h-3 text-indigo-400" />
            Administrator
          </Badge>
        );
      case "MANAGER":
        return (
          <Badge className="bg-blue-500/15 text-blue-400 border-blue-500/30 gap-1 text-xs font-semibold py-0.5 px-2">
            <Briefcase className="w-3 h-3 text-blue-400" />
            Project Manager
          </Badge>
        );
      default:
        return (
          <Badge className="bg-emerald-500/15 text-emerald-400 border-emerald-500/30 gap-1 text-xs font-semibold py-0.5 px-2">
            <Code2 className="w-3 h-3 text-emerald-400" />
            Developer / Contributor
          </Badge>
        );
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto bg-slate-950 border-slate-800 p-0 shadow-2xl">
        {/* Header with Avatar & Role */}
        <DialogHeader className="p-6 pb-4 border-b border-slate-800/80 bg-slate-900/50">
          <div className="flex items-center justify-between">
            <div className="flex items-center space-x-4">
              <Avatar className="size-14 border-2 border-primary/30 ring-4 ring-primary/10">
                {user.avatarUrl ? <AvatarImage src={user.avatarUrl} alt="" /> : null}
                <AvatarFallback className="bg-primary/20 text-primary font-bold text-sm">
                  {initials}
                </AvatarFallback>
              </Avatar>
              <div>
                <div className="flex items-center gap-2">
                  <DialogTitle className="text-lg font-bold text-white">
                    {user.fullName}
                  </DialogTitle>
                  {getRoleBadge()}
                </div>
                <DialogDescription className="text-xs text-slate-400 mt-1 flex items-center gap-2">
                  <Mail className="w-3 h-3 text-slate-500" />
                  <span>{user.email}</span>
                  {user.activeOrganizationName && (
                    <>
                      <span>•</span>
                      <Building2 className="w-3 h-3 text-slate-500" />
                      <span>{user.activeOrganizationName}</span>
                    </>
                  )}
                </DialogDescription>
              </div>
            </div>
          </div>
        </DialogHeader>

        {loading ? (
          <div className="p-12 text-center text-xs text-slate-400">
            <div className="animate-spin rounded-full h-7 w-7 border-b-2 border-primary mx-auto mb-3"></div>
            Loading profile information...
          </div>
        ) : (
          <form onSubmit={handleSave} className="p-6 space-y-6">
            <Tabs value={activeTab} onValueChange={setActiveTab} className="w-full">
              <TabsList className="w-full grid grid-cols-2 bg-slate-900/80 border border-slate-800 mb-6">
                <TabsTrigger value="account" className="text-xs gap-1.5 py-1.5 data-[state=active]:bg-primary/20 data-[state=active]:text-white">
                  <User className="w-3.5 h-3.5" />
                  Account & Organization
                </TabsTrigger>
                <TabsTrigger value="developer" className="text-xs gap-1.5 py-1.5 data-[state=active]:bg-primary/20 data-[state=active]:text-white">
                  <Sparkles className="w-3.5 h-3.5 text-primary" />
                  Developer & AI Skills
                  {isDeveloperEnabled && (
                    <span className="ml-1 text-[10px] bg-emerald-500/20 text-emerald-400 px-1.5 py-0.2 rounded-full font-mono">
                      Active
                    </span>
                  )}
                </TabsTrigger>
              </TabsList>

              {/* TAB 1: Account & Role Info */}
              <TabsContent value="account" className="space-y-5 focus-visible:outline-none">
                {/* Role Overview Card */}
                <div className="p-4 rounded-xl bg-slate-900/70 border border-slate-800 space-y-2">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-semibold text-slate-300 flex items-center gap-1.5">
                      <Shield className="w-3.5 h-3.5 text-primary" />
                      Assigned Workspace Role
                    </span>
                    {getRoleBadge()}
                  </div>
                  <p className="text-xs text-slate-400 leading-relaxed">
                    {isOwner && "As Workspace Owner, you have complete authority over workspace administration, billing, team membership, and project settings."}
                    {isAdmin && "As an Administrator, you can manage team members, workspace settings, sprint schedules, and system configurations."}
                    {isManager && "As a Project Manager, you have permission to create and manage backlogs, sprints, and review allocation analytics."}
                    {!isManagementRole && "As a Developer / Contributor, your capacity and technical specializations are matched to sprint backlog stories by the AI optimizer."}
                  </p>
                </div>

                {/* Personal Information */}
                <div className="space-y-4">
                  <div className="flex items-center gap-2 text-xs font-bold uppercase tracking-wider text-slate-400">
                    <User className="w-3.5 h-3.5 text-primary" />
                    Personal Details
                  </div>

                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <Label className="text-xs text-slate-300 mb-1.5 block">First Name</Label>
                      <Input
                        value={form.firstName}
                        onChange={(e) => setForm({ ...form, firstName: e.target.value })}
                        required
                        className="bg-slate-900 border-slate-800 text-xs text-white"
                      />
                    </div>
                    <div>
                      <Label className="text-xs text-slate-300 mb-1.5 block">Last Name</Label>
                      <Input
                        value={form.lastName}
                        onChange={(e) => setForm({ ...form, lastName: e.target.value })}
                        required
                        className="bg-slate-900 border-slate-800 text-xs text-white"
                      />
                    </div>
                  </div>

                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <Label className="text-xs text-slate-300 mb-1.5 block">Email Address</Label>
                      <div className="relative">
                        <Input
                          value={form.email}
                          disabled
                          className="bg-slate-900/50 border-slate-800/80 text-xs text-slate-400 cursor-not-allowed pr-8"
                        />
                        <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400 absolute right-2.5 top-2.5" />
                      </div>
                    </div>
                    <div>
                      <Label className="text-xs text-slate-300 mb-1.5 block">Job Title / Designation</Label>
                      <Input
                        value={form.jobTitle}
                        onChange={(e) => setForm({ ...form, jobTitle: e.target.value })}
                        placeholder="e.g. Founder & CTO, Principal Architect..."
                        className="bg-slate-900 border-slate-800 text-xs text-white"
                      />
                    </div>
                  </div>
                </div>

                {/* Developer Mode Quick Link for Management Roles */}
                {isManagementRole && (
                  <div className="p-4 rounded-xl bg-primary/5 border border-primary/20 flex items-center justify-between">
                    <div className="space-y-0.5">
                      <div className="text-xs font-semibold text-white flex items-center gap-1.5">
                        <Code2 className="w-3.5 h-3.5 text-primary" />
                        Sprint Development Participation
                      </div>
                      <p className="text-[11px] text-slate-400">
                        {isDeveloperEnabled 
                          ? "Developer telemetry is active. AI optimizer will allocate sprint tasks according to your skills."
                          : "Do you also write code or take sprint tasks? You can configure your developer skills."}
                      </p>
                    </div>
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      onClick={() => setActiveTab("developer")}
                      className="text-xs gap-1 bg-slate-900 border-slate-700 hover:bg-slate-800 text-primary"
                    >
                      {isDeveloperEnabled ? "Edit Skills" : "Configure Skills"}
                      <ArrowRight className="w-3 h-3" />
                    </Button>
                  </div>
                )}
              </TabsContent>

              {/* TAB 2: Developer & AI Telemetry Skills */}
              <TabsContent value="developer" className="space-y-5 focus-visible:outline-none">
                {/* Contributor Mode Banner for Management Roles */}
                {isManagementRole && (
                  <div className="p-4 bg-slate-900/80 border border-slate-800 rounded-xl flex items-center justify-between">
                    <div className="space-y-0.5">
                      <h4 className="text-xs font-bold text-white flex items-center gap-1.5">
                        <UserCheck className="w-3.5 h-3.5 text-primary" />
                        Participate in Sprint Task Allocation
                      </h4>
                      <p className="text-[11px] text-slate-400">
                        Enable this if you actively pick up development stories in the Kanban board.
                      </p>
                    </div>
                    <label className="relative inline-flex items-center cursor-pointer">
                      <input
                        type="checkbox"
                        checked={isDeveloperEnabled}
                        onChange={(e) => setIsDeveloperEnabled(e.target.checked)}
                        className="sr-only peer"
                      />
                      <div className="w-9 h-5 bg-slate-800 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-slate-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-primary"></div>
                    </label>
                  </div>
                )}

                {isManagementRole && !isDeveloperEnabled ? (
                  <div className="p-8 text-center bg-slate-900/40 rounded-xl border border-slate-800/80 space-y-3">
                    <ShieldCheck className="w-10 h-10 text-slate-600 mx-auto" />
                    <div>
                      <h4 className="text-xs font-semibold text-slate-300">Developer Telemetry Inactive</h4>
                      <p className="text-xs text-slate-500 max-w-sm mx-auto mt-1">
                        As a workspace manager/owner, developer task allocation telemetry is off by default. Switch on the toggle above if you wish to be assigned sprint tasks.
                      </p>
                    </div>
                  </div>
                ) : (
                  <div className="space-y-5">
                    {/* Developer Specialization & Capacity */}
                    <div className="grid grid-cols-2 gap-4">
                      <div>
                        <Label className="text-xs text-slate-300 mb-1.5 block">Primary Specialization</Label>
                        <Input
                          value={form.specialization}
                          onChange={(e) => setForm({ ...form, specialization: e.target.value })}
                          placeholder="e.g. Lead Frontend Architect, Backend Specialist..."
                          required={isDeveloperEnabled}
                          className="bg-slate-900 border-slate-800 text-xs text-white"
                        />
                      </div>
                      <div>
                        <Label className="text-xs text-slate-300 mb-1.5 block">Seniority & Capacity Tier</Label>
                        <select
                          value={form.experienceLevel}
                          onChange={(e) => setForm({ ...form, experienceLevel: Number(e.target.value) })}
                          className="w-full h-9 bg-slate-900 border border-slate-800 rounded-md px-3 text-xs text-white focus:outline-none focus:ring-1 focus:ring-primary/50"
                        >
                          <option value={1}>Level 1 - Junior Engineer (10 SPs max)</option>
                          <option value={2}>Level 2 - Mid-Level Engineer (20 SPs max)</option>
                          <option value={3}>Level 3 - Mid/Senior Engineer (30 SPs max)</option>
                          <option value={4}>Level 4 - Senior Engineer (40 SPs max)</option>
                          <option value={5}>Level 5 - Principal / Lead Architect (50 SPs max)</option>
                        </select>
                      </div>
                    </div>

                    <div>
                      <Label className="text-xs text-slate-300 mb-1.5 block">Developer Bio / Focus</Label>
                      <Textarea
                        value={form.bio}
                        onChange={(e) => setForm({ ...form, bio: e.target.value })}
                        placeholder="Share your preferred tech stacks, frameworks, and architecture strengths..."
                        rows={2}
                        className="bg-slate-900 border-slate-800 text-xs text-white resize-none"
                      />
                    </div>

                    {/* Technical Proficiencies Sliders */}
                    <div className="p-5 bg-slate-900/60 rounded-2xl border border-slate-800 space-y-4">
                      <div className="flex items-center justify-between">
                        <div className="flex items-center gap-2">
                          <Sliders className="w-4 h-4 text-primary" />
                          <h4 className="text-xs font-bold uppercase tracking-wider text-white">
                            Self-Assessed Skill Ratings (1–10)
                          </h4>
                        </div>
                        <span className="text-[10px] text-slate-400">Used by AI matching model</span>
                      </div>

                      {/* Frontend Slider */}
                      <div className="space-y-1.5">
                        <div className="flex justify-between text-xs">
                          <span className="font-semibold text-slate-300 flex items-center gap-1.5">
                            <Layout className="w-3.5 h-3.5 text-cyan-400" />
                            Frontend Engineering (React, Next.js, UI/UX, CSS)
                          </span>
                          <span className="font-bold text-cyan-400 font-mono">{form.skillFrontend} / 10</span>
                        </div>
                        <input
                          type="range"
                          min={1}
                          max={10}
                          value={form.skillFrontend}
                          onChange={(e) => setForm({ ...form, skillFrontend: Number(e.target.value) })}
                          className="w-full accent-cyan-500 cursor-pointer"
                        />
                      </div>

                      {/* Backend Slider */}
                      <div className="space-y-1.5">
                        <div className="flex justify-between text-xs">
                          <span className="font-semibold text-slate-300 flex items-center gap-1.5">
                            <Server className="w-3.5 h-3.5 text-emerald-400" />
                            Backend & Distributed APIs (Spring Boot, Java, Python, REST)
                          </span>
                          <span className="font-bold text-emerald-400 font-mono">{form.skillBackend} / 10</span>
                        </div>
                        <input
                          type="range"
                          min={1}
                          max={10}
                          value={form.skillBackend}
                          onChange={(e) => setForm({ ...form, skillBackend: Number(e.target.value) })}
                          className="w-full accent-emerald-500 cursor-pointer"
                        />
                      </div>

                      {/* Database Slider */}
                      <div className="space-y-1.5">
                        <div className="flex justify-between text-xs">
                          <span className="font-semibold text-slate-300 flex items-center gap-1.5">
                            <Database className="w-3.5 h-3.5 text-purple-400" />
                            Database Architecture & SQL (PostgreSQL, Schema, Indexing)
                          </span>
                          <span className="font-bold text-purple-400 font-mono">{form.skillDb} / 10</span>
                        </div>
                        <input
                          type="range"
                          min={1}
                          max={10}
                          value={form.skillDb}
                          onChange={(e) => setForm({ ...form, skillDb: Number(e.target.value) })}
                          className="w-full accent-purple-500 cursor-pointer"
                        />
                      </div>
                    </div>
                  </div>
                )}
              </TabsContent>
            </Tabs>

            {/* Footer Buttons */}
            <div className="flex items-center justify-end gap-3 pt-3 border-t border-slate-800">
              <Button
                type="button"
                variant="outline"
                onClick={() => onOpenChange(false)}
                disabled={saving}
                className="bg-slate-900 border-slate-800 text-slate-300 text-xs"
              >
                Cancel
              </Button>
              <Button
                type="submit"
                disabled={saving}
                className="bg-primary hover:bg-primary/90 text-primary-foreground font-bold text-xs gap-1.5"
              >
                <Save className="w-3.5 h-3.5" />
                {saving ? "Saving..." : "Save Profile"}
              </Button>
            </div>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}
