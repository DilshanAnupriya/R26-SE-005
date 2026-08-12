"use client";

import { useState } from "react";
import { Loader2, Sparkles } from "lucide-react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useCreateProject, useWorkspaces } from "../hooks";

/**
 * The vocabulary the estimation model was trained on.
 *
 * <p>Free text would be worse than a dropdown here. The model one-hot encodes these,
 * so "Finance" or "fin-tech" simply sets nothing and the signal is silently lost —
 * no error, just a slightly worse estimate nobody can account for.</p>
 */
const DOMAINS = ["finance", "ecommerce", "healthcare", "logistics", "education"] as const;
const TECH_STACKS = [
  "springboot", "nodejs", "django", "flask", "fastapi",
  "react", "angular", "vue", "dotnet", "rails",
] as const;
const EXPERIENCE = [
  { value: "junior", label: "Mostly junior" },
  { value: "mid", label: "Mostly mid-level" },
  { value: "senior", label: "Mostly senior" },
] as const;

const UNSET = "__unset__";

export function CreateProjectDialog({
  open,
  onOpenChange,
  onCreated,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreated?: (projectId: string) => void;
}) {
  const workspaces = useWorkspaces();
  const create = useCreateProject();

  const [name, setName] = useState("");
  const [projectKey, setProjectKey] = useState("");
  const [description, setDescription] = useState("");
  const [workspaceId, setWorkspaceId] = useState<string>("");

  const [domain, setDomain] = useState<string>(UNSET);
  const [techStack, setTechStack] = useState<string>(UNSET);
  const [experience, setExperience] = useState<string>(UNSET);
  const [teamSize, setTeamSize] = useState("");
  const [velocity, setVelocity] = useState("");
  const [completionRate, setCompletionRate] = useState("");

  // Falls back to the organization's default workspace, which is the only one most
  // organizations have — asking about it would be a question with one answer.
  const resolvedWorkspaceId =
    workspaceId ||
    workspaces.data?.find((workspace) => workspace.defaultWorkspace)?.id ||
    workspaces.data?.[0]?.id ||
    "";

  const valid = name.trim() !== "" && resolvedWorkspaceId !== "";

  /** Blank means "not supplied", which the model reads as typical. Never zero. */
  const optionalNumber = (raw: string): number | undefined => {
    const trimmed = raw.trim();
    if (trimmed === "") return undefined;
    const parsed = Number(trimmed);
    return Number.isFinite(parsed) ? parsed : undefined;
  };

  const optionalChoice = (value: string) => (value === UNSET ? undefined : value);

  function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!valid) return;

    const percent = optionalNumber(completionRate);

    create.mutate(
      {
        workspaceId: resolvedWorkspaceId,
        name: name.trim(),
        projectKey: projectKey.trim() || undefined,
        description: description.trim() || undefined,
        domain: optionalChoice(domain),
        // A list on the wire, but the model is given the first entry — so the
        // primary stack is the one that is sent.
        techStack: optionalChoice(techStack) ? [techStack] : undefined,
        devExperienceLevel: optionalChoice(experience) as
          | "junior" | "mid" | "senior" | undefined,
        teamSize: optionalNumber(teamSize),
        teamVelocityAvg: optionalNumber(velocity),
        // Typed as a percentage because that is how people say it; stored as the
        // 0..1 proportion the model was trained on.
        recentCompletionRate: percent === undefined ? undefined : percent / 100,
      },
      {
        onSuccess: (project) => {
          reset();
          onOpenChange(false);
          onCreated?.(project.id);
        },
      },
    );
  }

  function reset() {
    setName("");
    setProjectKey("");
    setDescription("");
    setDomain(UNSET);
    setTechStack(UNSET);
    setExperience(UNSET);
    setTeamSize("");
    setVelocity("");
    setCompletionRate("");
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[88vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>New project</DialogTitle>
          <DialogDescription>
            A project holds the backlog, the sprints and the board.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={submit} className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-[1fr_140px]">
            <div className="space-y-1.5">
              <Label htmlFor="project-name">Name</Label>
              <Input
                id="project-name"
                value={name}
                onChange={(event) => setName(event.target.value)}
                placeholder="Payroll SaaS"
                autoFocus
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="project-key">Key</Label>
              <Input
                id="project-key"
                value={projectKey}
                onChange={(event) => setProjectKey(event.target.value.toUpperCase())}
                placeholder="PAY"
                maxLength={10}
                className="font-mono"
              />
            </div>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="project-description">Description</Label>
            <Textarea
              id="project-description"
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              rows={2}
              placeholder="Optional."
            />
          </div>

          {(workspaces.data?.length ?? 0) > 1 ? (
            <div className="space-y-1.5">
              <Label htmlFor="project-workspace">Workspace</Label>
              <Select value={resolvedWorkspaceId} onValueChange={setWorkspaceId}>
                <SelectTrigger id="project-workspace" className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {workspaces.data?.map((workspace) => (
                    <SelectItem key={workspace.id} value={workspace.id}>
                      {workspace.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          ) : null}

          {/* ---- What the estimator needs to know about this team ---- */}
          <div className="space-y-3 rounded-lg border border-line bg-surface-2 p-3">
            <div className="flex items-start gap-2">
              <Sparkles className="mt-0.5 size-4 flex-none text-accent" aria-hidden />
              <div>
                <p className="text-sm font-semibold">Estimation context</p>
                <p className="mt-0.5 text-2xs text-ink-3">
                  All optional. The story-point model uses these to size work for
                  <em> this </em> team. Left blank, it falls back to typical values and
                  says so on every estimate.
                </p>
              </div>
            </div>

            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label htmlFor="project-domain">Domain</Label>
                <Select value={domain} onValueChange={setDomain}>
                  <SelectTrigger id="project-domain" className="w-full">
                    <SelectValue placeholder="Not set" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value={UNSET}>Not set</SelectItem>
                    {DOMAINS.map((value) => (
                      <SelectItem key={value} value={value} className="capitalize">
                        {value}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="project-stack">Primary tech stack</Label>
                <Select value={techStack} onValueChange={setTechStack}>
                  <SelectTrigger id="project-stack" className="w-full">
                    <SelectValue placeholder="Not set" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value={UNSET}>Not set</SelectItem>
                    {TECH_STACKS.map((value) => (
                      <SelectItem key={value} value={value}>
                        {value}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="project-experience">Team experience</Label>
                <Select value={experience} onValueChange={setExperience}>
                  <SelectTrigger id="project-experience" className="w-full">
                    <SelectValue placeholder="Not set" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value={UNSET}>Not set</SelectItem>
                    {EXPERIENCE.map((option) => (
                      <SelectItem key={option.value} value={option.value}>
                        {option.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="project-team-size">Team size</Label>
                <Input
                  id="project-team-size"
                  inputMode="numeric"
                  value={teamSize}
                  onChange={(event) => setTeamSize(event.target.value)}
                  placeholder="6"
                />
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="project-velocity">Average velocity</Label>
                <Input
                  id="project-velocity"
                  inputMode="decimal"
                  value={velocity}
                  onChange={(event) => setVelocity(event.target.value)}
                  placeholder="22"
                />
                <p className="text-2xs text-ink-3">Story points completed per sprint.</p>
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="project-completion">Completion rate</Label>
                <div className="relative">
                  <Input
                    id="project-completion"
                    inputMode="decimal"
                    value={completionRate}
                    onChange={(event) => setCompletionRate(event.target.value)}
                    placeholder="84"
                    className="pr-7"
                  />
                  <span className="absolute top-1/2 right-2.5 -translate-y-1/2 text-sm text-ink-3">
                    %
                  </span>
                </div>
                <p className="text-2xs text-ink-3">Of what you commit to each sprint.</p>
              </div>
            </div>
          </div>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={!valid || create.isPending}>
              {create.isPending ? (
                <>
                  <Loader2 className="size-4 animate-spin" aria-hidden />
                  Creating…
                </>
              ) : (
                "Create project"
              )}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
