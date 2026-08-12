"use client";

import { use, useState } from "react";
import Link from "next/link";
import { ArrowLeft, Plus, CalendarDays, Play, Square } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { CreateSprintDialog } from "@/features/sprints/components/create-sprint-dialog";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";
import { EmptyState, ErrorState } from "@/components/feedback/states";
import { LineChart, chartPalette } from "@/components/charts";
import { CostWidget } from "@/features/ai/components/cost-widget";
import { RiskCard } from "@/features/ai/components/risk-card";
import {
  useAssessSprintRisk,
  useForecastCost,
  usePredictVelocity,
  useSprintRisk,
} from "@/features/ai/hooks";
import { useProject } from "@/features/projects/hooks";
import {
  useBurndown,
  useCompleteSprint,
  useSprintCapacity,
  useSprints,
  useStartSprint,
} from "@/features/sprints/hooks";
import type { Sprint } from "@/lib/api/delivery-schemas";
import { cn } from "@/lib/utils";

const STATUS_TONE = {
  PLANNED: "bg-sky-500/10 text-sky-700 dark:text-sky-400",
  ACTIVE: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
  COMPLETED: "bg-muted text-muted-foreground",
  CANCELLED: "bg-destructive/15 text-destructive",
} as const;

/**
 * Sprint planning, execution and close-out on one screen.
 *
 * <p>Planning and running a sprint are the same object at two moments, and splitting
 * them across routes means a scrum master navigating back and forth to answer "should we
 * commit to this". The selected sprint's panels change with its status instead.</p>
 */
