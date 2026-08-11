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
import { useCreateStory } from "../hooks";
import { useEpics } from "../hooks";

const TYPES = ["STORY", "TASK", "BUG", "SPIKE", "CHORE"] as const;
const PRIORITIES = ["LOWEST", "LOW", "MEDIUM", "HIGH", "CRITICAL"] as const;
const NO_EPIC = "__none__";

/**
 * Writing a backlog item.
 *
 * <p>Acceptance criteria sit in the main form rather than behind a disclosure because
 * they are the strongest size signal available before anyone writes code — the
 * estimator counts them as its component signal and scores their text. A story
 * captured as a title alone is one the model has almost nothing to go on for, and the
 * hint under the field says so rather than leaving that to be discovered.</p>
 */
export function CreateStoryDialog({
  projectId,
  open,
  onOpenChange,
  onCreated,
}: {
  projectId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreated?: (storyId: string) => void;
}) {
  const create = useCreateStory(projectId);
  const epics = useEpics(open ? projectId : undefined);

  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [criteria, setCriteria] = useState("");
  const [type, setType] = useState<string>("STORY");
  const [priority, setPriority] = useState<string>("MEDIUM");
  const [epicId, setEpicId] = useState<string>(NO_EPIC);

  const criteriaCount = criteria
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean).length;

  const valid = title.trim() !== "";

  function reset() {
    setTitle("");
    setDescription("");
    setCriteria("");
    setType("STORY");
    setPriority("MEDIUM");
    setEpicId(NO_EPIC);
  }

  function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!valid) return;

    create.mutate(
      {
        title: title.trim(),
        description: description.trim() || undefined,
        acceptanceCriteria: criteria.trim() || undefined,
        type,
        priority,
        epicId: epicId === NO_EPIC ? undefined : epicId,
      },
      {
        onSuccess: (story) => {
          reset();
          onOpenChange(false);
          onCreated?.(story.id);
        },
      },
    );
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[88vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>New backlog item</DialogTitle>
          <DialogDescription>
            Describe the change from the point of view of whoever asked for it.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={submit} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="story-title">Title</Label>
            <Input
              id="story-title"
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              placeholder="Add multi-currency invoice generation"
              autoFocus
            />
          </div>

          <div className="grid gap-3 sm:grid-cols-3">
            <div className="space-y-1.5">
              <Label htmlFor="story-type">Type</Label>
              <Select value={type} onValueChange={setType}>
                <SelectTrigger id="story-type" className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {TYPES.map((value) => (
                    <SelectItem key={value} value={value}>
                      {value.charAt(0) + value.slice(1).toLowerCase()}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="story-priority">Priority</Label>
              <Select value={priority} onValueChange={setPriority}>
                <SelectTrigger id="story-priority" className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {PRIORITIES.map((value) => (
                    <SelectItem key={value} value={value}>
                      {value.charAt(0) + value.slice(1).toLowerCase()}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="story-epic">Epic</Label>
              <Select value={epicId} onValueChange={setEpicId}>
                <SelectTrigger id="story-epic" className="w-full">
                  <SelectValue placeholder="None" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={NO_EPIC}>None</SelectItem>
                  {(epics.data?.content ?? []).map((epic) => (
                    <SelectItem key={epic.id} value={epic.id}>
                      {epic.title}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="story-description">Description</Label>
            <Textarea
              id="story-description"
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              rows={3}
              placeholder="What changes, and why it is worth doing."
            />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="story-criteria">Acceptance criteria</Label>
            <Textarea
              id="story-criteria"
              value={criteria}
              onChange={(event) => setCriteria(event.target.value)}
              rows={4}
              placeholder={"Given a customer in the EU\nWhen an invoice is generated\nThen VAT is applied"}
              className="font-mono text-sm"
            />
            <p className="flex items-center gap-1.5 text-2xs text-ink-3">
              <Sparkles className="size-3 flex-none text-accent" aria-hidden />
              {criteriaCount > 0
                ? `${criteriaCount} line${criteriaCount === 1 ? "" : "s"} — the estimator counts these, so more detail here sharpens its range.`
                : "One per line. The estimator uses these as its strongest size signal; without them it has little to go on."}
            </p>
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
                "Create item"
              )}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
