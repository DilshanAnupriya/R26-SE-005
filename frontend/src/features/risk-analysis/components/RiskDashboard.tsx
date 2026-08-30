"use client";

import { useEffect, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { EmptyState } from "@/components/feedback/states";
import { Skeleton } from "@/components/ui/skeleton";
import { workableProjects } from "@/features/projects/api";
import { useProjects } from "@/features/projects/hooks";
import { useSprints } from "@/features/sprints/hooks";
import {
  useAbsorbRiskChange,
  useAssessSprintRisk,
  useDailyRiskTrend,
  useRiskAdjustments,
  useRiskSteps,
  useSprintRequirementChanges,
  useSprintRisk,
  useToggleRiskStep,
} from "../hooks";
import { DailyRiskTrend } from "./daily-risk-trend";
import { RequirementChangesPanel } from "./requirement-changes-panel";
import { SprintCompare } from "./sprint-compare";
import { SprintRiskOverview } from "./sprint-risk-overview";

/**
 * Risk analysis for one sprint, plus the cross-sprint comparison.
 *
 * <p>Scoped to a project and a sprint at the top rather than per tab. Risk is only ever
 * a property of a specific sprint, and a picker that reset every time somebody moved
 * between the gauges and the trend would make comparing the two views needlessly
 * awkward.</p>
 */
export default function RiskDashboard() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  const projects = useProjects();
  const projectList = workableProjects(projects.data?.content ?? []);

  // Seeded from the query, which is how the sprint risk board hands this page a sprint.
  // Read once as initial state rather than tracked: after the first paint the pickers
  // own the selection and the effect below writes it back, so a click in a Select is
  // never in a race with the address bar.
  const [projectId, setProjectId] = useState<string>(() => searchParams.get("projectId") ?? "");
  const [sprintId, setSprintId] = useState<string>(() => searchParams.get("sprintId") ?? "");

  // Land on something useful: the first workable project, and its active sprint if it
  // has one. An empty picker on a page whose whole content depends on it is a dead end.
  useEffect(() => {
    if (!projectId && projectList.length > 0) {
      setProjectId(projectList[0].id);
    }
  }, [projectList, projectId]);

  const sprints = useSprints(projectId || undefined);
  const sprintList = sprints.data?.content ?? [];

  // A project arrived at by link may be finished, and `workableProjects` drops those.
  // It still belongs in its own picker while it is the one being read — otherwise the
  // trigger sits blank above a page full of that project's data.
  const chosen = (projects.data?.content ?? []).find((project) => project.id === projectId);
  const pickerProjects =
    chosen && !projectList.some((project) => project.id === chosen.id)
      ? [chosen, ...projectList]
      : projectList;

  useEffect(() => {
    if (sprintList.length === 0) {
      return;
    }
    if (!sprintId || !sprintList.some((sprint) => sprint.id === sprintId)) {
      const active = sprintList.find((sprint) => sprint.status === "ACTIVE");
      setSprintId((active ?? sprintList[0]).id);
    }
  }, [sprintList, sprintId]);

  // Keep the address bar honest about what is on screen: the view then survives a
  // refresh, and anywhere that knows a sprint can link straight to its analysis.
  useEffect(() => {
    if (!projectId) {
      return;
    }
    const params = new URLSearchParams();
    params.set("projectId", projectId);
    if (sprintId) {
      params.set("sprintId", sprintId);
    }
    const next = `${pathname}?${params.toString()}`;
    // Guarded, because arriving on a link already matching this would otherwise cost a
    // pointless navigation on first paint.
    if (next !== window.location.pathname + window.location.search) {
      router.replace(next, { scroll: false });
    }
  }, [projectId, sprintId, pathname, router]);

  const risk = useSprintRisk(sprintId || undefined);
  const adjustments = useRiskAdjustments(sprintId || undefined);
  const trend = useDailyRiskTrend(sprintId || undefined);
  const changes = useSprintRequirementChanges(sprintId || undefined);
  const assess = useAssessSprintRisk(sprintId || undefined);
  const steps = useRiskSteps(sprintId || undefined);
  const toggleStep = useToggleRiskStep(sprintId || undefined);
  const absorb = useAbsorbRiskChange(sprintId || undefined);

  if (projects.isLoading) {
    return (
      <div className="space-y-3">
        <Skeleton className="h-9 w-72" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }

  if (pickerProjects.length === 0) {
    return (
      <EmptyState
        title="No projects yet"
        description="Risk is assessed per sprint, so there is nothing to read until a project has one."
      />
    );
  }

  return (
    <div className="animate-fade-up space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-[-0.02em]">Risk analysis</h1>
          <p className="mt-1 text-sm text-ink-3">
            Three models per sprint, the trend across its days, and what the board has recorded
            since they last ran.
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <Select value={projectId} onValueChange={setProjectId}>
            <SelectTrigger className="w-[200px]">
              <SelectValue placeholder="Project" />
            </SelectTrigger>
            <SelectContent>
              {pickerProjects.map((project) => (
                <SelectItem key={project.id} value={project.id}>
                  {project.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>

          <Select value={sprintId} onValueChange={setSprintId} disabled={sprintList.length === 0}>
            <SelectTrigger className="w-[220px]">
              <SelectValue placeholder={sprints.isLoading ? "Loading sprints…" : "No sprints"} />
            </SelectTrigger>
            <SelectContent>
              {sprintList.map((sprint) => (
                <SelectItem key={sprint.id} value={sprint.id}>
                  {sprint.name} · {sprint.status}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>

      {sprintList.length === 0 && !sprints.isLoading ? (
        <EmptyState
          title="This project has no sprints"
          description="Risk is scored against a sprint's commitment, dates and board activity. Plan one first."
        />
      ) : (
        <Tabs defaultValue="overview">
          <TabsList>
            <TabsTrigger value="overview">Overview</TabsTrigger>
            <TabsTrigger value="trend">Trend</TabsTrigger>
            <TabsTrigger value="changes">Requirement changes</TabsTrigger>
            <TabsTrigger value="compare">Compare sprints</TabsTrigger>
          </TabsList>

          <TabsContent value="overview" className="mt-4">
            <SprintRiskOverview
              risk={risk.data}
              adjustments={adjustments.data}
              steps={steps.data}
              loading={risk.isPending}
              onAssess={() => assess.mutate()}
              assessing={assess.isPending}
              onToggleStep={toggleStep.mutate}
              togglingStepKey={
                toggleStep.isPending ? (toggleStep.variables?.stepKey ?? null) : null
              }
              onAbsorb={absorb.mutate}
              absorbingId={absorb.isPending ? (absorb.variables?.adjustmentId ?? null) : null}
            />
          </TabsContent>

          <TabsContent value="trend" className="mt-4">
            <DailyRiskTrend points={trend.data ?? []} loading={trend.isPending} />
          </TabsContent>

          <TabsContent value="changes" className="mt-4">
            <RequirementChangesPanel
              projectId={projectId}
              sprintId={sprintId || undefined}
              summary={changes.data}
              loading={changes.isPending}
            />
          </TabsContent>

          <TabsContent value="compare" className="mt-4">
            <SprintCompare sprints={sprintList} />
          </TabsContent>
        </Tabs>
      )}
    </div>
  );
}
