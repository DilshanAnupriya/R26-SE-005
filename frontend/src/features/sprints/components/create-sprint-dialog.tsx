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
import { useCreateSprint } from "../hooks";

/** Today plus n days, as the yyyy-MM-dd the date input and the API both want. */
function isoInDays(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() + days);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

export function CreateSprintDialog({
  projectId,
  defaultSprintDays = 14,
  suggestedName,
  open,
  onOpenChange,
}: {
  projectId: string;
  defaultSprintDays?: number;
  suggestedName?: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const create = useCreateSprint(projectId);

  const [name, setName] = useState(suggestedName ?? "");
  const [goal, setGoal] = useState("");
  const [startDate, setStartDate] = useState(isoInDays(0));
  // The project's own cadence, not a hardcoded fortnight — a team on one-week
  // sprints should not have to correct this every time.
  const [endDate, setEndDate] = useState(isoInDays(defaultSprintDays));
  const [budgetAmount, setBudgetAmount] = useState("");

  const parsedBudget = budgetAmount.trim() === "" ? undefined : Number(budgetAmount);
  const budgetInvalid = parsedBudget !== undefined
    && (!Number.isFinite(parsedBudget) || parsedBudget < 0);
  const valid = name.trim() !== ""
    && startDate !== ""
    && endDate !== ""
    && endDate >= startDate
    && !budgetInvalid;
  const backwards = startDate !== "" && endDate !== "" && endDate < startDate;

  function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!valid) return;

    create.mutate(
      {
        name: name.trim(),
        goal: goal.trim() || undefined,
        startDate,
        endDate,
        budgetAmount: parsedBudget,
      },
      {
        onSuccess: () => {
          setName("");
          setGoal("");
          setBudgetAmount("");
          onOpenChange(false);
        },
      },
    );
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>New sprint</DialogTitle>
          <DialogDescription>
            Plan the dates now; commit the work when you start it. Nothing is frozen until
            the sprint begins.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={submit} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="sprint-name">Name</Label>
            <Input
              id="sprint-name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="Sprint 1"
              autoFocus
            />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="sprint-goal">Goal</Label>
            <Textarea
              id="sprint-goal"
              value={goal}
              onChange={(event) => setGoal(event.target.value)}
              rows={2}
              placeholder="Optional. The one outcome this sprint is for."
            />
          </div>

          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label htmlFor="sprint-start">Starts</Label>
              <Input
                id="sprint-start"
                type="date"
                value={startDate}
                onChange={(event) => setStartDate(event.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="sprint-end">Ends</Label>
              <Input
                id="sprint-end"
                type="date"
                value={endDate}
                onChange={(event) => setEndDate(event.target.value)}
                aria-invalid={backwards}
              />
            </div>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="sprint-budget">Sprint budget (optional)</Label>
            <Input
              id="sprint-budget"
              type="number"
              min="0"
              step="0.01"
              inputMode="decimal"
              value={budgetAmount}
              onChange={(event) => setBudgetAmount(event.target.value)}
              placeholder="e.g. 7200"
              aria-invalid={budgetInvalid}
            />
            <p className="text-2xs text-ink-3">
              Leave blank to use the project budget in cost forecasts.
            </p>
            {budgetInvalid ? (
              <p className="text-2xs text-red">Budget must be zero or greater.</p>
            ) : null}
          </div>

          {backwards ? (
            <p className="text-2xs text-red">A sprint cannot end before it starts.</p>
          ) : (
            <p className="text-2xs text-ink-3">
              Burndown reads a daily snapshot between these dates, so scope added later
              stays visible rather than being absorbed.
            </p>
          )}

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
                "Create sprint"
              )}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
