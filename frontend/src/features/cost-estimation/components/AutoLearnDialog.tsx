"use client";

import { BrainCircuit, Check, Database, ShieldCheck } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

const REQUIRED_SPRINTS = 30;

type AutoLearnDialogProps = {
  completedSprints: number;
  projectName?: string | null;
};

export function AutoLearnDialog({ completedSprints, projectName }: AutoLearnDialogProps) {
  const completedCycles = Math.max(0, completedSprints);
  const countedCycles = Math.min(completedCycles, REQUIRED_SPRINTS);
  const remainingCycles = Math.max(0, REQUIRED_SPRINTS - completedCycles);
  const progress = (countedCycles / REQUIRED_SPRINTS) * 100;
  const readyForReview = completedCycles >= REQUIRED_SPRINTS;

  return (
    <Dialog>
      <Tooltip>
        <TooltipTrigger asChild>
          <DialogTrigger asChild>
            <Button type="button" variant="outline" className="h-9">
              <BrainCircuit className="size-4" aria-hidden />
              AutoLearn
              <span className="ml-0.5 min-w-10 text-right text-xs tabular-nums text-muted-foreground">
                {countedCycles}/{REQUIRED_SPRINTS}
              </span>
            </Button>
          </DialogTrigger>
        </TooltipTrigger>
        <TooltipContent>Learn from completed sprint outcomes</TooltipContent>
      </Tooltip>

      <DialogContent className="sm:max-w-xl">
        <DialogHeader>
          <div className="flex items-start gap-3 pr-8">
            <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10">
              <BrainCircuit className="size-5 text-primary" aria-hidden />
            </span>
            <div className="min-w-0 space-y-1">
              <div className="flex flex-wrap items-center gap-2">
                <DialogTitle>SprintIQ AutoLearn</DialogTitle>
                <Badge variant="secondary">
                  {readyForReview ? "Ready for review" : "Collecting outcomes"}
                </Badge>
              </div>
              <DialogDescription>
                {projectName
                  ? `Builds a new forecasting candidate from completed ${projectName} sprints.`
                  : "Builds a new forecasting candidate from completed project sprints."}
              </DialogDescription>
            </div>
          </div>
        </DialogHeader>

        <div className="space-y-5 py-1">
          <section className="space-y-2" aria-labelledby="autolearn-progress-title">
            <div className="flex items-end justify-between gap-3">
              <div>
                <h3 id="autolearn-progress-title" className="text-sm font-semibold">
                  Sprint-cycle readiness
                </h3>
                <p className="text-xs text-muted-foreground">
                  {readyForReview
                    ? "The minimum cycle count has been reached."
                    : `${remainingCycles} more completed ${remainingCycles === 1 ? "sprint" : "sprints"} required.`}
                </p>
              </div>
              <span className="shrink-0 text-lg font-semibold tabular-nums">
                {countedCycles}/{REQUIRED_SPRINTS}
              </span>
            </div>
            <div
              className="h-2 w-full overflow-hidden rounded-full bg-muted"
              role="progressbar"
              aria-label="AutoLearn sprint-cycle readiness"
              aria-valuemin={0}
              aria-valuemax={REQUIRED_SPRINTS}
              aria-valuenow={countedCycles}
            >
              <div
                className="h-full rounded-full bg-primary transition-[width] duration-300"
                style={{ width: `${progress}%` }}
              />
            </div>
          </section>

          <div className="grid gap-3 sm:grid-cols-3">
            <div className="rounded-lg border p-3">
              <Database className="mb-2 size-4 text-primary" aria-hidden />
              <p className="text-sm font-medium">Collect</p>
              <p className="mt-1 text-xs text-muted-foreground">Initial, mid, and final outcomes</p>
            </div>
            <div className="rounded-lg border p-3">
              <BrainCircuit className="mb-2 size-4 text-primary" aria-hidden />
              <p className="text-sm font-medium">Train</p>
              <p className="mt-1 text-xs text-muted-foreground">Candidate after 30 cycles</p>
            </div>
            <div className="rounded-lg border p-3">
              <ShieldCheck className="mb-2 size-4 text-primary" aria-hidden />
              <p className="text-sm font-medium">Review</p>
              <p className="mt-1 text-xs text-muted-foreground">Baseline and metric gate</p>
            </div>
          </div>

          <div className="flex gap-2 border-t pt-4 text-sm">
            <Check className="mt-0.5 size-4 shrink-0 text-emerald-600" aria-hidden />
            <p className="text-muted-foreground">
              The current forecast model remains active until a better candidate passes MAE, RMSE,
              MAPE, and R2 review.
            </p>
          </div>
        </div>

        <DialogFooter showCloseButton>
          <Button type="button" disabled={!readyForReview}>
            {readyForReview ? "Review and activate" : `Available after ${REQUIRED_SPRINTS} sprints`}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
