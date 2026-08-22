"use client";

import { useEffect, useState } from "react";
import { Loader2 } from "lucide-react";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
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
import { Textarea } from "@/components/ui/textarea";
import { useCreateTask } from "../hooks";
import type { BoardMember } from "./kanban-board";

const PRIORITIES = ["LOWEST", "LOW", "MEDIUM", "HIGH", "CRITICAL"] as const;
const UNASSIGNED = "__unassigned__";

/**
 * Estimates offered as a menu rather than a free-text field.
 *
 * <p>The estimate drives the card's countdown, so a typo of "40" for "4" produces a
 * card that looks fine and silently never goes amber. A short list of plausible sizes
 * removes that failure and is faster to use for the sizes people actually pick.</p>
 */
const ESTIMATES: { label: string; minutes: number | null }[] = [
  { label: "No estimate", minutes: null },
  { label: "30 minutes", minutes: 30 },
  { label: "1 hour", minutes: 60 },
  { label: "2 hours", minutes: 120 },
  { label: "4 hours (half a day)", minutes: 240 },
  { label: "1 day", minutes: 480 },
  { label: "2 days", minutes: 960 },
  { label: "3 days", minutes: 1440 },
  { label: "1 week", minutes: 2400 },
];
const NO_ESTIMATE = "none";

function initials(name?: string | null) {
  if (!name) return "?";
  return name.split(/\s+/).slice(0, 2).map((part) => part[0]?.toUpperCase() ?? "").join("");
}

/**
 * Adding a card without leaving the board.
 *
 * <p>Deliberately smaller than the backlog's create dialog. A task typed at the board
 * is usually noticed rather than planned — "we need to check the migration first" — and
 * asking for an epic, a type and acceptance criteria at that moment is how the thought
 * ends up in a notebook instead. Title alone is enough; everything else has a default
 * and can be filled in later.</p>
 */
export function AddTaskDialog({
  projectId,
  open,
  onOpenChange,
  defaultSprintId,
  members = [],
  onCreated,
}: {
  projectId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  defaultSprintId?: string;
  members?: BoardMember[];
  onCreated?: () => void;
}) {
  const create = useCreateTask(projectId);

  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [priority, setPriority] = useState<string>("MEDIUM");
  const [assigneeId, setAssigneeId] = useState<string>(UNASSIGNED);
  const [estimate, setEstimate] = useState<string>(NO_ESTIMATE);

  // Reopening the dialog after a create should offer an empty form, not the last one.
  useEffect(() => {
    if (!open) {
      setTitle("");
      setDescription("");
      setPriority("MEDIUM");
      setAssigneeId(UNASSIGNED);
      setEstimate(NO_ESTIMATE);
    }
  }, [open]);

  const valid = title.trim().length > 0;

  function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!valid || create.isPending) return;

    create.mutate(
      {
        title: title.trim(),
        description: description.trim() || undefined,
        priority,
        sprintId: defaultSprintId || undefined,
        assigneeId: assigneeId === UNASSIGNED ? undefined : assigneeId,
        estimateMinutes: estimate === NO_ESTIMATE ? undefined : Number(estimate),
      },
      {
        onSuccess: () => {
          onOpenChange(false);
          onCreated?.();
        },
      },
    );
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Add a task</DialogTitle>
          <DialogDescription>
            Lands in the first column with its clock running.
            {defaultSprintId ? " Added to the sprint you are filtered to." : ""}
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={submit} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="task-title">Title</Label>
            <Input
              id="task-title"
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              placeholder="e.g. Back-fill the elapsed_seconds column"
              autoFocus
            />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="task-description">Description</Label>
            <Textarea
              id="task-description"
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              placeholder="Optional"
              rows={3}
            />
          </div>

          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label htmlFor="task-estimate">Estimate</Label>
              <Select value={estimate} onValueChange={setEstimate}>
                <SelectTrigger id="task-estimate" className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {ESTIMATES.map((option) => (
                    <SelectItem
                      key={option.label}
                      value={option.minutes == null ? NO_ESTIMATE : String(option.minutes)}
                    >
                      {option.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <p className="text-[11px] text-muted-foreground">
                Sets the countdown on the card. Without one the card tracks elapsed time only.
              </p>
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="task-priority">Priority</Label>
              <Select value={priority} onValueChange={setPriority}>
                <SelectTrigger id="task-priority" className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {PRIORITIES.map((option) => (
                    <SelectItem key={option} value={option}>
                      {option.charAt(0) + option.slice(1).toLowerCase()}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="task-assignee">Assignee</Label>
            <Select value={assigneeId} onValueChange={setAssigneeId}>
              <SelectTrigger id="task-assignee" className="w-full">
                <SelectValue placeholder="Unassigned" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={UNASSIGNED}>
                  <span className="font-normal text-muted-foreground">Unassigned</span>
                </SelectItem>
                {members.map((member) => {
                  const id = member.userId ?? member.id ?? "";
                  const name = member.fullName ?? member.email ?? "Developer";
                  return (
                    <SelectItem key={id} value={id}>
                      <div className="flex items-center gap-2">
                        <Avatar className="size-4">
                          <AvatarImage src={member.avatarUrl ?? undefined} alt={name} />
                          <AvatarFallback className="bg-primary text-[8px] text-white">
                            {initials(name)}
                          </AvatarFallback>
                        </Avatar>
                        <span>{name}</span>
                      </div>
                    </SelectItem>
                  );
                })}
              </SelectContent>
            </Select>
          </div>

          <DialogFooter>
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={!valid || create.isPending}>
              {create.isPending ? <Loader2 className="size-3.5 animate-spin" aria-hidden /> : null}
              Add task
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
