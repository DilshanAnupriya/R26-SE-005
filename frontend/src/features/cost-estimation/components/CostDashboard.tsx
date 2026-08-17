"use client";

import { useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { CalendarDays, DollarSign, History, LineChart, Loader2, Wallet } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState, ErrorState } from "@/components/feedback/states";
import { CostWidget } from "@/features/ai/components/cost-widget";
import { aiApi } from "@/features/ai/api";
import { useForecastCost } from "@/features/ai/hooks";
import { useProjects } from "@/features/projects/hooks";
import { useSprintCapacity, useSprints } from "@/features/sprints/hooks";
import { useSession } from "@/components/session-provider";
import { queryKeys } from "@/lib/api/query-keys";
import type { CostForecast, CostTrendPoint } from "@/lib/api/ai-schemas";
import type { Sprint } from "@/lib/api/delivery-schemas";
import { cn } from "@/lib/utils";

const STATUS_TONE: Record<Sprint["status"], string> = {
  PLANNED: "bg-blue-500/10 text-blue-700 dark:text-blue-400",
  ACTIVE: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
  COMPLETED: "bg-muted text-muted-foreground",
  CANCELLED: "bg-destructive/15 text-destructive",
};

function money(amount?: number | null, currency = "USD") {
  if (amount == null) return "-";
  return new Intl.NumberFormat(undefined, {
    style: "currency",
    currency,
    maximumFractionDigits: 0,
  }).format(amount);
}

function number(value?: number | null, suffix = "") {
  if (value == null) return "-";
  return `${new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 }).format(value)}${suffix}`;
}

function dateLabel(value?: string | null) {
  if (!value) return "Not set";
  return new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric" }).format(
    new Date(value),
  );
}

function dateTimeLabel(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}

function stageLabel(stage: CostTrendPoint["stage"] | CostForecast["stage"]) {
  return stage === "MID_SPRINT" ? "Mid-sprint" : "Initial";
}

function forecastError(error: unknown) {
  return error instanceof Error ? error.message : "Could not generate the cost forecast.";
}

