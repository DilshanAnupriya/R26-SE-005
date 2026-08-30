"use client";

import { useState, useEffect } from "react";
import { Loader2, Sparkles, User, Users } from "lucide-react";
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
import { AcceptanceCriteriaList, parseCriteria } from "./acceptance-criteria-list";
import { Textarea } from "@/components/ui/textarea";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { useCreateStory, useEpics } from "../hooks";
import { useMembers } from "@/features/organizations/hooks";
import { useSprints } from "@/features/sprints/hooks";

const TYPES = ["STORY", "TASK", "BUG", "SPIKE", "CHORE"] as const;
const PRIORITIES = ["LOWEST", "LOW", "MEDIUM", "HIGH", "CRITICAL"] as const;
const NO_EPIC = "__none__";
const NO_SPRINT = "__none__";
const UNASSIGNED = "__unassigned__";

function initials(name?: string | null) {
  if (!name) return "?";
  return name.split(/\s+/).slice(0, 2).map((part) => part[0]?.toUpperCase() ?? "").join("");
}

/**
 * Writing a backlog item with optional upfront developer assignment and sprint association.
 */
export function CreateStoryDialog({
  projectId,
  open,
  onOpenChange,
  onCreated,
  defaultSprintId,
}: {
  projectId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreated?: (storyId: string) => void;
  defaultSprintId?: string;
}) {
  const create = useCreateStory(projectId);
  const epics = useEpics(open ? projectId : undefined);
  const members = useMembers();
  const sprints = useSprints(open ? projectId : undefined);

  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [criteria, setCriteria] = useState("");
  const [type, setType] = useState<string>("STORY");
  const [priority, setPriority] = useState<string>("MEDIUM");
  const [epicId, setEpicId] = useState<string>(NO_EPIC);
  const [assigneeId, setAssigneeId] = useState<string>(UNASSIGNED);
  const [sprintId, setSprintId] = useState<string>(defaultSprintId || NO_SPRINT);

  useEffect(() => {
    if (defaultSprintId) {
      setSprintId(defaultSprintId);
    }
  }, [defaultSprintId, open]);

  const criteriaCount = parseCriteria(criteria).length;

  const valid = title.trim() !== "";

  function reset() {
    setTitle("");
    setDescription("");
    setCriteria("");
    setType("STORY");
    setPriority("MEDIUM");
    setEpicId(NO_EPIC);
    setAssigneeId(UNASSIGNED);
    setSprintId(defaultSprintId || NO_SPRINT);
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
        assigneeId: assigneeId === UNASSIGNED ? undefined : assigneeId,
        sprintId: sprintId === NO_SPRINT ? undefined : sprintId,
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

  const memberList = members.data?.content ?? (Array.isArray(members.data) ? members.data : []);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>New backlog item</DialogTitle>
          <DialogDescription>
            Create a task, story, or bug and assign a developer upfront or schedule it into a sprint.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={submit} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="story-title">Title</Label>
            <Input
              id="story-title"
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              placeholder="e.g. Design authentication backend API"
              autoFocus
            />
          </div>

          <div className="grid gap-3 sm:grid-cols-2">
            {/* Assignee Selection */}
            <div className="space-y-1.5">
              <Label htmlFor="story-assignee" className="flex items-center gap-1.5">
                <User className="size-3.5 text-muted-foreground" />
                Assignee (Developer)
              </Label>
              <Select value={assigneeId} onValueChange={setAssigneeId}>
                <SelectTrigger id="story-assignee" className="w-full">
                  <SelectValue placeholder="Unassigned" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={UNASSIGNED}>
                    <span className="text-muted-foreground font-normal">Unassigned</span>
                  </SelectItem>
                  {memberList.map((m: any) => {
                    const id = m.userId || m.id;
                    const name = m.fullName || m.email || "Developer";
                    return (
                      <SelectItem key={id} value={id}>
                        <div className="flex items-center gap-2">
                          <Avatar className="size-4">
                            <AvatarImage src={m.avatarUrl ?? undefined} alt={name} />
                            <AvatarFallback className="text-[8px] bg-primary text-white">
                              {initials(name)}
                            </AvatarFallback>
                          </Avatar>
                          <span className="font-medium text-foreground">{name}</span>
                          {m.jobTitle ? (
                            <span className="text-[11px] text-muted-foreground">({m.jobTitle})</span>
                          ) : null}
                        </div>
                      </SelectItem>
                    );
                  })}
                </SelectContent>
              </Select>
            </div>

            {/* Sprint Selection */}
            <div className="space-y-1.5">
              <Label htmlFor="story-sprint" className="flex items-center gap-1.5">
                <Users className="size-3.5 text-muted-foreground" />
                Sprint
              </Label>
              <Select value={sprintId} onValueChange={setSprintId}>
                <SelectTrigger id="story-sprint" className="w-full">
                  <SelectValue placeholder="Backlog (No Sprint)" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={NO_SPRINT}>
                    <span className="text-muted-foreground font-normal">Backlog (No Sprint)</span>
                  </SelectItem>
                  {(sprints.data?.content ?? []).map((sprint) => (
                    <SelectItem key={sprint.id} value={sprint.id}>
                      <span className="font-medium text-foreground">{sprint.name}</span>
                      <span className="ml-1.5 text-xs text-muted-foreground">({sprint.status})</span>
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
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
            <Label>Acceptance criteria</Label>
            {/*
              A list rather than a textarea, because the count is load-bearing: the
              estimator sums these and the requirement-change model reads how many there
              are. A stray blank line in free text quietly changed both.
            */}
            <AcceptanceCriteriaList
              value={criteria}
              onChange={setCriteria}
              idPrefix="story-criteria"
            />
            <p className="flex items-center gap-1.5 text-2xs text-muted-foreground">
              <Sparkles className="size-3 flex-none text-primary" aria-hidden />
              {criteriaCount > 0
                ? `${criteriaCount} criteri${criteriaCount === 1 ? "on" : "a"} — the estimator counts these, so more detail here sharpens its range.`
                : "The estimator uses these as its size signal."}
            </p>
            {/*
              Said here rather than discovered later. There is no story-edit form in the
              product: once this is created, criteria move only through "Log a change",
              which is what keeps the register describing the backlog it is supposed to
              describe.
            */}
            <p className="text-2xs leading-relaxed text-ink-3">
              Once the item is created these are locked. Changing them afterwards goes
              through <span className="font-medium text-ink-2">Log a change</span>, so the
              sprint&rsquo;s requirement-change risk hears about it.
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
