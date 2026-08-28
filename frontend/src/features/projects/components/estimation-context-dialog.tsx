"use client";

import { useState } from "react";
import { Sparkles, TriangleAlert } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useUpdateProject } from "@/features/projects/hooks";
import {
  EXPERIENCE_LEVELS,
  TECH_STACKS,
  TECH_STACK_LABELS,
  TUNED_DOMAINS,
  domainLabel,
  isTunedDomain,
} from "@/features/projects/model-vocabulary";
import type { Project } from "@/lib/api/delivery-schemas";

/**
 * Fills in what the estimation models need to know about a team.
 *
 * <p>This existed only on the create form, where every field is optional and blank by
 * default — so a project created in a hurry could never be corrected, and its estimates
 * were scored against training medians for ever. Four of our nine projects are in
 * exactly that state.</p>
 *
 * <p>Tech stack is a closed list because the model one-hot encodes it: a value outside
 * the ten it was trained on sets no column at all, which looks like a filled-in field
 * and behaves like an empty one. Domain is deliberately open — see below.</p>
 */

const UNSET = "__unset__";
const CUSTOM = "__custom__";

function optionalChoice(value: string): string | null {
  return value === UNSET || value === CUSTOM || value.trim() === "" ? null : value.trim();
}

function optionalNumber(value: string): number | null {
  const trimmed = value.trim();
  if (trimmed === "") return null;
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : null;
}

export function EstimationContextDialog({
  project,
  open,
  onOpenChange,
}: {
  project: Project;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {/* The form is mounted only while the dialog is open, so its state seeds from
          the project on every open. Cancelling and reopening therefore shows what is
          stored rather than the last abandoned edit — without an effect that writes
          state back on every render. */}
      {open && <EstimationContextForm project={project} onOpenChange={onOpenChange} />}
    </Dialog>
  );
}