export default function CostDashboard() {
  const { api, status } = useSession();
  const projects = useProjects();
  const [chosenProjectId, setChosenProjectId] = useState<string | null>(null);
  const [chosenSprintId, setChosenSprintId] = useState<string | null>(null);

  const projectList = projects.data?.content ?? [];
  const projectId = projectList.some((project) => project.id === chosenProjectId)
    ? chosenProjectId!
    : (projectList[0]?.id ?? "");
  const selectedProject = projectList.find((project) => project.id === projectId) ?? null;

  const sprints = useSprints(projectId || undefined);
  const sprintList = sprints.data?.content ?? [];
  const sprintId = sprintList.some((sprint) => sprint.id === chosenSprintId)
    ? chosenSprintId!
    : (sprintList[0]?.id ?? "");
  const selectedSprint = sprintList.find((sprint) => sprint.id === sprintId) ?? null;
  const capacity = useSprintCapacity(selectedSprint?.id);
  const forecast = useForecastCost();

  const history = useQuery({
    queryKey: queryKeys.ai.costHistory(selectedSprint?.id ?? ""),
    queryFn: () => aiApi.costHistory(api, selectedSprint!.id),
    enabled: status === "authenticated" && Boolean(selectedSprint),
  });

  const visibleForecast = forecast.data?.sprintId === selectedSprint?.id ? forecast.data : null;
  const currency = visibleForecast?.currency ?? selectedProject?.currency ?? "USD";
  const remainingPoints = selectedSprint
    ? Math.max(0, selectedSprint.committedPoints - selectedSprint.completedPoints)
    : null;
  const totalAbsenceDays = capacity.data?.members.reduce(
    (sum, member) => sum + (member.absenceDays ?? 0),
    0,
  );
  const pendingStage = forecast.isPending ? forecast.variables?.stage : null;

  const runForecast = (stage: "initial" | "mid-sprint") => {
    if (!selectedSprint) return;
    forecast.mutate({ sprintId: selectedSprint.id, stage });
  };

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0 space-y-1">
          <div className="flex items-center gap-3">
            <span className="flex size-10 items-center justify-center rounded-lg bg-primary/10">
              <DollarSign className="size-5 text-primary" aria-hidden />
            </span>
            <div>
              <h1 className="text-3xl font-bold">Cost Estimation</h1>
              <p className="text-sm text-muted-foreground">
                Forecast sprint cost from the selected project and sprint plan.
              </p>
            </div>
          </div>
        </div>
      </header>

      <section className="rounded-lg border bg-card p-4 shadow-card" aria-label="Forecast scope">
        <div className="flex flex-wrap items-end gap-3">
          <div className="min-w-60 flex-1 space-y-1.5">
            <label htmlFor="cost-project" className="text-xs font-medium text-muted-foreground">
              Project
            </label>
            <Select
              value={projectId}
              onValueChange={(next) => {
                setChosenProjectId(next);
                setChosenSprintId(null);
              }}
              disabled={projects.isPending || projectList.length === 0}
            >
              <SelectTrigger id="cost-project" className="h-9 w-full">
                <SelectValue placeholder="Choose a project" />
              </SelectTrigger>
              <SelectContent>
                {projectList.map((project) => (
                  <SelectItem key={project.id} value={project.id}>
                    {project.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="min-w-60 flex-1 space-y-1.5">
            <label htmlFor="cost-sprint" className="text-xs font-medium text-muted-foreground">
              Sprint
            </label>
            <Select
              value={sprintId}
              onValueChange={setChosenSprintId}
              disabled={!projectId || sprints.isPending || sprintList.length === 0}
            >
              <SelectTrigger id="cost-sprint" className="h-9 w-full">
                <SelectValue placeholder="Choose a sprint" />
              </SelectTrigger>
              <SelectContent>
                {sprintList.map((sprint) => (
                  <SelectItem key={sprint.id} value={sprint.id}>
                    {sprint.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="flex flex-wrap gap-2">
            <Button
              type="button"
              variant="secondary"
              disabled={!selectedSprint || forecast.isPending}
              onClick={() => runForecast("initial")}
            >
              {pendingStage === "initial" ? (
                <Loader2 className="size-4 animate-spin" aria-hidden />
              ) : null}
              Initial forecast
            </Button>
            <Button
              type="button"
              disabled={!selectedSprint || forecast.isPending}
              onClick={() => runForecast("mid-sprint")}
            >
              {pendingStage === "mid-sprint" ? (
                <Loader2 className="size-4 animate-spin" aria-hidden />
              ) : null}
              Mid-sprint forecast
            </Button>
          </div>
        </div>
      </section>

      {status === "anonymous" ? (
        <EmptyState
          icon={Wallet}
          title="Sign in to forecast costs"
          description="Cost forecasting uses your project and sprint data, so it needs an active session."
          action={
            <Button asChild>
              <Link href="/login?next=/cost">Sign in</Link>
            </Button>
          }
        />
      ) : projects.isPending ? (
        <Skeleton className="h-64 w-full" />
      ) : projects.error ? (
        <ErrorState error={projects.error} onRetry={() => void projects.refetch()} />
      ) : projectList.length === 0 ? (
        <EmptyState
          icon={Wallet}
          title="No projects yet"
          description="Create a project first, then come back here to forecast sprint cost."
        />
      ) : sprints.error ? (
        <ErrorState error={sprints.error} onRetry={() => void sprints.refetch()} />
      ) : !selectedSprint ? (
        <EmptyState
          icon={CalendarDays}
          title="No sprint selected"
          description="Choose a project with at least one sprint to generate a forecast."
        />
      ) : (
        <>
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <MetricCard
              label="Committed points"
              value={number(selectedSprint.committedPoints)}
              hint={`${number(selectedSprint.completedPoints)} delivered`}
            />
            <MetricCard
              label="Remaining points"
              value={number(remainingPoints)}
              hint={`${number(selectedSprint.completionRate, "%")} complete`}
            />
            <MetricCard
              label="Team capacity"
              value={number(capacity.data?.totalEffectiveHours ?? selectedSprint.capacityHours, "h")}
              hint={`${number(totalAbsenceDays)} absence days`}
            />
            <MetricCard
              label="Sprint budget"
              value={money(selectedSprint.budgetAmount ?? selectedProject?.budgetAmount, currency)}
              hint={selectedSprint.plannedCostAmount != null
                ? `${money(selectedSprint.plannedCostAmount, currency)} planned`
                : "Budget variance appears after forecast"}
            />
          </div>

          <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_24rem]">
            <section className="rounded-lg border bg-card p-4 shadow-card" aria-labelledby="forecast-result">
              <div className="mb-3 flex flex-wrap items-start justify-between gap-2">
                <div>
                  <h2 id="forecast-result" className="text-lg font-semibold">
                    Forecast result
                  </h2>
                  <p className="text-sm text-muted-foreground">
                    {selectedSprint.name} · {dateLabel(selectedSprint.startDate)} to{" "}
                    {dateLabel(selectedSprint.endDate)}
                  </p>
                </div>
                <Badge className={cn("border-0", STATUS_TONE[selectedSprint.status])}>
                  {selectedSprint.status.toLowerCase()}
                </Badge>
              </div>

              {forecast.isError ? (
                <p role="alert" className="rounded-md bg-destructive/10 p-3 text-sm text-destructive">
                  {forecastError(forecast.error)}
                </p>
              ) : visibleForecast ? (
                <CostWidget forecast={visibleForecast} />
              ) : (
                <EmptyState
                  icon={LineChart}
                  title="No forecast generated"
                  description="Run an initial forecast during planning, then run a mid-sprint forecast after effort is logged."
                />
              )}
            </section>

            <section className="rounded-lg border bg-card p-4 shadow-card" aria-labelledby="sprint-inputs">
              <h2 id="sprint-inputs" className="text-lg font-semibold">
                Sprint inputs
              </h2>
              <dl className="mt-3 space-y-3 text-sm">
                <InputRow label="Sprint number" value={selectedSprint.sequenceNo} />
                <InputRow label="Team size" value={selectedSprint.teamSize ?? "-"} />
                <InputRow label="Capacity points" value={number(selectedSprint.capacityPoints)} />
                <InputRow label="Capacity hours" value={number(selectedSprint.capacityHours, "h")} />
                <InputRow label="Story count" value={selectedSprint.storyCount ?? "-"} />
                <InputRow
                  label="Utilisation"
                  value={capacity.data?.utilisationPct != null
                    ? `${capacity.data.utilisationPct}%`
                    : "-"}
                />
              </dl>
            </section>
          </div>

          <section className="rounded-lg border bg-card p-4 shadow-card" aria-labelledby="forecast-history">
            <div className="flex items-center justify-between gap-3">
              <div>
                <h2 id="forecast-history" className="flex items-center gap-2 text-lg font-semibold">
                  <History className="size-4 text-primary" aria-hidden />
                  Prediction history
                </h2>
                <p className="text-sm text-muted-foreground">
                  Stored forecasts for the selected sprint.
                </p>
              </div>
              <Button
                type="button"
                size="sm"
                variant="outline"
                disabled={history.isFetching}
                onClick={() => void history.refetch()}
              >
                Refresh
              </Button>
            </div>

            <HistoryTable
              history={history.data ?? []}
              currency={currency}
              isPending={history.isPending}
              error={history.error}
              onRetry={() => void history.refetch()}
            />
          </section>
        </>
      )}
    </div>
  );
}

function MetricCard({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="rounded-lg border bg-card p-4 shadow-card">
      <p className="text-xs font-medium text-muted-foreground">{label}</p>
      <p className="mt-1 text-2xl font-semibold tabular-nums">{value}</p>
      {hint ? <p className="mt-1 text-xs text-muted-foreground">{hint}</p> : null}
    </div>
  );
}

function InputRow({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4 border-b pb-2 last:border-b-0">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="font-medium tabular-nums">{value}</dd>
    </div>
  );
}

function HistoryTable({
  history,
  currency,
  isPending,
  error,
  onRetry,
}: {
  history: CostTrendPoint[];
  currency: string;
  isPending: boolean;
  error: unknown;
  onRetry: () => void;
}) {
  if (isPending) {
    return <Skeleton className="mt-4 h-32 w-full" />;
  }

  if (error) {
    return <ErrorState error={error} onRetry={onRetry} />;
  }

  if (history.length === 0) {
    return (
      <EmptyState
        icon={History}
        title="No history yet"
        description="Every forecast you generate for this sprint will be stored here."
      />
    );
  }

  return (
    <div className="mt-4 overflow-x-auto">
      <table className="w-full min-w-[42rem] text-left text-sm">
        <thead className="border-b text-xs text-muted-foreground">
          <tr>
            <th className="py-2 pr-3 font-medium">Time</th>
            <th className="px-3 py-2 font-medium">Stage</th>
            <th className="px-3 py-2 font-medium">Forecast</th>
            <th className="px-3 py-2 font-medium">Range</th>
            <th className="py-2 pl-3 font-medium">Budget</th>
          </tr>
        </thead>
        <tbody>
          {history.map((point) => (
            <tr key={`${point.stage}-${point.snapshotAt}`} className="border-b last:border-b-0">
              <td className="py-2 pr-3 text-muted-foreground">{dateTimeLabel(point.snapshotAt)}</td>
              <td className="px-3 py-2">
                <Badge variant="outline">{stageLabel(point.stage)}</Badge>
              </td>
              <td className="px-3 py-2 font-medium tabular-nums">
                {money(point.forecastAmount, currency)}
              </td>
              <td className="px-3 py-2 text-muted-foreground tabular-nums">
                {point.lowerBound != null && point.upperBound != null
                  ? `${money(point.lowerBound, currency)} to ${money(point.upperBound, currency)}`
                  : "-"}
              </td>
              <td className="py-2 pl-3 text-muted-foreground tabular-nums">
                {money(point.budgetAmount, currency)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
