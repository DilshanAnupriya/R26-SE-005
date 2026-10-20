"use client";

import { useState } from "react";
import Link from "next/link";
import { useQueries, useQuery } from "@tanstack/react-query";
import {
  Activity,
  ArrowDown,
  ArrowUp,
  CalendarDays,
  ChevronRight,
  CircleHelp,
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
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { EmptyState, ErrorState } from "@/components/feedback/states";
import { CostWidget } from "@/features/ai/components/cost-widget";
import { aiApi } from "@/features/ai/api";
import { CostRiskCrossCheckPanel } from "@/features/ai/components/cost-risk-cross-check-panel";
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
import { ForecastMethodDialog } from "./ForecastMethodDialog";
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

const HELP_TEXT: Record<string, string> = {
  Project: "Selects the project whose team rates, budget, tasks, and sprints supply the cost data.",
  Sprint: "Selects the sprint used by every summary, forecast, team, task, and history panel below.",
  "Initial forecast": "Estimates the full sprint cost from planned task effort and the team's blended hourly rate before execution data is needed.",
  "Mid-sprint forecast": "Combines labor cost already incurred with ML-predicted remaining effort. Very early sprints use the initial model until enough progress exists.",
  "Story points": "Total points committed to the sprint, with completed and remaining points shown underneath.",
  "Team capacity": "Sum of effective developer hours after allocation and planned absence adjustments.",
  "Average rate": "Blended hourly rate calculated from delivery members who have an hourly rate in their developer profile.",
  "Planned sprint cost": "The original task-estimate baseline: estimated minutes multiplied by assignee rates, with the blended rate used when needed.",
  "Cost incurred so far": "Labor cost recorded to date from logged time. Overtime entries are priced at 1.5 times the normal rate.",
  "Final recorded cost": "The completed sprint's recorded labor cost from logged time and developer rates, including overtime.",
  "Sprint budget": "The spending limit stored for the sprint, or the project budget when the sprint has no separate value.",
  "Forecast result": "The selected initial or mid-sprint model's predicted final labor cost, including its estimated range.",
  "Planning signals": "Live sprint inputs that provide context for the forecast and help explain delivery pressure.",
  "Sprint completion": "Completed story points divided by committed story points.",
  "Developer availability": "Average profile availability across delivery members, alongside planned absence days.",
  "Capacity utilisation": "Planned sprint points divided by the team's available point capacity. Values above 100% indicate overcommitment.",
  "Skill coverage": "Required task skills covered by at least one delivery member with proficiency level 3 or higher.",
  "Average velocity": "Average story points completed in the project's recent completed sprints.",
  "Sprint number": "The sprint's sequence number, used to give the model delivery-history context.",
  Tasks: "Number of tasks currently assigned to the selected sprint.",
  "Cost explanation": "Shows the financial and human-aware signals supplied with the forecast so the result can be reviewed rather than treated as a black box.",
  "Forecast final cost": "The predicted total labor cost when the sprint finishes; it is not the original plan or the amount already recorded.",
  "Overtime signal": "Total logged overtime hours. Overtime contributes at 1.5 times the normal hourly rate.",
  "Availability signal": "Average developer availability used to represent reduced capacity from planned absence.",
  "Workload pressure": "The highest individual developer load: open estimated hours divided by that developer's effective capacity.",
  "Budget variance": "Forecast final cost minus sprint budget. A positive value means the forecast is over budget.",
  "Prediction interval": "A heuristic likely range based on held-out model error; it is not a probability confidence interval.",
  "Cost comparison": "Places the forecast beside the original plan, sprint budget, and recorded labor cost using the same visual scale.",
  "Budget position": "Shows how much of the sprint budget the forecast is expected to use and whether it remains within the limit.",
  "Planning context": "Human-aware delivery signals that provide context for the forecast. They are not presented as direct monetary contributions.",
  Availability: "Average developer availability used to represent reduced capacity from planned absence.",
  "Cost drivers": "Initial forecasts show exact formula components. Mid-sprint forecasts add signed local Shapley influences that reconcile to the final amount.",
  "Latest stored forecast": "Most recently saved forecast for this sprint, loaded from forecast history.",
  "Team cost and workload": "Developer rates, availability, effective capacity, allocation, skills, and assigned workload used by sprint planning.",
  "Sprint task costs": "Task-level plan and actual labor view. Actual cost comes from time entries or, when unavailable, task spent minutes.",
  "Prediction history": "Every stored forecast snapshot for the selected sprint, allowing initial and mid-sprint estimates to be compared over time.",
  "Cost record": "Retains incurred labor for a cancelled sprint without including it in model accuracy evaluation.",
  "Actual cost so far": "Cost of all labor logged so far, including the overtime multiplier where applicable.",
  "Estimated remaining": "Estimated cost of unfinished task effort using assignee rates or the blended fallback rate.",
  "Projected final cost": "Actual cost so far plus the estimated cost of remaining work. This is live tracking, not final accuracy.",
  "Latest forecast": "The newest stored initial or mid-sprint model prediction.",
  "Live variance": "Projected final cost minus the latest forecast. Positive means the live projection is higher.",
  "Final accuracy validation": "Evaluates stored forecasts only after sprint completion, when final actual labor cost is available.",
  "Actual cost": "Final completed-sprint labor cost calculated from logged minutes, developer rates, and overtime.",
  MAE: "Mean Absolute Error: the average monetary distance between forecasts and final actual cost. Lower is better.",
  RMSE: "Root Mean Squared Error: similar to MAE but penalizes large forecasting errors more strongly. Lower is better.",
  MAPE: "Mean Absolute Percentage Error: average forecast error as a percentage of final actual cost. Lower is better.",
  "Mid-sprint error": "Percentage difference between the latest mid-sprint prediction and the completed sprint's final actual cost.",
  "Accuracy pending sprint completion": "Accuracy is intentionally withheld until the sprint is completed, preventing a total-cost forecast from being compared with partial actual cost.",
  "Prediction accuracy": "Forecast-by-forecast comparison against final actual labor cost for this completed sprint.",
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

function HelpHint({ label }: { label: string }) {
  const explanation = HELP_TEXT[label];
  if (!explanation) return null;

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <button
          type="button"
          className="inline-flex size-5 shrink-0 items-center justify-center text-muted-foreground/70 transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          aria-label={`Explain ${label}`}
        >
          <CircleHelp className="size-3.5" aria-hidden />
        </button>
      </TooltipTrigger>
      <TooltipContent side="top" sideOffset={6} className="max-w-72 leading-relaxed">
        {explanation}
      </TooltipContent>
    </Tooltip>
  );
}

function LabelWithHelp({ label }: { label: string }) {
  return (
    <span className="inline-flex items-center gap-1">
      <span>{label}</span>
      <HelpHint label={label} />
    </span>
  );
}

function money(amount?: number | null, currency = "USD", maximumFractionDigits = 0) {
  if (amount == null || !Number.isFinite(amount)) return "-";
  return new Intl.NumberFormat(undefined, {
    style: "currency",
    currency,
    maximumFractionDigits,
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
  const unestimatedTaskCount = sprintTasks.filter(
    (task) => task.estimateMinutes == null || task.estimateMinutes <= 0,
  ).length;
  const planIssue = taskBoard.isSuccess
    ? sprintTasks.length === 0
      ? "Add at least one task with a positive effort estimate before forecasting cost."
      : unestimatedTaskCount > 0
        ? `Estimate all sprint tasks before forecasting cost (${unestimatedTaskCount} missing or zero).`
        : null
    : null;
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
  const actualCostLabel = selectedSprint?.status === "COMPLETED"
    ? "Final recorded cost"
    : "Cost incurred so far";
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
            <div className="flex items-center gap-1 text-xs font-medium text-muted-foreground">
              <label htmlFor="cost-project">Project</label>
              <HelpHint label="Project" />
            </div>
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
            <div className="flex items-center gap-1 text-xs font-medium text-muted-foreground">
              <label htmlFor="cost-sprint">Sprint</label>
              <HelpHint label="Sprint" />
            </div>
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
            <div className="flex items-center gap-1">
              <Button
                type="button"
                variant="secondary"
                disabled={!selectedSprint || forecast.isPending || Boolean(planIssue)}
                onClick={() => runForecast("initial")}
              >
                {pendingStage === "initial" ? (
                  <Loader2 className="size-4 animate-spin" aria-hidden />
                ) : (
                  <LineChart className="size-4" aria-hidden />
                )}
                Initial forecast
              </Button>
              <ForecastMethodDialog stage="initial" />
            </div>
            <div className="flex items-center gap-1">
              <Button
                type="button"
                disabled={!selectedSprint || forecast.isPending || Boolean(planIssue)}
                onClick={() => runForecast("mid-sprint")}
              >
                {pendingStage === "mid-sprint" ? (
                  <Loader2 className="size-4 animate-spin" aria-hidden />
                ) : (
                  <Activity className="size-4" aria-hidden />
                )}
                Mid-sprint forecast
              </Button>
              <ForecastMethodDialog stage="mid-sprint" />
            </div>
          </div>
        </div>
        {planIssue ? (
          <p role="status" className="mt-3 rounded-md bg-amber-500/10 p-3 text-sm text-amber-800 dark:text-amber-300">
            {planIssue}
          </p>
        ) : null}
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
              {selectedSprint.status === "COMPLETED" && remainingPoints != null && remainingPoints > 0 ? (
                <p className="mt-1 text-xs font-medium text-amber-700 dark:text-amber-400">
                  Sprint closed at {number(selectedSprint.completedPoints)} of {number(selectedSprint.committedPoints)} points. {number(remainingPoints)} points moved to the next sprint.
                </p>
              ) : null}
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
                label="Planned sprint cost"
                value={planIssue ? "-" : money(plannedLaborCost, currency)}
                hint={planIssue ? "Estimate sprint tasks to calculate a plan" : `${hours(plannedEffortMinutes)} estimated effort`}
              />
              <MetricCard
                label={actualCostLabel}
                value={money(actualLaborCost, currency)}
                hint={`${hours(loggedEffortMinutes)} logged · ${hours(overtimeMinutes)} overtime`}
              />
              <MetricCard
                label="Sprint budget"
                value={money(sprintBudget, currency)}
                hint={planIssue ? "Forecast unavailable until tasks are estimated" : latestHistory ? `${money(latestHistory.forecastAmount, currency)} latest forecast` : "No forecast yet"}
              />
            </div>

            <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_22rem]">
              <section className="min-w-0 space-y-3" aria-labelledby="forecast-result">
                <div>
                  <h3 id="forecast-result" className="font-semibold">
                    <LabelWithHelp label="Forecast result" />
                  </h3>
                  <p className="text-xs text-muted-foreground">Planning estimate, not an invoice.</p>
                </div>

                {planIssue ? (
                  <p role="status" className="rounded-md bg-amber-500/10 p-3 text-sm text-amber-800 dark:text-amber-300">
                    {planIssue} Any earlier forecast in History reflects an older plan.
                  </p>
                ) : forecast.isError ? (
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
                      actualCostLabel={actualCostLabel}
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
                      actualCostLabel={actualCostLabel}
                    />
                  </div>
                ) : (
                  <EmptyState icon={LineChart} title="No forecast generated" description="Use the forecast actions above for this sprint." />
                )}
              </section>

              {/* The sidebar column. The cross-check sits above the planning signals
                  rather than beside the forecast: those signals are inputs the cost
                  model already reads, and this is the one reading on the page that
                  comes from somewhere the cost model cannot see. */}
              <div className="space-y-5">
                {planIssue ? (
                  <section className="rounded-lg border bg-card p-4 shadow-card" aria-label="Cost vs risk">
                    <h3 className="font-semibold">Cost vs risk</h3>
                    <p className="mt-2 text-sm text-muted-foreground">
                      Estimate sprint tasks before comparing cost and risk.
                    </p>
                  </section>
                ) : (
                  <CostRiskCrossCheckPanel sprintId={selectedSprint.id} />
                )}

                <section className="rounded-lg border bg-card p-4 shadow-card" aria-labelledby="planning-signals">
                <h3 id="planning-signals" className="font-semibold">
                  <LabelWithHelp label="Planning signals" />
                </h3>
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
            </div>

            {selectedSprint.status === "COMPLETED" || selectedSprint.status === "CANCELLED" ? (
              <ValidationSummary
                validation={validationResult}
                currency={currency}
                isPending={validationPending}
                error={validationError}
                isFallback={usingLocalValidation}
                onRetry={() => void validation.refetch()}
              />
            ) : null}
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
                    <LabelWithHelp label="Prediction history" />
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
              {planIssue ? (
                <p className="mt-3 text-sm text-amber-800 dark:text-amber-300">
                  Current plan is incomplete. Earlier forecast rows are retained for audit and should not be used as the current cost estimate.
                </p>
              ) : null}
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
  actualCostLabel,
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
  actualCostLabel: string;
}) {
  const budgetVariance = budget != null ? forecast.forecastAmount - budget : null;
  const drivers = "drivers" in forecast ? forecast.drivers : [];
  const budgetUsagePct = budget != null && budget > 0
    ? (forecast.forecastAmount / budget) * 100
    : null;
  const comparisonMaximum = Math.max(
    1,
    forecast.forecastAmount,
    plannedLaborCost ?? 0,
    actualLaborCost ?? 0,
    budget ?? 0,
  );
  const topDrivers = drivers
    .slice()
    .sort((a, b) => Math.abs(b.contribution) - Math.abs(a.contribution))
    .slice(0, 8);

  return (
    <section className="rounded-lg border bg-card p-4 shadow-card" aria-labelledby="cost-explanation">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 id="cost-explanation" className="font-semibold">
            <LabelWithHelp label="Cost explanation" />
          </h3>
          <p className="text-xs text-muted-foreground">
            Breakdown of the planning signals behind this forecast.
          </p>
        </div>
        <Badge variant="outline">{stageLabel(forecast.stage)}</Badge>
      </div>

      <div className="mt-4 grid gap-5 lg:grid-cols-[minmax(0,0.9fr)_minmax(22rem,1.1fr)]">
        <div className="space-y-5">
          <div>
            <p className="text-xs font-medium text-muted-foreground">
              <LabelWithHelp label="Forecast final cost" />
            </p>
            <p className="mt-1 text-3xl font-semibold tabular-nums">
              {money(forecast.forecastAmount, currency)}
            </p>
            <p className="mt-1 text-xs text-muted-foreground">Expected total labor cost at sprint completion</p>
          </div>

          <ForecastRange
            forecastAmount={forecast.forecastAmount}
            lowerBound={forecast.lowerBound}
            upperBound={forecast.upperBound}
            currency={currency}
          />

          <div className="border-t pt-4">
            <h4 className="text-sm font-medium">
              <LabelWithHelp label="Budget position" />
            </h4>
            {budgetVariance == null ? (
              <p className="mt-2 text-sm text-muted-foreground">Set a sprint budget to see the forecast position.</p>
            ) : (
              <div className="mt-2 flex items-start gap-2">
                {budgetVariance > 0 ? (
                  <ArrowUp className="mt-0.5 size-4 shrink-0 text-destructive" aria-hidden />
                ) : (
                  <ArrowDown className="mt-0.5 size-4 shrink-0 text-emerald-600" aria-hidden />
                )}
                <div>
                  <p className={cn(
                    "font-semibold tabular-nums",
                    budgetVariance > 0 ? "text-destructive" : "text-emerald-600 dark:text-emerald-400",
                  )}>
                    {money(Math.abs(budgetVariance), currency)} {budgetVariance > 0 ? "over" : "under"} budget
                  </p>
                  <p className="mt-0.5 text-xs text-muted-foreground">
                    {budgetUsagePct == null
                      ? "Budget usage is unavailable"
                      : `Forecast uses ${number(budgetUsagePct)}% of the sprint budget`}
                  </p>
                </div>
              </div>
            )}
          </div>
        </div>

        <div className="border-t pt-4 lg:border-l lg:border-t-0 lg:pl-5 lg:pt-0">
          <h4 className="text-sm font-medium">
            <LabelWithHelp label="Cost comparison" />
          </h4>
          <div className="mt-4 space-y-4">
            <CostComparisonBar
              label="Forecast"
              amount={forecast.forecastAmount}
              maximum={comparisonMaximum}
              currency={currency}
              tone="bg-primary"
            />
            <CostComparisonBar
              label="Original plan"
              amount={plannedLaborCost}
              maximum={comparisonMaximum}
              currency={currency}
              tone="bg-slate-500 dark:bg-slate-400"
            />
            <CostComparisonBar
              label="Sprint budget"
              amount={budget}
              maximum={comparisonMaximum}
              currency={currency}
              tone="bg-amber-500"
            />
            <CostComparisonBar
              label={actualCostLabel}
              amount={actualLaborCost}
              maximum={comparisonMaximum}
              currency={currency}
              tone="bg-emerald-500"
            />
          </div>
        </div>
      </div>

      <div className="mt-5 border-t pt-4">
        <h4 className="text-sm font-medium">
          <LabelWithHelp label="Planning context" />
        </h4>
        <p className="mt-1 text-xs text-muted-foreground">
          Human-aware signals considered alongside the financial forecast.
        </p>
        <div className="mt-4 grid gap-x-6 gap-y-4 sm:grid-cols-2">
          <ContextSignal
            label="Availability"
            value={availabilityPct}
            detail={`${number(totalAbsenceDays)} planned absence days`}
            tone="bg-emerald-500"
          />
          <ContextSignal
            label="Skill coverage"
            value={skillCoverage}
            detail={requiredSkillCount > 0
              ? `${coveredSkillCount} of ${requiredSkillCount} required skills covered`
              : "No required skills listed"}
            tone="bg-teal-500"
          />
          <ContextSignal
            label="Workload pressure"
            value={maxLoadPct > 0 ? maxLoadPct : null}
            detail="Highest developer load"
            tone={(maxLoadPct ?? 0) > 100 ? "bg-destructive" : "bg-amber-500"}
          />
          <div>
            <div className="flex items-center justify-between gap-3 text-xs">
              <span className="font-medium"><LabelWithHelp label="Overtime signal" /></span>
              <span className="font-semibold tabular-nums">{hours(overtimeMinutes)}</span>
            </div>
            <div className="mt-2 flex items-center gap-2 text-xs text-muted-foreground">
              <span
                className={cn(
                  "size-2 shrink-0 rounded-full",
                  overtimeMinutes > 0 ? "bg-violet-500" : "bg-muted-foreground/30",
                )}
                aria-hidden
              />
              <span>{overtimeMinutes > 0 ? "Overtime has been recorded" : "No overtime recorded"}</span>
            </div>
          </div>
        </div>
      </div>

      <div className="mt-4 border-t pt-3">
        <h4 className="text-sm font-medium">
          <LabelWithHelp label="Cost drivers" />
        </h4>
        <p className="mt-1 text-xs text-muted-foreground">
          {forecast.stage === "INITIAL"
            ? "Exact formula contribution: planned effort priced at the blended hourly rate."
            : "Actual cost plus the neutral ML reference and signed local Shapley impacts equals the forecast amount."}
        </p>
        {topDrivers.length > 0 ? (
          <div className="mt-2 grid gap-2 sm:grid-cols-2">
            {topDrivers.map((driver) => {
              const isComponent = [
                "planned_effort_minutes",
                "actual_cost_so_far",
                "model_reference",
              ].includes(driver.feature);
              const increasing = driver.contribution >= 0;
              const DirectionIcon = increasing ? ArrowUp : ArrowDown;
              const driverType = driver.feature === "model_reference"
                ? "ML reference"
                : isComponent
                  ? "Cost component"
                  : "Model influence";
              const driverLabel = driver.feature === "actual_cost_so_far"
                ? actualCostLabel
                : (driver.label ?? driver.feature);
              return (
                <div key={`${driver.feature}-${driver.label}`} className="rounded-md border bg-background/40 p-3">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="min-w-0">
                      <p className="flex items-center gap-1.5 text-sm font-medium">
                        <DirectionIcon
                          className={cn(
                            "size-3.5 shrink-0",
                            isComponent
                              ? "text-muted-foreground"
                              : increasing
                                ? "text-amber-600 dark:text-amber-400"
                                : "text-emerald-600 dark:text-emerald-400",
                          )}
                          aria-hidden
                        />
                        <span>{driverLabel}</span>
                      </p>
                      <p className="mt-0.5 text-[10px] font-medium uppercase text-muted-foreground">
                        {driverType}
                      </p>
                    </div>
                    <Badge
                      variant="outline"
                      className={cn(
                        "shrink-0 tabular-nums",
                        !isComponent && (increasing
                          ? "text-amber-700 dark:text-amber-400"
                          : "text-emerald-700 dark:text-emerald-400"),
                      )}
                    >
                      {driver.contribution > 0 ? "+" : ""}
                      {money(driver.contribution, currency, 2)}
                    </Badge>
                  </div>
                  <p className="mt-1 text-xs text-muted-foreground">
                    {driver.detail ?? enumLabel(driver.direction)}
                  </p>
                </div>
              );
            })}
          </div>
        ) : (
          <p className="mt-2 text-xs text-muted-foreground">
            Detailed forecast drivers are available after generating a fresh forecast. Stored history keeps the cost trend and interval.
          </p>
        )}
      </div>
    </section>
  );
}

function ForecastRange({
  forecastAmount,
  lowerBound,
  upperBound,
  currency,
}: {
  forecastAmount: number;
  lowerBound?: number | null;
  upperBound?: number | null;
  currency: string;
}) {
  const hasRange = lowerBound != null && upperBound != null && upperBound > lowerBound;
  const markerPosition = hasRange
    ? Math.max(0, Math.min(100, ((forecastAmount - lowerBound) / (upperBound - lowerBound)) * 100))
    : 50;

  return (
    <div className="border-t pt-4">
      <div className="flex items-center justify-between gap-3">
        <p className="text-sm font-medium"><LabelWithHelp label="Prediction interval" /></p>
        <span className="text-[11px] text-muted-foreground">Estimated range</span>
      </div>
      {hasRange ? (
        <div className="mt-3">
          <div className="relative h-5" aria-hidden>
            <div className="absolute inset-x-1 top-2 h-1 rounded-full bg-primary/20" />
            <div className="absolute left-1 top-1 size-3 rounded-full border-2 border-primary bg-card" />
            <div className="absolute right-1 top-1 size-3 rounded-full border-2 border-primary bg-card" />
            <div
              className="absolute top-0.5 size-4 -translate-x-1/2 rounded-full border-4 border-card bg-primary shadow-sm"
              style={{ left: `${markerPosition}%` }}
            />
          </div>
          <div className="flex items-start justify-between gap-3 text-[11px] tabular-nums text-muted-foreground">
            <span>{money(lowerBound, currency)}</span>
            <span className="font-semibold text-foreground">{money(forecastAmount, currency)}</span>
            <span>{money(upperBound, currency)}</span>
          </div>
        </div>
      ) : (
        <p className="mt-2 text-xs text-muted-foreground">No estimated range is stored for this forecast.</p>
      )}
    </div>
  );
}

function CostComparisonBar({
  label,
  amount,
  maximum,
  currency,
  tone,
}: {
  label: string;
  amount?: number | null;
  maximum: number;
  currency: string;
  tone: string;
}) {
  const width = amount == null ? 0 : Math.max(0, Math.min(100, (amount / maximum) * 100));

  return (
    <div>
      <div className="flex items-center justify-between gap-3 text-xs">
        <span className="font-medium">{label}</span>
        <span className="font-semibold tabular-nums">{money(amount, currency)}</span>
      </div>
      <div
        className="mt-1.5 h-2 overflow-hidden rounded-full bg-muted"
        role="meter"
        aria-label={`${label} cost`}
        aria-valuemin={0}
        aria-valuemax={maximum}
        aria-valuenow={amount ?? undefined}
      >
        <div
          className={cn("h-full rounded-full transition-[width] duration-300", tone)}
          style={{ width: `${width}%` }}
        />
      </div>
    </div>
  );
}

function ContextSignal({
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
        <span className="font-medium"><LabelWithHelp label={label} /></span>
        <span className="font-semibold tabular-nums">
          {value == null ? "-" : `${number(value)}%`}
        </span>
      </div>
      <div
        className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-muted"
        role="meter"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={value == null ? undefined : Math.round(value)}
      >
        <div className={cn("h-full rounded-full", tone)} style={{ width: `${bounded}%` }} />
      </div>
      <p className="mt-1 text-[11px] text-muted-foreground">{detail}</p>
    </div>
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
      <p className="text-xs font-medium text-muted-foreground">
        <LabelWithHelp label={label} />
      </p>
      <p className={cn("mt-1 text-lg font-semibold tabular-nums", tone)}>{value}</p>
      {detail ? <p className="mt-1 text-[11px] text-muted-foreground">{detail}</p> : null}
    </div>
  );
}

function MetricCard({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="rounded-lg border bg-card p-4 shadow-card">
      <p className="text-xs font-medium text-muted-foreground">
        <LabelWithHelp label={label} />
      </p>
      <p className="mt-1 text-2xl font-semibold tabular-nums">{value}</p>
      {hint ? <p className="mt-1 text-xs text-muted-foreground">{hint}</p> : null}
    </div>
  );
}

function InputRow({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4 border-b py-2 first:pt-0 last:border-b-0 last:pb-0">
      <dt className="text-muted-foreground"><LabelWithHelp label={label} /></dt>
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
        <span className="font-medium"><LabelWithHelp label={label} /></span>
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
          <p className="text-xs font-medium text-muted-foreground">
            <LabelWithHelp label="Latest stored forecast" />
          </p>
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
  const [selectedUserId, setSelectedUserId] = useState<string | null>(null);
  const selectedRow = rows.find((row) => row.member.userId === selectedUserId) ?? null;

  if (isPending) return <Skeleton className="h-64 w-full" />;
  if (error) return <ErrorState error={error} onRetry={onRetry} />;
  if (rows.length === 0) {
    return <EmptyState icon={Users} title="No delivery team" description="Add delivery members to the selected project." />;
  }

  return (
    <section className="rounded-lg border bg-card p-4 shadow-card" aria-labelledby="team-cost-plan">
      <div className="mb-4">
        <h3 id="team-cost-plan" className="font-semibold">
          <LabelWithHelp label="Team cost and workload" />
        </h3>
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
                  <button
                    type="button"
                    className="group flex w-full items-center gap-2 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                    onClick={() => setSelectedUserId(row.member.userId)}
                    title="View developer profile"
                  >
                    <Avatar className="size-8">
                      <AvatarImage src={row.member.avatarUrl ?? undefined} alt="" />
                      <AvatarFallback className="text-xs">{initials(row.member.fullName)}</AvatarFallback>
                    </Avatar>
                    <div className="min-w-0">
                      <p className="max-w-44 truncate font-medium">{row.member.fullName ?? "Unnamed member"}</p>
                      <p className="max-w-44 truncate text-[11px] text-muted-foreground">{row.member.email}</p>
                    </div>
                    <ChevronRight className="ml-auto size-4 shrink-0 text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100 group-focus-visible:opacity-100" aria-hidden />
                  </button>
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
      <DeveloperProfileSheet
        row={selectedRow}
        currency={currency}
        onOpenChange={(open) => {
          if (!open) setSelectedUserId(null);
        }}
      />
    </section>
  );
}

function DeveloperProfileSheet({
  row,
  currency,
  onOpenChange,
}: {
  row: TeamRow | null;
  currency: string;
  onOpenChange: (open: boolean) => void;
}) {
  const member = row?.member;
  const profile = row?.profile;
  const loadPct = row?.loadPct ?? null;

  return (
    <Sheet open={row != null} onOpenChange={onOpenChange}>
      <SheetContent className="w-full gap-0 overflow-hidden sm:max-w-lg">
        {row && member ? (
          <>
            <SheetHeader className="border-b p-5 pr-14">
              <div className="flex items-start gap-3">
                <Avatar className="size-12">
                  <AvatarImage src={member.avatarUrl ?? undefined} alt="" />
                  <AvatarFallback>{initials(member.fullName)}</AvatarFallback>
                </Avatar>
                <div className="min-w-0 flex-1">
                  <SheetTitle className="truncate text-lg">
                    {member.fullName ?? "Unnamed member"}
                  </SheetTitle>
                  <SheetDescription className="truncate">{member.email ?? "No email available"}</SheetDescription>
                  <div className="mt-2 flex flex-wrap gap-1.5">
                    <Badge variant="secondary">{enumLabel(member.projectRole)}</Badge>
                    {profile?.seniority ? <Badge variant="outline">{enumLabel(profile.seniority)}</Badge> : null}
                  </div>
                </div>
              </div>
            </SheetHeader>

            <div className="flex-1 overflow-y-auto">
              <section className="p-5" aria-labelledby="developer-profile-overview">
                <h3 id="developer-profile-overview" className="mb-3 text-sm font-semibold">Profile</h3>
                {profile ? (
                  <dl className="grid grid-cols-2 overflow-hidden rounded-md border">
                    <ProfileMetric
                      label="Hourly rate"
                      value={profile.hourlyRate == null ? "Not set" : `${money(profile.hourlyRate, currency)}/h`}
                    />
                    <ProfileMetric label="Experience" value={`${number(profile.yearsExperience)} years`} />
                    <ProfileMetric label="Weekly capacity" value={number(profile.weeklyCapacityHours, "h")} />
                    <ProfileMetric
                      label="Average velocity"
                      value={profile.avgVelocity == null ? "Not measured" : `${number(profile.avgVelocity)} points`}
                    />
                  </dl>
                ) : (
                  <p className="text-sm text-muted-foreground">This developer has not completed their cost profile.</p>
                )}
              </section>

              <section className="border-t p-5" aria-labelledby="developer-current-sprint">
                <h3 id="developer-current-sprint" className="mb-3 text-sm font-semibold">Current sprint</h3>
                <dl className="grid grid-cols-2 overflow-hidden rounded-md border">
                  <ProfileMetric
                    label="Availability"
                    value={profile ? `${number(profile.availabilityRate * 100)}%` : "Not set"}
                  />
                  <ProfileMetric label="Project allocation" value={`${number(member.allocationPct)}%`} />
                  <ProfileMetric label="Effective capacity" value={number(row.capacity?.effectiveHours, "h")} />
                  <ProfileMetric label="Planned absence" value={`${number(row.capacity?.absenceDays)} days`} />
                </dl>
                <div className="mt-4">
                  <div className="flex items-center justify-between gap-3 text-sm">
                    <span className="font-medium">Workload</span>
                    <span className={cn(
                      "tabular-nums",
                      loadPct != null && loadPct > 100 ? "text-destructive" : "text-muted-foreground",
                    )}>
                      {loadPct == null ? "Not available" : `${number(loadPct)}%`}
                    </span>
                  </div>
                  <div className="mt-2 h-2 overflow-hidden rounded-full bg-muted">
                    <div
                      className={cn("h-full rounded-full", loadPct != null && loadPct > 100 ? "bg-destructive" : "bg-primary")}
                      style={{ width: `${Math.min(100, Math.max(0, loadPct ?? 0))}%` }}
                    />
                  </div>
                  <p className="mt-2 text-xs text-muted-foreground">
                    {row.tasks.length} task{row.tasks.length === 1 ? "" : "s"} assigned in this sprint
                  </p>
                </div>
              </section>

              <section className="border-t p-5" aria-labelledby="developer-skills">
                <h3 id="developer-skills" className="mb-3 text-sm font-semibold">Skills</h3>
                {row.skills.length > 0 ? (
                  <div className="flex flex-wrap gap-2">
                    {row.skills.map((skill) => (
                      <Badge key={skill.skillId} variant="outline" className="font-normal">
                        {skill.skillName} · Level {skill.proficiency}
                      </Badge>
                    ))}
                  </div>
                ) : (
                  <p className="text-sm text-muted-foreground">No skills have been added to this profile.</p>
                )}
              </section>

              <section className="border-t p-5" aria-labelledby="developer-sprint-tasks">
                <div className="mb-3 flex items-center justify-between gap-3">
                  <h3 id="developer-sprint-tasks" className="text-sm font-semibold">Assigned tasks</h3>
                  <Badge variant="secondary">{row.tasks.length}</Badge>
                </div>
                {row.tasks.length > 0 ? (
                  <div className="divide-y rounded-md border">
                    {row.tasks.map((task) => (
                      <div key={task.id} className="flex items-start justify-between gap-3 p-3">
                        <div className="min-w-0">
                          <p className="truncate text-sm font-medium">{task.title}</p>
                          <p className="mt-0.5 text-xs text-muted-foreground">
                            {task.itemKey} · {hours(task.estimateMinutes)} planned
                          </p>
                        </div>
                        <Badge className={cn("shrink-0 font-normal", TASK_STATUS_TONE[task.status])}>
                          {enumLabel(task.status)}
                        </Badge>
                      </div>
                    ))}
                  </div>
                ) : (
                  <p className="text-sm text-muted-foreground">No tasks are assigned to this developer in the selected sprint.</p>
                )}
              </section>
            </div>
          </>
        ) : null}
      </SheetContent>
    </Sheet>
  );
}

function ProfileMetric({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0 border-b p-3 odd:border-r [&:nth-last-child(-n+2)]:border-b-0">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="mt-1 truncate font-medium tabular-nums" title={value}>{value}</dd>
    </div>
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
        <h3 id="sprint-cost-tasks" className="font-semibold">
          <LabelWithHelp label="Sprint task costs" />
        </h3>
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
              <LabelWithHelp label={isCancelled ? "Cost record" : "Forecast tracking"} />
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
          <h3 id="cost-validation-summary" className="font-semibold">
            <LabelWithHelp label="Final accuracy validation" />
          </h3>
          <p className="text-xs text-muted-foreground">
            Stored predictions compared with the completed sprint&apos;s final logged labor cost.
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
        <h3 id="cost-validation-pending" className="font-semibold">
          <LabelWithHelp label="Accuracy pending sprint completion" />
        </h3>
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
          <h3 id="cost-validation-details" className="font-semibold">
            <LabelWithHelp label="Prediction accuracy" />
          </h3>
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
