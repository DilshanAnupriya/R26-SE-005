"use client";

import { useState } from "react";
import Link from "next/link";
import { useQueries, useQuery } from "@tanstack/react-query";
import {
  Activity,
  CalendarDays,
  CircleDollarSign,
  Gauge,
  History,
  LineChart,
  ListChecks,
  Loader2,
  Users,
  Wallet,
} from "lucide-react";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
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
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { EmptyState, ErrorState } from "@/components/feedback/states";
import { CostWidget } from "@/features/ai/components/cost-widget";
import { aiApi } from "@/features/ai/api";
import { useForecastCost } from "@/features/ai/hooks";
import { useTaskBoard } from "@/features/board/hooks";
import { useProjects } from "@/features/projects/hooks";
import { useVelocityReport } from "@/features/reports/hooks";
import { useSprintCapacity, useSprints } from "@/features/sprints/hooks";
import { useSession } from "@/components/session-provider";
import { queryKeys } from "@/lib/api/query-keys";
import type { CostForecast, CostPredictionAccuracy, CostTrendPoint, CostValidation } from "@/lib/api/ai-schemas";
import type { Sprint, Task } from "@/lib/api/delivery-schemas";
import { cn } from "@/lib/utils";
import {
  sprintCostApi,
  type SprintCostMember,
  type SprintCostProfile,
  type SprintCostSkill,
  type SprintTimeEntry,
} from "../api";

const STATUS_TONE: Record<Sprint["status"], string> = {
  PLANNED: "bg-blue-500/10 text-blue-700 dark:text-blue-400",
  ACTIVE: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
  COMPLETED: "bg-muted text-muted-foreground",
  CANCELLED: "bg-destructive/15 text-destructive",
};

const TASK_STATUS_TONE: Record<Task["status"], string> = {
  TODO: "bg-muted text-muted-foreground",
  IN_PROGRESS: "bg-blue-500/10 text-blue-700 dark:text-blue-400",
  IN_REVIEW: "bg-amber-500/10 text-amber-700 dark:text-amber-400",
  BLOCKED: "bg-destructive/10 text-destructive",
  DONE: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
  CANCELLED: "bg-muted text-muted-foreground",
};

type TeamRow = {
  member: SprintCostMember;
  profile?: SprintCostProfile;
  skills: SprintCostSkill[];
  capacity?: {
    availableHours?: number | null;
    absenceDays?: number | null;
    effectiveHours?: number | null;
  };
  tasks: Task[];
  loadPct: number | null;
};

function money(amount?: number | null, currency = "USD") {
  if (amount == null || !Number.isFinite(amount)) return "-";
  return new Intl.NumberFormat(undefined, {
    style: "currency",
    currency,
    maximumFractionDigits: 0,
  }).format(amount);
}

function number(value?: number | null, suffix = "") {
  if (value == null || !Number.isFinite(value)) return "-";
  return `${new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 }).format(value)}${suffix}`;
}

