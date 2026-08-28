"use client";

import { useState, useEffect } from "react";
import { useSession } from "@/components/session-provider";
import { getTokenStore } from "@/lib/api/token-store";
import { getActiveOrganization } from "@/lib/api/org-context";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
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
  Award
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
  
  const [devId, setDevId] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  const [form, setForm] = useState({
    firstName: "",
    lastName: "",
    email: "",
    specialization: "Full-Stack Engineer",
    bio: "",
    experienceLevel: 4,
    skillFrontend: 5,
    skillBackend: 5,
    skillDb: 5
  });

  useEffect(() => {
    if (!open || !user) return;

    setLoading(true);

    const nameParts = user.fullName.trim().split(/\s+/);
    const initialFirst = nameParts[0] || "";
    const initialLast = nameParts.slice(1).join(" ") || "";

    // Fetch developers from backend to find matching record
    authFetch("http://localhost:8080/api/developers")
      .then((res) => res.json())
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
            setForm({
              firstName: match.profile?.firstName || initialFirst,
              lastName: match.profile?.lastName || initialLast,
              email: match.profile?.email || user.email,
              specialization: match.profile?.specialization || "Full-Stack Engineer",
              bio: match.profile?.bio || "",
              experienceLevel: match.experience_level || 4,
              skillFrontend: match.skill_frontend || 5,
              skillBackend: match.skill_backend || 5,
              skillDb: match.skill_db || 5
            });
          } else {
            // Default to session user data
            setDevId(null);
            setForm((prev) => ({
              ...prev,
              firstName: initialFirst,
              lastName: initialLast,
              email: user.email
            }));
          }
        }
        setLoading(false);
      })
      .catch((err) => {
        console.error("Failed to load developer profile:", err);
        setLoading(false);
      });
  }, [open, user]);

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);

    try {
      if (devId) {
        // Update existing developer
        const res = await authFetch(`http://localhost:8080/api/developers/${devId}`, {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(form)
        });

        if (!res.ok) throw new Error("Failed to update developer profile");
      } else {
        // Create developer record if doesn't exist yet
        const res = await authFetch("http://localhost:8080/api/developers", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            ...form,
            devId: `DEV-${Math.random().toString(36).substring(2, 8).toUpperCase()}`,
            currentWorkload: 0,
            availability: 100.0,
            currentTasks: 0,
            consistency: 0.85,
            learningRate: 0.1
          })
        });

        if (!res.ok) throw new Error("Failed to create developer profile");
      }

      toast.success("Profile & AI developer telemetry updated!");
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

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto bg-slate-950 border-slate-800 p-0 shadow-2xl">
        <DialogHeader className="p-6 pb-4 border-b border-slate-800/80 bg-slate-900/40">
          <div className="flex items-center space-x-4">
            <Avatar className="size-14 border-2 border-primary/30 ring-4 ring-primary/10">
              {user.avatarUrl ? <AvatarImage src={user.avatarUrl} alt="" /> : null}
              <AvatarFallback className="bg-primary/20 text-primary font-bold text-sm">
                {initials}
              </AvatarFallback>
            </Avatar>
            <div>
              <DialogTitle className="text-lg font-bold text-white flex items-center gap-2">
                <span>My Developer Profile</span>
                <span className="text-[10px] font-mono font-bold bg-primary/10 text-primary border border-primary/20 px-2 py-0.5 rounded-full">
                  AI Optimization
                </span>
              </DialogTitle>
              <DialogDescription className="text-xs text-slate-400 mt-0.5">
                Manage your technical domain skills, seniority, and specialization for intelligent sprint task allocation.
              </DialogDescription>
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
            {/* Personal Details */}
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
                  <Label className="text-xs text-slate-300 mb-1.5 block">Primary Specialization</Label>
                  <Input
                    value={form.specialization}
                    onChange={(e) => setForm({ ...form, specialization: e.target.value })}
                    placeholder="e.g. Lead Frontend Architect, Backend Specialist..."
                    required
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
            </div>

            {/* Technical Proficiencies */}
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
                {saving ? "Saving..." : "Save Profile & Skills"}
              </Button>
            </div>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}