export default function SprintsPage({
  params,
}: {
  params: Promise<{ orgSlug: string; projectId: string }>;
}) {
  const { orgSlug, projectId } = use(params);
  const project = useProject(projectId);
  const sprints = useSprints(projectId);

  const [chosenId, setChosenId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const list = sprints.data?.content ?? [];
  // Derived, not stored: the first sprint is a default, and an effect that writes it into
  // state costs a cascading render for nothing.
  const selectedId = chosenId ?? list[0]?.id ?? null;
  const selected = list.find((sprint) => sprint.id === selectedId) ?? null;

  return (
    <div className="animate-fade-up mx-auto max-w-6xl space-y-6">
      <div>
        <Link
          href={`/app/${orgSlug}/projects/${projectId}`}
          className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
        >
          <ArrowLeft className="size-3" aria-hidden />
          {project.data?.name ?? "Project"}
        </Link>
        <div className="mt-1 flex flex-wrap items-center justify-between gap-3">
          <h1 className="text-2xl font-semibold tracking-tight">Sprints</h1>
          <Button size="sm" onClick={() => setCreating(true)}>
            <Plus className="size-4" aria-hidden />
            New sprint
          </Button>
        </div>
      </div>

      {sprints.isPending ? (
        <Skeleton className="h-64 w-full" />
      ) : sprints.error ? (
        <ErrorState error={sprints.error} onRetry={() => void sprints.refetch()} />
      ) : list.length === 0 ? (
        <EmptyState
          icon={CalendarDays}
          title="No sprints yet"
          description="Create a sprint to start planning against your team's velocity."
          action={
            <Button onClick={() => setCreating(true)}>
              <Plus className="size-4" aria-hidden />
              Create a sprint
            </Button>
          }
        />
      ) : (
        <div className="grid gap-4 lg:grid-cols-[16rem_1fr]">
          <nav aria-label="Sprints">
            <ul className="space-y-1">
              {list.map((sprint) => (
                <li key={sprint.id}>
                  <button
                    type="button"
                    onClick={() => setChosenId(sprint.id)}
                    aria-current={sprint.id === selectedId ? "true" : undefined}
                    className={cn(
                      "w-full rounded-md px-2.5 py-2 text-left text-sm transition-colors",
                      "focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none",
                      sprint.id === selectedId ? "bg-muted font-medium" : "hover:bg-muted/50",
                    )}
                  >
                    <span className="flex items-center justify-between gap-2">
                      <span className="truncate">{sprint.name}</span>
                      <Badge className={cn("border-0 shrink-0", STATUS_TONE[sprint.status])}>
                        {sprint.status.toLowerCase()}
                      </Badge>
                    </span>
                    <span className="mt-0.5 block text-[11px] text-muted-foreground tabular-nums">
                      {sprint.completedPoints}/{sprint.committedPoints} points
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </nav>

          {selected ? (
            <SprintDetail sprint={selected} projectId={projectId} orgSlug={orgSlug} />
          ) : null}
        </div>
      )}

      <CreateSprintDialog
        projectId={projectId}
        defaultSprintDays={project.data?.defaultSprintDays ?? undefined}
        // "Sprint 4" when three exist — the name people were going to type anyway.
        suggestedName={`Sprint ${list.length + 1}`}
        open={creating}
        onOpenChange={setCreating}
      />
    </div>
  );
}

function SprintDetail({
  sprint,
  projectId,
  orgSlug,
}: {
  sprint: Sprint;
  projectId: string;
  orgSlug: string;
}) {
  const burndown = useBurndown(sprint.id);
  const capacity = useSprintCapacity(sprint.id);
  const risk = useSprintRisk(sprint.id);

  const start = useStartSprint(projectId);
  const complete = useCompleteSprint(projectId);
  const assessRisk = useAssessSprintRisk();
  const forecastCost = useForecastCost();
  const predictVelocity = usePredictVelocity();

  const [closing, setClosing] = useState(false);
  const [strategy, setStrategy] = useState<"BACKLOG" | "NEXT_SPRINT">("BACKLOG");
  const [summary, setSummary] = useState("");

  const points = burndown.data?.points ?? [];

  return (
    <div className="space-y-4">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="text-lg font-semibold">{sprint.name}</h2>
          {sprint.goal ? (
            <p className="mt-0.5 text-sm text-muted-foreground">{sprint.goal}</p>
          ) : (
            <p className="mt-0.5 text-sm text-muted-foreground">
              No goal set — a sprint without one has nothing to hold scope against.
            </p>
          )}
        </div>

        <div className="flex flex-wrap gap-2">
          <Button asChild size="sm" variant="secondary">
            <Link href={`/app/${orgSlug}/projects/${projectId}/board?sprint=${sprint.id}`}>
              Open board
            </Link>
          </Button>

          {sprint.status === "PLANNED" ? (
            <Button size="sm" disabled={start.isPending} onClick={() => start.mutate(sprint.id)}>
              <Play className="size-3.5" aria-hidden />
              Start sprint
            </Button>
          ) : null}

          {sprint.status === "ACTIVE" ? (
            <Button size="sm" variant="secondary" onClick={() => setClosing(true)}>
              <Square className="size-3.5" aria-hidden />
              Complete sprint
            </Button>
          ) : null}
        </div>
      </header>

      <div className="grid gap-3 sm:grid-cols-4">
        <Stat label="Committed" value={sprint.committedPoints} />
        <Stat label="Delivered" value={sprint.completedPoints} />
        <Stat
          label="Scope change"
          value={`${sprint.addedPoints > 0 ? "+" : ""}${sprint.addedPoints - sprint.removedPoints}`}
          hint={`${sprint.addedPoints} added, ${sprint.removedPoints} removed`}
        />
        <Stat
          label="Days left"
          value={sprint.daysRemaining ?? "—"}
          hint={sprint.endDate ? `Ends ${sprint.endDate}` : undefined}
        />
      </div>

      <section className="rounded-lg border p-4" aria-labelledby="burndown-heading">
        <h3 id="burndown-heading" className="text-sm font-medium">
          Burndown
        </h3>
        <p className="mt-0.5 text-xs text-muted-foreground">
          Read from daily snapshots, so scope changes stay visible rather than being
          smoothed away.
        </p>
        <div className="mt-3">
          {burndown.isPending ? (
            <Skeleton className="h-48 w-full" />
          ) : (
            <LineChart
              labels={points.map((point) => point.date)}
              yLabel="Points remaining"
              series={[
                {
                  label: "Ideal",
                  dashed: true,
                  color: chartPalette.muted,
                  points: points.map((point) => point.idealRemaining ?? null),
                },
                {
                  label: "Actual",
                  color: chartPalette.primary,
                  points: points.map((point) => point.actualRemaining ?? null),
                },
              ]}
            />
          )}
        </div>
      </section>

      {capacity.data ? (
        <section className="rounded-lg border p-4" aria-labelledby="capacity-heading">
          <h3 id="capacity-heading" className="text-sm font-medium">
            Capacity
          </h3>
          <div className="mt-2 grid gap-3 sm:grid-cols-3">
            <Stat label="Effective hours" value={capacity.data.totalEffectiveHours ?? "—"} />
            <Stat label="Capacity points" value={capacity.data.capacityPoints ?? "—"} />
            <Stat
              label="Utilisation"
              value={capacity.data.utilisationPct != null ? `${capacity.data.utilisationPct}%` : "—"}
            />
          </div>
        </section>
      ) : null}

      {/* The AI panels are opt-in actions rather than automatic: each one is a model
          call, and a page that bills for four on load is one people stop opening. */}
      <section className="space-y-3" aria-labelledby="ai-heading">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h3 id="ai-heading" className="text-sm font-medium">
            AI assessment
          </h3>
          <div className="flex flex-wrap gap-2">
            <Button
              size="sm"
              variant="secondary"
              disabled={predictVelocity.isPending}
              onClick={() => predictVelocity.mutate(sprint.id)}
            >
              {predictVelocity.isPending ? "Predicting…" : "Predict velocity"}
            </Button>
            <Button
              size="sm"
              variant="secondary"
              disabled={assessRisk.isPending}
              onClick={() => assessRisk.mutate(sprint.id)}
            >
              {assessRisk.isPending ? "Assessing…" : "Assess risk"}
            </Button>
            <Button
              size="sm"
              variant="secondary"
              disabled={forecastCost.isPending}
              onClick={() =>
                forecastCost.mutate({
                  sprintId: sprint.id,
                  stage: sprint.status === "ACTIVE" ? "mid-sprint" : "initial",
                })
              }
            >
              {forecastCost.isPending ? "Forecasting…" : "Forecast cost"}
            </Button>
          </div>
        </div>

        {predictVelocity.data ? (
          <div className="rounded-lg border p-3">
            <p className="text-sm">
              Predicted velocity{" "}
              <span className="font-semibold tabular-nums">
                {predictVelocity.data.predictedVelocity}
              </span>{" "}
              points — commitment is{" "}
              <span className="font-medium">
                {predictVelocity.data.commitmentVerdict.replace("_", " ").toLowerCase()}
              </span>
              .
            </p>
            {predictVelocity.data.prediction?.explanation ? (
              <p className="mt-1 text-xs text-muted-foreground">
                {predictVelocity.data.prediction.explanation}
              </p>
            ) : null}
          </div>
        ) : null}

        <div className="grid gap-3 lg:grid-cols-2">
          {(assessRisk.data?.overBudget ?? risk.data?.overBudget) ? (
            <RiskCard assessment={(assessRisk.data?.overBudget ?? risk.data?.overBudget)!} />
          ) : null}
          {(assessRisk.data?.requirementChange ?? risk.data?.requirementChange) ? (
            <RiskCard
              assessment={(assessRisk.data?.requirementChange ?? risk.data?.requirementChange)!}
            />
          ) : null}
          {forecastCost.data ? <CostWidget forecast={forecastCost.data} /> : null}
        </div>
      </section>

      <Dialog open={closing} onOpenChange={setClosing}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Complete {sprint.name}</DialogTitle>
            <DialogDescription>
              Unfinished work has to go somewhere. Leaving it attached to a closed sprint
              would corrupt every velocity figure that comes after it.
            </DialogDescription>
          </DialogHeader>

          <fieldset className="space-y-2">
            <legend className="text-sm font-medium">Where does unfinished work go?</legend>
            {(
              [
                ["BACKLOG", "Back to the backlog", "It will be re-prioritised with everything else."],
                ["NEXT_SPRINT", "Into the next sprint", "Carried straight into the following sprint."],
              ] as const
            ).map(([value, label, hint]) => (
              <label key={value} className="flex items-start gap-2 rounded-md border p-2.5">
                <input
                  type="radio"
                  name="spillover"
                  value={value}
                  checked={strategy === value}
                  onChange={() => setStrategy(value)}
                  className="mt-0.5"
                />
                <span>
                  <span className="block text-sm">{label}</span>
                  <span className="block text-xs text-muted-foreground">{hint}</span>
                </span>
              </label>
            ))}
          </fieldset>

          <div>
            <Label htmlFor="retro-summary" className="text-xs">
              Retrospective note (optional)
            </Label>
            <Textarea
              id="retro-summary"
              rows={3}
              className="mt-1"
              value={summary}
              onChange={(event) => setSummary(event.target.value)}
            />
          </div>

          <DialogFooter>
            <Button variant="ghost" onClick={() => setClosing(false)}>
              Cancel
            </Button>
            <Button
              disabled={complete.isPending}
              onClick={() =>
                complete.mutate(
                  {
                    sprintId: sprint.id,
                    spilloverStrategy: strategy,
                    retrospectiveSummary: summary || null,
                  },
                  { onSuccess: () => setClosing(false) },
                )
              }
            >
              {complete.isPending ? "Closing…" : "Complete sprint"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function Stat({
  label,
  value,
  hint,
}: {
  label: string;
  value: string | number;
  hint?: string;
}) {
  return (
    <div className="rounded-lg border p-3">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="mt-0.5 text-xl font-semibold tabular-nums">{value}</p>
      {hint ? <p className="text-[11px] text-muted-foreground">{hint}</p> : null}
    </div>
  );
}