function hours(minutes?: number | null) {
  return minutes == null ? "-" : number(minutes / 60, "h");
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

function enumLabel(value?: string | null) {
  if (!value) return "Not set";
  return value
    .toLowerCase()
    .split("_")
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function initials(name?: string | null) {
  if (!name) return "?";
  return name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join("");
}

function forecastError(error: unknown) {
  return error instanceof Error ? error.message : "Could not generate the cost forecast.";
}

function costForMinutes(minutes: number, rate?: number | null, overtime = false) {
  if (rate == null || !Number.isFinite(rate)) return null;
  return (minutes / 60) * rate * (overtime ? 1.5 : 1);
}

function buildLocalValidation(
  sprintId: string,
  sprintStatus: Sprint["status"],
  history: CostTrendPoint[],
  actualCost?: number | null,
  estimatedRemainingCost?: number | null,
): CostValidation | undefined {
  if (actualCost == null || !Number.isFinite(actualCost)) {
    return undefined;
  }

  const accuracyEligible = sprintStatus === "COMPLETED";
  const remainingCost = accuracyEligible ? 0 : (estimatedRemainingCost ?? 0);
  const projectedFinalCost = actualCost + remainingCost;
  const latestForecastAmount = history.at(-1)?.forecastAmount ?? null;
  const liveVariance = latestForecastAmount == null
    ? null
    : projectedFinalCost - latestForecastAmount;
  const predictions: CostPredictionAccuracy[] = accuracyEligible ? history.map((point, index) => {
    const errorAmount = actualCost - point.forecastAmount;
    const absoluteError = Math.abs(errorAmount);

    return {
      forecastId: `${sprintId}-${index}`,
      stage: point.stage,
      predictedCost: point.forecastAmount,
      actualCost,
      errorAmount,
      absoluteError,
      absolutePercentageError: actualCost === 0 ? null : (absoluteError / actualCost) * 100,
      snapshotAt: point.snapshotAt,
    };
  }) : [];
  const mean = (values: number[]) =>
    values.reduce((sum, value) => sum + value, 0) / values.length;
  const latestOf = (stage: CostTrendPoint["stage"]) =>
    predictions
      .filter((row) => row.stage === stage)
      .sort((left, right) => Date.parse(right.snapshotAt) - Date.parse(left.snapshotAt))[0] ?? null;
  const percentageErrors = predictions
    .map((row) => row.absolutePercentageError)
    .filter((value): value is number => value != null && Number.isFinite(value));
  const initial = latestOf("INITIAL");
  const midSprint = latestOf("MID_SPRINT");
  const closerStage = initial && midSprint
    ? (midSprint.absoluteError <= initial.absoluteError ? "MID_SPRINT" : "INITIAL")
    : (midSprint?.stage ?? initial?.stage ?? null);

  return {
    sprintId,
    evaluationStatus: sprintStatus === "COMPLETED"
      ? "FINAL"
      : sprintStatus === "ACTIVE"
        ? "LIVE_TRACKING"
        : sprintStatus === "CANCELLED"
          ? "CANCELLED"
          : "PLANNING",
    accuracyEligible,
    actualCost,
    actualCostSoFar: actualCost,
    estimatedRemainingCost: remainingCost,
    projectedFinalCost,
    latestForecastAmount,
    liveVariance,
    mae: predictions.length > 0 ? mean(predictions.map((row) => row.absoluteError)) : null,
    rmse: predictions.length > 0
      ? Math.sqrt(mean(predictions.map((row) => row.absoluteError ** 2)))
      : null,
    mape: percentageErrors.length > 0 ? mean(percentageErrors) : null,
    initial,
    midSprint,
    closerStage,
    predictions,
  };
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
  const taskBoard = useTaskBoard(projectId || undefined, selectedSprint?.id);
  const velocity = useVelocityReport(projectId || undefined);
  const forecast = useForecastCost();

  const members = useQuery({
    queryKey: ["sprint-cost", "members", projectId],
    queryFn: () => sprintCostApi.members(api, projectId),
    enabled: status === "authenticated" && Boolean(projectId),
  });

  const deliveryMembers = (members.data ?? []).filter((member) => !member.stakeholder);
  const profileQueries = useQueries({
    queries: deliveryMembers.map((member) => ({
      queryKey: ["sprint-cost", "profile", member.userId],
      queryFn: () => sprintCostApi.developerProfile(api, member.userId),
      enabled: status === "authenticated",
      staleTime: 5 * 60_000,
    })),
  });
  const skillQueries = useQueries({
    queries: deliveryMembers.map((member) => ({
      queryKey: ["sprint-cost", "skills", member.userId],
      queryFn: () => sprintCostApi.developerSkills(api, member.userId),
      enabled: status === "authenticated",
      staleTime: 5 * 60_000,
    })),
  });

  const timeEntries = useQuery({
    queryKey: ["sprint-cost", "time-entries", selectedSprint?.id ?? ""],
    queryFn: () => sprintCostApi.timeEntries(api, projectId, selectedSprint!.id),
    enabled: status === "authenticated" && Boolean(projectId && selectedSprint),
  });

  const history = useQuery({
    queryKey: queryKeys.ai.costHistory(selectedSprint?.id ?? ""),
    queryFn: () => aiApi.costHistory(api, selectedSprint!.id),
    enabled: status === "authenticated" && Boolean(selectedSprint),
  });

  const validation = useQuery({
    queryKey: queryKeys.ai.costValidation(selectedSprint?.id ?? ""),
    queryFn: () => aiApi.costValidation(api, selectedSprint!.id),
    enabled: status === "authenticated" && Boolean(selectedSprint),
  });

  const sprintTasks = taskBoard.data?.columns.flatMap((column) => column.tasks) ?? [];
  const capacityByUser = new Map(
    (capacity.data?.members ?? []).map((entry) => [entry.userId, entry]),
  );
  const memberDetails = deliveryMembers.map((member, index) => ({
    member,
    profile: profileQueries[index]?.data,
    skills: skillQueries[index]?.data ?? [],
  }));
  const rates = memberDetails
    .map((entry) => entry.profile?.hourlyRate)
    .filter((rate): rate is number => rate != null && rate > 0);
  const averageHourlyRate = rates.length > 0
    ? rates.reduce((sum, rate) => sum + rate, 0) / rates.length
    : null;
  const rateByUser = new Map(
    memberDetails
      .filter((entry) => entry.profile?.hourlyRate != null)
      .map((entry) => [entry.member.userId, entry.profile!.hourlyRate!]),
  );
  const rateFor = (userId?: string | null) =>
    (userId ? rateByUser.get(userId) : null) ?? averageHourlyRate;

  const plannedEffortMinutes = sprintTasks.reduce(
    (sum, task) => sum + (task.estimateMinutes ?? 0),
    0,
  );
  const loggedEffortMinutes = sprintTasks.reduce(
    (sum, task) => sum + (task.spentMinutes ?? 0),
    0,
  );
  const plannedLaborCost = averageHourlyRate == null
    ? null
    : sprintTasks.reduce(
        (sum, task) =>
          sum + (costForMinutes(task.estimateMinutes ?? 0, rateFor(task.assigneeId)) ?? 0),
        0,
      );

  const sprintTimeEntries = timeEntries.data?.content ?? [];
  const actualCostFromEntries = averageHourlyRate == null
    ? null
    : sprintTimeEntries.reduce(
        (sum, entry) =>
          sum + (costForMinutes(entry.minutes, rateFor(entry.userId), entry.overtime) ?? 0),
        0,
      );
  const actualCostFromTasks = averageHourlyRate == null
    ? null
    : sprintTasks.reduce(
        (sum, task) =>
          sum + (costForMinutes(task.spentMinutes ?? 0, rateFor(task.assigneeId)) ?? 0),
        0,
      );
  const actualLaborCost = timeEntries.isSuccess ? actualCostFromEntries : actualCostFromTasks;
  const estimatedRemainingLaborCost = averageHourlyRate == null
    ? null
    : sprintTasks
        .filter((task) => task.status !== "DONE" && task.status !== "CANCELLED")
        .reduce((sum, task) => {
          const remainingMinutes = task.remainingMinutes
            ?? Math.max(0, (task.estimateMinutes ?? 0) - (task.spentMinutes ?? 0));
          return sum + (costForMinutes(remainingMinutes, rateFor(task.assigneeId)) ?? 0);
        }, 0);
  const overtimeMinutes = sprintTimeEntries
    .filter((entry) => entry.overtime)
    .reduce((sum, entry) => sum + entry.minutes, 0);

  const timeEntriesByTask = new Map<string, SprintTimeEntry[]>();
  for (const entry of sprintTimeEntries) {
    timeEntriesByTask.set(entry.entityId, [
      ...(timeEntriesByTask.get(entry.entityId) ?? []),
      entry,
    ]);
  }

  const requiredSkillNames = new Map<string, string>();
  for (const task of sprintTasks) {
    for (const skill of task.requiredSkills) {
      requiredSkillNames.set(skill.toLowerCase(), skill);
    }
  }
  const coveredSkills = new Set(
    memberDetails.flatMap((entry) =>
      entry.skills
        .filter((skill) => skill.proficiency >= 3)
        .map((skill) => skill.skillName.toLowerCase()),
    ),
  );
  const requiredSkills = [...requiredSkillNames.entries()];
  const coveredSkillCount = requiredSkills.filter(([key]) => coveredSkills.has(key)).length;
  const skillCoverage = requiredSkills.length > 0
    ? (coveredSkillCount / requiredSkills.length) * 100
    : null;

  const availabilityRates = memberDetails
    .map((entry) => entry.profile?.availabilityRate)
    .filter((rate): rate is number => rate != null);
  const availabilityPct = availabilityRates.length > 0
    ? (availabilityRates.reduce((sum, rate) => sum + rate, 0) / availabilityRates.length) * 100
    : null;

  const teamRows: TeamRow[] = memberDetails.map((entry) => {
    const memberTasks = sprintTasks.filter((task) => task.assigneeId === entry.member.userId);
    const openEstimatedMinutes = memberTasks
      .filter((task) => task.status !== "DONE" && task.status !== "CANCELLED")
      .reduce((sum, task) => sum + (task.estimateMinutes ?? 0), 0);
    const memberCapacity = capacityByUser.get(entry.member.userId);
    const effectiveHours = memberCapacity?.effectiveHours ?? 0;
    return {
      ...entry,
      capacity: memberCapacity,
      tasks: memberTasks,
      loadPct: effectiveHours > 0 ? (openEstimatedMinutes / 60 / effectiveHours) * 100 : null,
    };
  });

  const visibleForecast = forecast.data?.sprintId === selectedSprint?.id ? forecast.data : null;
  const currency = visibleForecast?.currency ?? selectedProject?.currency ?? "USD";
  const remainingPoints = selectedSprint
    ? Math.max(0, selectedSprint.committedPoints - selectedSprint.completedPoints)
    : null;
  const completionRate = selectedSprint
    ? selectedSprint.committedPoints > 0
      ? (selectedSprint.completedPoints / selectedSprint.committedPoints) * 100
      : (selectedSprint.completionRate ?? 0)
    : null;
  const totalAbsenceDays = capacity.data?.members.reduce(
    (sum, member) => sum + (member.absenceDays ?? 0),
    0,
  );
  const latestHistory = history.data?.at(-1) ?? null;
  const sprintBudget = selectedSprint?.budgetAmount ?? selectedProject?.budgetAmount ?? null;
  const pendingStage = forecast.isPending ? forecast.variables?.stage : null;
  const maxLoadPct = teamRows.reduce(
    (max, row) => Math.max(max, row.loadPct ?? 0),
    0,
  );
  const localValidation = selectedSprint
    ? buildLocalValidation(
        selectedSprint.id,
        selectedSprint.status,
        history.data ?? [],
        actualLaborCost,
        estimatedRemainingLaborCost,
      )
    : undefined;
  const backendValidation = validation.data?.evaluationStatus ? validation.data : undefined;
  const validationResult = backendValidation ?? localValidation;
  const usingLocalValidation = !backendValidation && Boolean(validation.error) && Boolean(localValidation);
  const validationError = validationResult ? null : validation.error;
  const validationPending = validation.isPending && !validationResult;

  const runForecast = (stage: "initial" | "mid-sprint") => {
    if (!selectedSprint) return;
    forecast.mutate({ sprintId: selectedSprint.id, stage });
  };

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div className="flex min-w-0 items-center gap-3">
          <span className="flex size-10 items-center justify-center rounded-lg bg-primary/10">
            <CircleDollarSign className="size-5 text-primary" aria-hidden />
          </span>
          <div>
            <h1 className="text-3xl font-bold">Sprint Cost</h1>
            <p className="text-sm text-muted-foreground">
              Planning, live effort, team capacity, and forecast history for one sprint.
            </p>
          </div>
        </div>
      </header>

      <section className="rounded-lg border bg-card p-4 shadow-card" aria-label="Sprint cost scope">
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
              ) : (
                <LineChart className="size-4" aria-hidden />
              )}
              Initial forecast
            </Button>
            <Button
              type="button"
              disabled={!selectedSprint || forecast.isPending}
              onClick={() => runForecast("mid-sprint")}
            >
              {pendingStage === "mid-sprint" ? (
                <Loader2 className="size-4 animate-spin" aria-hidden />
              ) : (
                <Activity className="size-4" aria-hidden />
              )}
              Mid-sprint forecast
            </Button>
          </div>
        </div>
      </section>

      {status === "anonymous" ? (
        <EmptyState
          icon={Wallet}
          title="Sign in to view sprint costs"
          description="Sprint cost data belongs to your active organization."
          action={
            <Button asChild>
              <Link href="/login?next=/app">Sign in</Link>
            </Button>
          }
        />
      ) : projects.isPending ? (
        <Skeleton className="h-64 w-full" />
      ) : projects.error ? (
        <ErrorState error={projects.error} onRetry={() => void projects.refetch()} />
      ) : projectList.length === 0 ? (
        <EmptyState icon={Wallet} title="No projects yet" description="Create a project before planning sprint cost." />
      ) : sprints.error ? (
        <ErrorState error={sprints.error} onRetry={() => void sprints.refetch()} />
      ) : !selectedSprint ? (
        <EmptyState icon={CalendarDays} title="No sprint selected" description="Choose a project with at least one sprint." />
      ) : (
        <Tabs defaultValue="overview" className="gap-5">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b pb-3">
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h2 className="truncate text-lg font-semibold">{selectedSprint.name}</h2>
                <Badge className={cn("border-0", STATUS_TONE[selectedSprint.status])}>
                  {enumLabel(selectedSprint.status)}
                </Badge>
              </div>
              <p className="text-xs text-muted-foreground">
                {dateLabel(selectedSprint.startDate)} to {dateLabel(selectedSprint.endDate)}
              </p>
            </div>
            <TabsList className="max-w-full overflow-x-auto" variant="line">
              <TabsTrigger value="overview"><Gauge data-icon="inline-start" /> Overview</TabsTrigger>
              <TabsTrigger value="team"><Users data-icon="inline-start" /> Team</TabsTrigger>
              <TabsTrigger value="tasks"><ListChecks data-icon="inline-start" /> Tasks</TabsTrigger>
              <TabsTrigger value="history"><History data-icon="inline-start" /> History</TabsTrigger>
            </TabsList>
          </div>

          <TabsContent value="overview" className="space-y-5">
            <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-6">
              <MetricCard
                label="Story points"
                value={number(selectedSprint.committedPoints)}
                hint={`${number(selectedSprint.completedPoints)} done · ${number(remainingPoints)} remaining`}
              />
              <MetricCard
                label="Team capacity"
                value={number(capacity.data?.totalEffectiveHours ?? selectedSprint.capacityHours, "h")}
                hint={`${number(totalAbsenceDays)} absence days`}
              />
              <MetricCard
                label="Average rate"
                value={averageHourlyRate == null ? "-" : `${money(averageHourlyRate, currency)}/h`}
                hint={`${teamRows.length} delivery member${teamRows.length === 1 ? "" : "s"}`}
              />
              <MetricCard
                label="Planned labor"
                value={money(plannedLaborCost, currency)}
                hint={`${hours(plannedEffortMinutes)} estimated effort`}
              />
              <MetricCard
                label="Actual labor"
                value={money(actualLaborCost, currency)}
                hint={`${hours(loggedEffortMinutes)} logged · ${hours(overtimeMinutes)} overtime`}
              />
              <MetricCard
                label="Sprint budget"
                value={money(sprintBudget, currency)}
                hint={latestHistory ? `${money(latestHistory.forecastAmount, currency)} latest forecast` : "No forecast yet"}
              />
            </div>

            <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_22rem]">
              <section className="min-w-0 space-y-3" aria-labelledby="forecast-result">
                <div>
                  <h3 id="forecast-result" className="font-semibold">Forecast result</h3>
                  <p className="text-xs text-muted-foreground">Planning estimate, not an invoice.</p>
                </div>

                {forecast.isError ? (
                  <p role="alert" className="rounded-md bg-destructive/10 p-3 text-sm text-destructive">
                    {forecastError(forecast.error)}
                  </p>
                ) : visibleForecast ? (
                  <div className="space-y-4">
                    <CostWidget forecast={visibleForecast} />
                    <CostExplanationPanel
                      forecast={visibleForecast}
                      plannedLaborCost={plannedLaborCost}
                      actualLaborCost={actualLaborCost}
                      overtimeMinutes={overtimeMinutes}
                      availabilityPct={availabilityPct}
                      totalAbsenceDays={totalAbsenceDays}
                      skillCoverage={skillCoverage}
                      coveredSkillCount={coveredSkillCount}
                      requiredSkillCount={requiredSkills.length}
                      maxLoadPct={maxLoadPct}
                      budget={sprintBudget}
                      currency={currency}
                    />
                  </div>
                ) : latestHistory ? (
                  <div className="space-y-4">
                    <StoredForecast forecast={latestHistory} currency={currency} />
                    <CostExplanationPanel
                      forecast={latestHistory}
                      plannedLaborCost={plannedLaborCost}
                      actualLaborCost={actualLaborCost}
                      overtimeMinutes={overtimeMinutes}
                      availabilityPct={availabilityPct}
                      totalAbsenceDays={totalAbsenceDays}
                      skillCoverage={skillCoverage}
                      coveredSkillCount={coveredSkillCount}
                      requiredSkillCount={requiredSkills.length}
                      maxLoadPct={maxLoadPct}
                      budget={sprintBudget}
                      currency={currency}
                    />
                  </div>
                ) : (
                  <EmptyState icon={LineChart} title="No forecast generated" description="Use the forecast actions above for this sprint." />
                )}
              </section>

              <section className="rounded-lg border bg-card p-4 shadow-card" aria-labelledby="planning-signals">
                <h3 id="planning-signals" className="font-semibold">Planning signals</h3>
                <div className="mt-4 space-y-4">
                  <SignalRow
                    label="Sprint completion"
                    value={completionRate}
                    detail={`${number(selectedSprint.completedPoints)} of ${number(selectedSprint.committedPoints)} points`}
                    tone="bg-blue-500"
                  />
                  <SignalRow
                    label="Developer availability"
                    value={availabilityPct}
                    detail={`${number(totalAbsenceDays)} planned absence days`}
                    tone="bg-emerald-500"
                  />
                  <SignalRow
                    label="Capacity utilisation"
                    value={capacity.data?.utilisationPct}
                    detail={`${number(capacity.data?.plannedPoints)} of ${number(capacity.data?.capacityPoints)} points`}
                    tone={(capacity.data?.utilisationPct ?? 0) > 100 ? "bg-destructive" : "bg-amber-500"}
                  />
                  <SignalRow
                    label="Skill coverage"
                    value={skillCoverage}
                    detail={requiredSkills.length > 0
                      ? `${coveredSkillCount} of ${requiredSkills.length} required skills`
                      : "No required skills listed"}
                    tone="bg-teal-500"
                  />
                </div>
                <dl className="mt-4 border-t pt-3 text-xs">
                  <InputRow label="Average velocity" value={`${number(velocity.data?.averageVelocity)} points`} />
                  <InputRow label="Sprint number" value={selectedSprint.sequenceNo} />
                  <InputRow label="Tasks" value={sprintTasks.length} />
                </dl>
              </section>
            </div>

            <ValidationSummary
              validation={validationResult}
              currency={currency}
              isPending={validationPending}
              error={validationError}
              isFallback={usingLocalValidation}
              onRetry={() => void validation.refetch()}
            />
          </TabsContent>

          <TabsContent value="team">
            <TeamTable
              rows={teamRows}
              currency={currency}
              isPending={members.isPending || profileQueries.some((query) => query.isPending)}
              error={members.error}
              onRetry={() => void members.refetch()}
            />
          </TabsContent>

          <TabsContent value="tasks">
            <TaskTable
              tasks={sprintTasks}
              currency={currency}
              isPending={taskBoard.isPending}
              error={taskBoard.error}
              rateFor={rateFor}
              timeEntriesByTask={timeEntriesByTask}
            />
          </TabsContent>

          <TabsContent value="history">
            <section className="rounded-lg border bg-card p-4 shadow-card" aria-labelledby="forecast-history">
              <div className="flex items-center justify-between gap-3">
                <div>
                  <h3 id="forecast-history" className="flex items-center gap-2 font-semibold">
                    <History className="size-4 text-primary" aria-hidden />
                    Prediction history
                  </h3>
                  <p className="text-xs text-muted-foreground">Every stored forecast for this sprint.</p>
                </div>
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  disabled={history.isFetching}
                  onClick={() => void history.refetch()}
                >
                  {history.isFetching ? <Loader2 className="size-4 animate-spin" aria-hidden /> : null}
                  Refresh
                </Button>
              </div>
              <HistoryTable
                history={history.data ?? []}
                currency={currency}
                budget={sprintBudget}
                isPending={history.isPending}
                error={history.error}
                onRetry={() => void history.refetch()}
              />
              <div className="mt-5">
                <ValidationDetails
                  validation={validationResult}
                  currency={currency}
                  isPending={validationPending}
                  error={validationError}
                  isFallback={usingLocalValidation}
                  onRetry={() => void validation.refetch()}
                />
              </div>
            </section>
          </TabsContent>
        </Tabs>
      )}
    </div>
  );
}

