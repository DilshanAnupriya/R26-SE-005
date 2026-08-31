"use client";

import { useState } from "react";
import { Loader2 } from "lucide-react";
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
import { useUpdateSprintBudget } from "@/features/sprints/hooks";
import type { Sprint } from "@/lib/api/delivery-schemas";

export function EditSprintBudgetDialog({
  projectId,
  sprint,
  open,
  onOpenChange,
}: {
  projectId: string;
  sprint: Sprint;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {open ? (
        <EditSprintBudgetForm
          projectId={projectId}
          sprint={sprint}
          onOpenChange={onOpenChange}
        />
      ) : null}
    </Dialog>
  );
}

function EditSprintBudgetForm({
  projectId,
  sprint,
  onOpenChange,
}: {
  projectId: string;
  sprint: Sprint;
  onOpenChange: (open: boolean) => void;
}) {
  const update = useUpdateSprintBudget(projectId);
  const [budgetAmount, setBudgetAmount] = useState(
    sprint.budgetAmount == null ? "" : String(sprint.budgetAmount),
  );
  const parsedBudget = budgetAmount.trim() === "" ? undefined : Number(budgetAmount);
  const budgetInvalid = parsedBudget !== undefined
    && (!Number.isFinite(parsedBudget) || parsedBudget < 0);

  function submit(event: React.FormEvent) {
    event.preventDefault();
    if (parsedBudget === undefined || budgetInvalid) return;

    update.mutate(
      { sprintId: sprint.id, budgetAmount: parsedBudget },
      { onSuccess: () => onOpenChange(false) },
    );
  }

  return (
    <DialogContent className="sm:max-w-md">
      <DialogHeader>
        <DialogTitle>Edit sprint budget</DialogTitle>
        <DialogDescription>
          Set the optional spending limit used by cost forecasts for {sprint.name}.
        </DialogDescription>
      </DialogHeader>

      <form onSubmit={submit} className="space-y-4">
        <div className="space-y-1.5">
          <Label htmlFor="edit-sprint-budget">Sprint budget</Label>
          <Input
            id="edit-sprint-budget"
            type="number"
            min="0"
            step="0.01"
            inputMode="decimal"
            value={budgetAmount}
            onChange={(event) => setBudgetAmount(event.target.value)}
            placeholder="Optional"
            aria-invalid={budgetInvalid}
            autoFocus
          />
          <p className="text-2xs text-ink-3">
            This overrides the project budget for this sprint only.
          </p>
          {budgetInvalid ? (
            <p className="text-2xs text-red">Budget must be zero or greater.</p>
          ) : null}
        </div>

        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button type="submit" disabled={parsedBudget === undefined || budgetInvalid || update.isPending}>
            {update.isPending ? (
              <>
                <Loader2 className="size-4 animate-spin" aria-hidden />
                Saving
              </>
            ) : (
              "Save budget"
            )}
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  );
}