function EstimationContextForm({
  project,
  onOpenChange,
}: {
  project: Project;
  onOpenChange: (open: boolean) => void;
}) {
  const update = useUpdateProject(project.id);

  const storedDomain = project.domain ?? "";
  const storedIsTuned = isTunedDomain(storedDomain);

  const [domainChoice, setDomainChoice] = useState<string>(
    storedDomain === "" ? UNSET : storedIsTuned ? storedDomain.toLowerCase() : CUSTOM,
  );
  const [customDomain, setCustomDomain] = useState(
    storedIsTuned || storedDomain === "" ? "" : storedDomain,
  );
  const [techStack, setTechStack] = useState<string>(project.techStack?.[0] ?? UNSET);
  const [teamSize, setTeamSize] = useState(
    project.teamSize == null ? "" : String(project.teamSize),
  );
  const [experience, setExperience] = useState<string>(project.devExperienceLevel ?? UNSET);

  const resolvedDomain =
    domainChoice === CUSTOM ? optionalChoice(customDomain) : optionalChoice(domainChoice);
  const stack = optionalChoice(techStack);

  const submit = () => {
    update.mutate(
      {
        domain: resolvedDomain,
        techStack: stack ? [stack] : [],
        teamSize: optionalNumber(teamSize),
        devExperienceLevel: optionalChoice(experience),
      },
      { onSuccess: () => onOpenChange(false) },
    );
  };

  return (
    <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-lg">
      <DialogHeader>
        <DialogTitle className="flex items-center gap-2">
          <Sparkles className="size-4 text-accent" aria-hidden />
          Estimation context
        </DialogTitle>
        <DialogDescription>
          What the story-point and velocity models know about this team. Left blank, a
          model falls back to the median of its training data and lowers its own
          confidence to say so.
        </DialogDescription>
      </DialogHeader>

      <div className="space-y-4">
        <div className="space-y-1.5">
          <Label htmlFor="estimation-domain">Domain</Label>
          <Select value={domainChoice} onValueChange={setDomainChoice}>
            <SelectTrigger id="estimation-domain" className="w-full">
              <SelectValue placeholder="Not set" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={UNSET}>Not set</SelectItem>
              {TUNED_DOMAINS.map((value) => (
                <SelectItem key={value} value={value}>
                  {domainLabel(value)}
                </SelectItem>
              ))}
              <SelectItem value={CUSTOM}>Something else…</SelectItem>
            </SelectContent>
          </Select>

          {domainChoice === CUSTOM && (
            <Input
              aria-label="Custom domain"
              placeholder="e.g. fintech, gaming, govtech"
              value={customDomain}
              onChange={(event) => setCustomDomain(event.target.value)}
            />
          )}

          {/* Open on purpose. The velocity model is a transfer-learning bundle whose
              base regressor excluded the domain column, so an unlisted domain routes
              to the base model and gets a zero-shot answer — worse than fine-tuned,
              far better than a healthcare team filed under "finance". */}
          <p className="text-2xs text-ink-3">
            {domainChoice === CUSTOM
              ? "New domains are fine — the velocity model falls back to its base model for anything it has not been fine-tuned on."
              : "The five listed domains have a fine-tuned velocity model. Anything else still works, with a slightly wider margin."}
          </p>
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="estimation-stack">Primary tech stack</Label>
          <Select value={techStack} onValueChange={setTechStack}>
            <SelectTrigger id="estimation-stack" className="w-full">
              <SelectValue placeholder="Not set" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={UNSET}>Not set</SelectItem>
              {TECH_STACKS.map((value) => (
                <SelectItem key={value} value={value}>
                  {TECH_STACK_LABELS[value]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          {/* Closed list, unlike domain: the story-point model one-hot encodes this,
              and an unseen value sets no column — indistinguishable, to the model,
              from leaving it blank. */}
          <p className="text-2xs text-ink-3">
            Only these ten are recognised by the model. Anything else is read as blank.
          </p>
        </div>

        <div className="grid gap-3 sm:grid-cols-2">
          <div className="space-y-1.5">
            <Label htmlFor="estimation-team-size">Team size</Label>
            <Input
              id="estimation-team-size"
              type="number"
              min={1}
              max={50}
              placeholder="Not set"
              value={teamSize}
              onChange={(event) => setTeamSize(event.target.value)}
            />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="estimation-experience">Team experience</Label>
            <Select value={experience} onValueChange={setExperience}>
              <SelectTrigger id="estimation-experience" className="w-full">
                <SelectValue placeholder="Not set" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={UNSET}>Not set</SelectItem>
                {EXPERIENCE_LEVELS.map((option) => (
                  <SelectItem key={option.value} value={option.value}>
                    {option.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>

        {/* Velocity and completion rate are absent from this form on purpose — they
            are measured from completed sprints now, not typed in. Saying so is the
            point: their absence would otherwise read as an oversight. */}
        <div className="rounded-lg border border-line bg-surface-2 p-3">
          <p className="text-2xs font-semibold text-ink-2">Measured automatically</p>
          <p className="mt-1 text-2xs text-ink-3">
            Average velocity and recent completion rate are calculated from your last
            five completed sprints, so they are not editable here. Until this project
            completes a sprint, the models fall back to typical values.
          </p>
          {project.teamVelocityAvg != null && (
            <p className="mt-1.5 text-2xs tabular-nums text-ink-2">
              Currently {project.teamVelocityAvg} points per sprint
              {project.recentCompletionRate != null &&
                ` · ${Math.round(project.recentCompletionRate * 100)}% completion`}
            </p>
          )}
        </div>

        {update.isError && (
          <p className="flex items-center gap-1.5 text-2xs text-danger">
            <TriangleAlert className="size-3.5" aria-hidden />
            Could not save. Check the values and try again.
          </p>
        )}
      </div>

      <DialogFooter>
        <Button variant="ghost" onClick={() => onOpenChange(false)}>
          Cancel
        </Button>
        <Button onClick={submit} disabled={update.isPending}>
          {update.isPending ? "Saving…" : "Save"}
        </Button>
    </DialogFooter>
    </DialogContent>
  );
}