function CostExplanationPanel({
  forecast,
  plannedLaborCost,
  actualLaborCost,
  overtimeMinutes,
  availabilityPct,
  totalAbsenceDays,
  skillCoverage,
  coveredSkillCount,
  requiredSkillCount,
  maxLoadPct,
  budget,
  currency,
}: {
  forecast: CostForecast | CostTrendPoint;
  plannedLaborCost?: number | null;
  actualLaborCost?: number | null;
  overtimeMinutes: number;
  availabilityPct?: number | null;
  totalAbsenceDays?: number | null;
  skillCoverage?: number | null;
  coveredSkillCount: number;
  requiredSkillCount: number;
  maxLoadPct: number;
  budget?: number | null;
  currency: string;
}) {
  const budgetVariance = budget != null ? forecast.forecastAmount - budget : null;
  const drivers = "drivers" in forecast ? forecast.drivers : [];
  const explanation = "explanation" in forecast ? forecast.explanation : null;
  const confidence = "confidence" in forecast ? forecast.confidence : null;
  const variancePct = "varianceVsBudgetPct" in forecast ? forecast.varianceVsBudgetPct : null;
  const errorMarginAmount = "errorMarginAmount" in forecast ? forecast.errorMarginAmount : null;
  const topDrivers = drivers
    .slice()
    .sort((a, b) => Math.abs(b.contribution) - Math.abs(a.contribution))
    .slice(0, 4);

  return (
    <section className="rounded-lg border bg-card p-4 shadow-card" aria-labelledby="cost-explanation">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 id="cost-explanation" className="font-semibold">Cost explanation</h3>
          <p className="text-xs text-muted-foreground">
            Breakdown of the planning signals behind this forecast.
          </p>
        </div>
        <Badge variant="outline">{stageLabel(forecast.stage)}</Badge>
      </div>

      <div className="mt-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
        <ExplanationMetric label="Forecast amount" value={money(forecast.forecastAmount, currency)} />
        <ExplanationMetric label="Planned labor" value={money(plannedLaborCost, currency)} />
        <ExplanationMetric label="Actual labor so far" value={money(actualLaborCost, currency)} />
        <ExplanationMetric label="Overtime signal" value={hours(overtimeMinutes)} detail="Logged as overtime" />
        <ExplanationMetric
          label="Availability signal"
          value={availabilityPct == null ? "-" : `${number(availabilityPct)}%`}
          detail={`${number(totalAbsenceDays)} planned absence days`}
        />
        <ExplanationMetric
          label="Skill coverage"
          value={skillCoverage == null ? "-" : `${number(skillCoverage)}%`}
          detail={requiredSkillCount > 0
            ? `${coveredSkillCount} of ${requiredSkillCount} required skills covered`
            : "No required skills listed"}
        />
        <ExplanationMetric
          label="Workload pressure"
          value={maxLoadPct > 0 ? `${number(maxLoadPct)}%` : "-"}
          detail="Highest developer load"
        />
        <ExplanationMetric
          label="Budget variance"
          value={budgetVariance == null ? "-" : money(budgetVariance, currency)}
          detail={variancePct == null ? "Set budget for variance" : `${variancePct}% vs budget`}
          tone={budgetVariance != null && budgetVariance > 0 ? "text-destructive" : "text-emerald-600 dark:text-emerald-400"}
        />
        <ExplanationMetric
          label="Prediction interval"
          value={forecast.lowerBound != null && forecast.upperBound != null
            ? `${money(forecast.lowerBound, currency)} - ${money(forecast.upperBound, currency)}`
            : "-"}
          detail={errorMarginAmount == null ? "No margin stored" : `± ${money(errorMarginAmount, currency)}`}
        />
        <ExplanationMetric
          label="Confidence"
          value={confidence == null ? "-" : `${number(confidence * 100)}%`}
          detail={confidence == null ? "History rows do not expose confidence" : "Model confidence"}
        />
      </div>

      {explanation ? (
        <p className="mt-4 rounded-md bg-muted/50 p-3 text-sm leading-relaxed text-muted-foreground">
          {explanation}
        </p>
      ) : null}

      <div className="mt-4 border-t pt-3">
        <h4 className="text-sm font-medium">Top model drivers</h4>
        {topDrivers.length > 0 ? (
          <div className="mt-2 grid gap-2 sm:grid-cols-2">
            {topDrivers.map((driver) => (
              <div key={`${driver.feature}-${driver.label}`} className="rounded-md border bg-background/40 p-3">
                <div className="flex items-start justify-between gap-3">
                  <p className="text-sm font-medium">{driver.label ?? driver.feature}</p>
                  <Badge variant="outline" className="shrink-0 tabular-nums">
                    {driver.contribution > 0 ? "+" : ""}{number(driver.contribution)}
                  </Badge>
                </div>
                <p className="mt-1 text-xs text-muted-foreground">
                  {driver.detail ?? enumLabel(driver.direction)}
                </p>
              </div>
            ))}
          </div>
        ) : (
          <p className="mt-2 text-xs text-muted-foreground">
            Detailed model drivers are available after generating a fresh forecast. Stored history keeps the cost trend and interval.
          </p>
        )}
      </div>
    </section>
  );
}

