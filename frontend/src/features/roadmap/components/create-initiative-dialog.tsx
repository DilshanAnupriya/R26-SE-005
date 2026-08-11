"use client";

import { useState } from "react";
import { Loader2 } from "lucide-react";
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
import { useProjects } from "@/features/projects/hooks";
import { useCreateInitiative } from "../hooks";
import type { Confidence } from "../api";

const CROSS_PROJECT = "__cross__";

function quarterBounds(): { start: string; end: string } {
  const now = new Date();
  const quarter = Math.floor(now.getMonth() / 3);
  return {
    start: iso(new Date(now.getFullYear(), quarter * 3, 1)),
    end: iso(new Date(now.getFullYear(), quarter * 3 + 3, 0)),
  };
}

function iso(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

/**
 * Adding something to the roadmap.
 *
 * <p>No progress field, deliberately. Progress is read off the epics linked to the
 * initiative, and offering a box to type it in is how a roadmap becomes a slide that
 * reports whatever the last person to remember it believed.</p>
 */
export function CreateInitiativeDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const bounds = quarterBounds();
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [projectId, setProjectId] = useState<string>(CROSS_PROJECT);
  const [startDate, setStartDate] = useState(bounds.start);
  const [targetDate, setTargetDate] = useState(bounds.end);
  const [confidence, setConfidence] = useState<Confidence>("MEDIUM");

  const projects = useProjects();
  const create = useCreateInitiative();

  const valid = title.trim() !== "";

  function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!valid) return;

    create.mutate(
      {
        title: title.trim(),
        description: description.trim() || undefined,
        projectId: projectId === CROSS_PROJECT ? undefined : projectId,
        startDate: startDate || undefined,
        targetDate: targetDate || undefined,
        confidence,
      },
      {
        onSuccess: () => {
          setTitle("");
          setDescription("");
          onOpenChange(false);
        },
      },
    );
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>New initiative</DialogTitle>
          <DialogDescription>
            Something big enough to name on a roadmap. Link the epics afterwards — progress
            comes from them, not from a number typed here.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={submit} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="initiative-title">Title</Label>
            <Input
              id="initiative-title"
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              placeholder="Enterprise SSO"
              autoFocus
            />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="initiative-description">What it covers</Label>
            <Textarea
              id="initiative-description"
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              rows={2}
              placeholder="Optional. The scope someone reading this next quarter will not have."
            />
          </div>

          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label htmlFor="initiative-project">Lane</Label>
              <Select value={projectId} onValueChange={setProjectId}>
                <SelectTrigger id="initiative-project" className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={CROSS_PROJECT}>Cross-project</SelectItem>
                  {(projects.data?.content ?? []).map((project) => (
                    <SelectItem key={project.id} value={project.id}>
                      {project.projectKey} · {project.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="initiative-confidence">Confidence in the date</Label>
              <Select
                value={confidence}
                onValueChange={(next) => setConfidence(next as Confidence)}
              >
                <SelectTrigger id="initiative-confidence" className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="HIGH">High</SelectItem>
                  <SelectItem value="MEDIUM">Medium</SelectItem>
                  <SelectItem value="LOW">Low</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="initiative-start">Starts</Label>
              <Input
                id="initiative-start"
                type="date"
                value={startDate}
                onChange={(event) => setStartDate(event.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="initiative-target">Lands by</Label>
              <Input
                id="initiative-target"
                type="date"
                value={targetDate}
                onChange={(event) => setTargetDate(event.target.value)}
              />
            </div>
          </div>
          {/* The target date is not decoration: it picks the column and it is what
              "behind pace" is measured against. */}
          <p className="-mt-1 text-2xs text-ink-3">
            The target date chooses the quarter column, and pace is measured between these
            two dates.
          </p>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={!valid || create.isPending}>
              {create.isPending ? (
                <>
                  <Loader2 className="size-4 animate-spin" aria-hidden />
                  Adding…
                </>
              ) : (
                "Add to roadmap"
              )}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