function ExplanationMetric({
  label,
  value,
  detail,
  tone,
}: {
  label: string;
  value: string;
  detail?: string;
  tone?: string;
}) {
  return (
    <div className="rounded-md border bg-background/40 p-3">
      <p className="text-xs font-medium text-muted-foreground">{label}</p>
      <p className={cn("mt-1 text-lg font-semibold tabular-nums", tone)}>{value}</p>
      {detail ? <p className="mt-1 text-[11px] text-muted-foreground">{detail}</p> : null}
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
    <div className="flex items-center justify-between gap-4 border-b py-2 first:pt-0 last:border-b-0 last:pb-0">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="font-medium tabular-nums">{value}</dd>
    </div>
  );
}

function SignalRow({
  label,
  value,
  detail,
  tone,
}: {
  label: string;
  value?: number | null;
  detail: string;
  tone: string;
}) {
  const bounded = Math.max(0, Math.min(100, value ?? 0));
  return (
    <div>
      <div className="flex items-center justify-between gap-3 text-xs">
        <span className="font-medium">{label}</span>
        <span className="tabular-nums text-muted-foreground">
          {value == null ? "-" : `${number(value)}%`}
        </span>
      </div>
      <div
        className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-muted"
        role="meter"
        aria-label={label}
        aria-valuenow={value == null ? undefined : Math.round(value)}
        aria-valuemin={0}
        aria-valuemax={100}
      >
        <div className={cn("h-full rounded-full", tone)} style={{ width: `${bounded}%` }} />
      </div>
      <p className="mt-1 text-[11px] text-muted-foreground">{detail}</p>
    </div>
  );
}

function StoredForecast({ forecast, currency }: { forecast: CostTrendPoint; currency: string }) {
  return (
    <div className="rounded-lg border bg-card p-4 shadow-card">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <p className="text-xs font-medium text-muted-foreground">Latest stored forecast</p>
          <p className="mt-1 text-3xl font-semibold tabular-nums">
            {money(forecast.forecastAmount, currency)}
          </p>
        </div>
        <Badge variant="outline">{stageLabel(forecast.stage)}</Badge>
      </div>
      <p className="mt-2 text-xs text-muted-foreground tabular-nums">
        {forecast.lowerBound != null && forecast.upperBound != null
          ? `Likely ${money(forecast.lowerBound, currency)} to ${money(forecast.upperBound, currency)}`
          : "No prediction interval stored"}
      </p>
      <p className="mt-1 text-[11px] text-muted-foreground">
        Generated {dateTimeLabel(forecast.snapshotAt)}
      </p>
    </div>
  );
}

function TeamTable({
  rows,
  currency,
  isPending,
  error,
  onRetry,
}: {
  rows: TeamRow[];
  currency: string;
  isPending: boolean;
  error: unknown;
  onRetry: () => void;
}) {
  if (isPending) return <Skeleton className="h-64 w-full" />;
  if (error) return <ErrorState error={error} onRetry={onRetry} />;
  if (rows.length === 0) {
    return <EmptyState icon={Users} title="No delivery team" description="Add delivery members to the selected project." />;
  }

  return (
    <section className="rounded-lg border bg-card p-4 shadow-card" aria-labelledby="team-cost-plan">
      <div className="mb-4">
        <h3 id="team-cost-plan" className="font-semibold">Team cost and workload</h3>
        <p className="text-xs text-muted-foreground">Rates, availability, capacity, allocation, and sprint load.</p>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[70rem] text-left text-sm">
          <thead className="border-b text-xs text-muted-foreground">
            <tr>
              <th className="py-2 pr-3 font-medium">Developer</th>
              <th className="px-3 py-2 font-medium">Role</th>
              <th className="px-3 py-2 font-medium">Availability</th>
              <th className="px-3 py-2 font-medium">Capacity</th>
              <th className="px-3 py-2 font-medium">Allocation</th>
              <th className="px-3 py-2 font-medium">Workload</th>
              <th className="px-3 py-2 font-medium">Hourly rate</th>
              <th className="py-2 pl-3 font-medium">Skills</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.member.userId} className="border-b align-top last:border-b-0">
                <td className="py-3 pr-3">
                  <div className="flex items-center gap-2">
                    <Avatar className="size-8">
                      <AvatarImage src={row.member.avatarUrl ?? undefined} alt="" />
                      <AvatarFallback className="text-xs">{initials(row.member.fullName)}</AvatarFallback>
                    </Avatar>
                    <div className="min-w-0">
                      <p className="max-w-44 truncate font-medium">{row.member.fullName ?? "Unnamed member"}</p>
                      <p className="max-w-44 truncate text-[11px] text-muted-foreground">{row.member.email}</p>
                    </div>
                  </div>
                </td>
                <td className="px-3 py-3">
                  <p>{enumLabel(row.member.projectRole)}</p>
                  <p className="text-[11px] text-muted-foreground">{enumLabel(row.profile?.seniority)}</p>
                </td>
                <td className="px-3 py-3 tabular-nums">
                  {row.profile ? `${number(row.profile.availabilityRate * 100)}%` : "-"}
                </td>
                <td className="px-3 py-3 tabular-nums">
                  <p>{number(row.capacity?.effectiveHours, "h")}</p>
                  <p className="text-[11px] text-muted-foreground">{number(row.capacity?.absenceDays)} days absent</p>
                </td>
                <td className="px-3 py-3 tabular-nums">{row.member.allocationPct}%</td>
                <td className="px-3 py-3">
                  <div className="flex items-center gap-2">
                    <span className="w-10 tabular-nums">{row.loadPct == null ? "-" : `${number(row.loadPct)}%`}</span>
                    {row.loadPct != null ? (
                      <div className="h-1.5 w-16 overflow-hidden rounded-full bg-muted">
                        <div
                          className={cn("h-full rounded-full", row.loadPct > 100 ? "bg-destructive" : "bg-primary")}
                          style={{ width: `${Math.min(100, row.loadPct)}%` }}
                        />
                      </div>
                    ) : null}
                  </div>
                  <p className="mt-1 text-[11px] text-muted-foreground">{row.tasks.length} assigned tasks</p>
                </td>
                <td className="px-3 py-3 font-medium tabular-nums">
                  {row.profile?.hourlyRate == null ? "-" : `${money(row.profile.hourlyRate, currency)}/h`}
                </td>
                <td className="py-3 pl-3">
                  <div className="flex max-w-72 flex-wrap gap-1">
                    {row.skills.length > 0 ? row.skills.slice(0, 5).map((skill) => (
                      <Badge key={skill.skillId} variant="outline" className="font-normal">
                        {skill.skillName} · L{skill.proficiency}
                      </Badge>
                    )) : <span className="text-muted-foreground">No skills listed</span>}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function TaskTable({
  tasks,
  currency,
  isPending,
  error,
  rateFor,
  timeEntriesByTask,
}: {
  tasks: Task[];
  currency: string;
  isPending: boolean;
  error: unknown;
  rateFor: (userId?: string | null) => number | null | undefined;
  timeEntriesByTask: Map<string, SprintTimeEntry[]>;
}) {
  if (isPending) return <Skeleton className="h-64 w-full" />;
  if (error) return <ErrorState error={error} />;
  if (tasks.length === 0) {
    return <EmptyState icon={ListChecks} title="No sprint tasks" description="Tasks assigned to this sprint appear here." />;
  }

  return (
    <section className="rounded-lg border bg-card p-4 shadow-card" aria-labelledby="sprint-cost-tasks">
      <div className="mb-4">
        <h3 id="sprint-cost-tasks" className="font-semibold">Sprint task costs</h3>
        <p className="text-xs text-muted-foreground">Planned hours, logged effort, required skills, and labor cost.</p>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[66rem] text-left text-sm">
          <thead className="border-b text-xs text-muted-foreground">
            <tr>
              <th className="py-2 pr-3 font-medium">Task</th>
              <th className="px-3 py-2 font-medium">Assignee</th>
              <th className="px-3 py-2 font-medium">Status</th>
              <th className="px-3 py-2 font-medium">Required skills</th>
              <th className="px-3 py-2 font-medium">Planned</th>
              <th className="px-3 py-2 font-medium">Logged</th>
              <th className="py-2 pl-3 text-right font-medium">Actual cost</th>
            </tr>
          </thead>
          <tbody>
            {tasks.map((task) => {
              const entries = timeEntriesByTask.get(task.id) ?? [];
              const entryCost = entries.reduce(
                (sum, entry) =>
                  sum + (costForMinutes(entry.minutes, rateFor(entry.userId), entry.overtime) ?? 0),
                0,
              );
              const taskCost = entries.length > 0
                ? entryCost
                : costForMinutes(task.spentMinutes ?? 0, rateFor(task.assigneeId));
              return (
                <tr key={task.id} className="border-b align-top last:border-b-0">
                  <td className="py-3 pr-3">
                    <p className="max-w-64 font-medium">{task.title}</p>
                    <p className="text-[11px] text-muted-foreground">{task.itemKey} · {enumLabel(task.type)}</p>
                  </td>
                  <td className="px-3 py-3">{task.assigneeName ?? "Unassigned"}</td>
                  <td className="px-3 py-3">
                    <Badge className={cn("border-0 font-normal", TASK_STATUS_TONE[task.status])}>
                      {enumLabel(task.status)}
                    </Badge>
                  </td>
                  <td className="px-3 py-3">
                    <div className="flex max-w-56 flex-wrap gap-1">
                      {task.requiredSkills.length > 0 ? task.requiredSkills.map((skill) => (
                        <Badge key={skill} variant="outline" className="font-normal">{skill}</Badge>
                      )) : <span className="text-muted-foreground">None</span>}
                    </div>
                  </td>
                  <td className="px-3 py-3 tabular-nums">{hours(task.estimateMinutes)}</td>
                  <td className="px-3 py-3 tabular-nums">{hours(task.spentMinutes)}</td>
                  <td className="py-3 pl-3 text-right font-medium tabular-nums">{money(taskCost, currency)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function ValidationSummary({
  validation,
  currency,
  isPending,
  error,
  isFallback,
  onRetry,
}: {
  validation?: CostValidation;
  currency: string;
  isPending: boolean;
  error: unknown;
  isFallback: boolean;
  onRetry: () => void;
}) {
  if (isPending) return <Skeleton className="h-36 w-full" />;
  if (error) return <ErrorState error={error} onRetry={onRetry} />;
  if (validation?.accuracyEligible === false) {
    const isCancelled = validation.evaluationStatus === "CANCELLED";
    const varianceDetail = validation.liveVariance == null
      ? "Generate a forecast to compare"
      : validation.liveVariance > 0
        ? "Projected above latest forecast"
        : validation.liveVariance < 0
          ? "Projected below latest forecast"
          : "Matches latest forecast";

    return (
      <section className="rounded-lg border bg-card p-4 shadow-card" aria-labelledby="cost-tracking-summary">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h3 id="cost-tracking-summary" className="font-semibold">
              {isCancelled ? "Cost record" : "Forecast tracking"}
            </h3>
            <p className="text-xs text-muted-foreground">
              {isCancelled
                ? "Cancelled sprints are not included in model accuracy results."
                : "Live cost projection during execution. Final accuracy is calculated after sprint completion."}
            </p>
            {isFallback ? (
              <p className="mt-1 text-xs text-amber-600 dark:text-amber-400">
                Calculated from loaded tasks and forecast history while backend validation is unavailable.
              </p>
            ) : null}
          </div>
          <Badge variant="outline">{enumLabel(validation.evaluationStatus)}</Badge>
        </div>
        <div className="mt-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
          <ExplanationMetric
            label="Actual cost so far"
            value={money(validation.actualCostSoFar ?? validation.actualCost, currency)}
            detail="Logged labor and overtime"
          />
          <ExplanationMetric
            label="Estimated remaining"
            value={isCancelled ? "-" : money(validation.estimatedRemainingCost, currency)}
            detail={isCancelled ? "Sprint cancelled" : "Open task estimates"}
          />
          <ExplanationMetric
            label="Projected final cost"
            value={isCancelled ? "-" : money(validation.projectedFinalCost, currency)}
            detail={isCancelled ? "No final projection" : "Actual plus remaining estimate"}
          />
          <ExplanationMetric
            label="Latest forecast"
            value={money(validation.latestForecastAmount, currency)}
            detail="Stored model prediction"
          />
          <ExplanationMetric
            label="Live variance"
            value={isCancelled ? "-" : money(validation.liveVariance, currency)}
            detail={isCancelled ? "Not evaluated" : varianceDetail}
          />
        </div>
      </section>
    );
  }
  if (!validation || validation.predictions.length === 0) {
    return (
      <EmptyState
        icon={Gauge}
        title="No accuracy data yet"
        description="This sprint is complete, but it has no stored forecasts to validate."
      />
    );
  }

  return (
    <section className="rounded-lg border bg-card p-4 shadow-card" aria-labelledby="cost-validation-summary">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 id="cost-validation-summary" className="font-semibold">Final accuracy validation</h3>
          <p className="text-xs text-muted-foreground">
            Stored predictions compared with the completed sprint's final logged labor cost.
          </p>
          {isFallback ? (
            <p className="mt-1 text-xs text-amber-600 dark:text-amber-400">
              Calculated from loaded forecast history while backend validation is unavailable.
            </p>
          ) : null}
        </div>
        <div className="flex flex-wrap gap-2">
          <Badge variant="outline">Final</Badge>
          {validation.closerStage ? (
            <Badge variant="outline">Closer: {stageLabel(validation.closerStage)}</Badge>
          ) : null}
        </div>
      </div>
      <div className="mt-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
        <ExplanationMetric label="Actual cost" value={money(validation.actualCost, currency)} />
        <ExplanationMetric label="MAE" value={money(validation.mae, currency)} detail="Average absolute error" />
        <ExplanationMetric label="RMSE" value={money(validation.rmse, currency)} detail="Large-error penalty" />
        <ExplanationMetric label="MAPE" value={validation.mape == null ? "-" : `${number(validation.mape)}%`} detail="Average percentage error" />
        <ExplanationMetric
          label="Mid-sprint error"
          value={validation.midSprint?.absolutePercentageError == null ? "-" : `${number(validation.midSprint.absolutePercentageError)}%`}
          detail={validation.midSprint ? money(validation.midSprint.absoluteError, currency) : "No mid-sprint forecast"}
        />
      </div>
    </section>
  );
}

function ValidationDetails({
  validation,
  currency,
  isPending,
  error,
  isFallback,
  onRetry,
}: {
  validation?: CostValidation;
  currency: string;
  isPending: boolean;
  error: unknown;
  isFallback: boolean;
  onRetry: () => void;
}) {
  if (isPending) return <Skeleton className="h-32 w-full" />;
  if (error) return <ErrorState error={error} onRetry={onRetry} />;
  if (validation?.accuracyEligible === false) {
    return (
      <section className="border-t pt-4" aria-labelledby="cost-validation-pending">
        <h3 id="cost-validation-pending" className="font-semibold">Accuracy pending sprint completion</h3>
        <p className="mt-1 text-xs text-muted-foreground">
          Forecast history is retained now and will be scored against final actual cost when this sprint is completed.
        </p>
      </section>
    );
  }
  if (!validation || validation.predictions.length === 0) {
    return null;
  }

  return (
    <section aria-labelledby="cost-validation-details">
      <div className="flex flex-wrap items-center justify-between gap-3 border-t pt-4">
        <div>
          <h3 id="cost-validation-details" className="font-semibold">Prediction accuracy</h3>
          <p className="text-xs text-muted-foreground">
            Actual cost is calculated from logged minutes, overtime, and developer rates.
          </p>
          {isFallback ? (
            <p className="mt-1 text-xs text-amber-600 dark:text-amber-400">
              Backend validation is unavailable; these values use the loaded history.
            </p>
          ) : null}
        </div>
        <div className="flex flex-wrap gap-2 text-xs text-muted-foreground">
          <span>MAE {money(validation.mae, currency)}</span>
          <span>RMSE {money(validation.rmse, currency)}</span>
          <span>MAPE {validation.mape == null ? "-" : `${number(validation.mape)}%`}</span>
        </div>
      </div>
      <div className="mt-3 overflow-x-auto">
        <table className="w-full min-w-[46rem] text-left text-sm">
          <thead className="border-b text-xs text-muted-foreground">
            <tr>
              <th className="py-2 pr-3 font-medium">Time</th>
              <th className="px-3 py-2 font-medium">Stage</th>
              <th className="px-3 py-2 font-medium">Predicted</th>
              <th className="px-3 py-2 font-medium">Actual</th>
              <th className="px-3 py-2 font-medium">Error</th>
              <th className="py-2 pl-3 font-medium">Error %</th>
            </tr>
          </thead>
          <tbody>
            {validation.predictions.map((row) => (
              <ValidationRow key={row.forecastId} row={row} currency={currency} />
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function ValidationRow({ row, currency }: { row: CostPredictionAccuracy; currency: string }) {
  return (
    <tr className="border-b last:border-b-0">
      <td className="py-2 pr-3 text-muted-foreground">{dateTimeLabel(row.snapshotAt)}</td>
      <td className="px-3 py-2"><Badge variant="outline">{stageLabel(row.stage)}</Badge></td>
      <td className="px-3 py-2 font-medium tabular-nums">{money(row.predictedCost, currency)}</td>
      <td className="px-3 py-2 tabular-nums">{money(row.actualCost, currency)}</td>
      <td className={cn("px-3 py-2 tabular-nums", row.errorAmount < 0 ? "text-emerald-600 dark:text-emerald-400" : "text-destructive")}>
        {row.errorAmount > 0 ? "+" : ""}{money(row.errorAmount, currency)}
      </td>
      <td className="py-2 pl-3 tabular-nums">
        {row.absolutePercentageError == null ? "-" : `${number(row.absolutePercentageError)}%`}
      </td>
    </tr>
  );
}

function HistoryTable({
  history,
  currency,
  budget,
  isPending,
  error,
  onRetry,
}: {
  history: CostTrendPoint[];
  currency: string;
  budget?: number | null;
  isPending: boolean;
  error: unknown;
  onRetry: () => void;
}) {
  if (isPending) return <Skeleton className="mt-4 h-32 w-full" />;
  if (error) return <ErrorState error={error} onRetry={onRetry} />;
  if (history.length === 0) {
    return <EmptyState icon={History} title="No prediction history" description="Forecast records for this sprint appear here." />;
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
              <td className="px-3 py-2"><Badge variant="outline">{stageLabel(point.stage)}</Badge></td>
              <td className="px-3 py-2 font-medium tabular-nums">{money(point.forecastAmount, currency)}</td>
              <td className="px-3 py-2 text-muted-foreground tabular-nums">
                {point.lowerBound != null && point.upperBound != null
                  ? `${money(point.lowerBound, currency)} to ${money(point.upperBound, currency)}`
                  : "-"}
              </td>
              <td className="py-2 pl-3 text-muted-foreground tabular-nums">
                {money(point.budgetAmount ?? budget, currency)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
