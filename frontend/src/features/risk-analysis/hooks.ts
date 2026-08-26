"use client";

import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { useSession } from "@/components/session-provider";
import { ApiError } from "@/lib/api/errors";
import type { RiskType } from "@/lib/api/ai-schemas";
import { queryKeys } from "@/lib/api/query-keys";
import { riskApi, type ImpactDirection, type RequirementChangeType } from "./api";

export function useSprintRisk(sprintId: string | undefined) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.ai.risk(sprintId ?? ""),
    queryFn: () => riskApi.latest(api, sprintId!),
    enabled: status === "authenticated" && Boolean(sprintId),
  });
}

/**
 * Re-scores the sprint.
 *
 * <p>Invalidates the trend and the adjustment ledger as well as the gauges: a fresh
 * scoring folds the board's own readings into the model's figure, so a ledger left on
 * screen would still be claiming credit for points now counted twice.</p>
 *
 * <p>The steps go with them, and that one was a bug rather than housekeeping. A
 * re-scoring settles every ticked step against the new assessment — re-pricing what is
 * still on offer, retiring what the model has since read for itself — so a step list
 * left on the old cache showed ticks and prices belonging to the previous scoring,
 * beside a gauge that had already moved past them.</p>
 */
export function useAssessSprintRisk(sprintId: string | undefined) {
  const { api } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: () => riskApi.assess(api, sprintId!),
    onSuccess: (result) => {
      queryClient.setQueryData(queryKeys.ai.risk(result.sprintId), result);
      void queryClient.invalidateQueries({ queryKey: queryKeys.ai.riskTrend(result.sprintId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.ai.riskAdjustments(result.sprintId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.ai.riskSteps(result.sprintId) });
      toast.success("Sprint reassessed.");
    },
    onError: (error) => {
      toast.error(error instanceof ApiError ? error.message : "Could not assess that sprint.");
    },
  });
}

export function useDailyRiskTrend(sprintId: string | undefined) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.ai.riskTrend(sprintId ?? ""),
    queryFn: () => riskApi.dailyTrend(api, sprintId!),
    enabled: status === "authenticated" && Boolean(sprintId),
  });
}

export function useRiskAdjustments(sprintId: string | undefined) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.ai.riskAdjustments(sprintId ?? ""),
    queryFn: () => riskApi.adjustments(api, sprintId!),
    enabled: status === "authenticated" && Boolean(sprintId),
  });
}

export function useRiskSteps(sprintId: string | undefined) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.ai.riskSteps(sprintId ?? ""),
    queryFn: () => riskApi.steps(api, sprintId!),
    enabled: status === "authenticated" && Boolean(sprintId),
  });
}

/**
 * Ticks a remediation step, or unticks it.
 *
 * <p>The gauges, the ledger and the trend are all invalidated because completing a step
 * writes a negative entry to the same ledger a blocked task writes to — the number moves
 * straight away, and leaving any of the three stale would have the screen disagreeing
 * with itself about a change the user just made.</p>
 *
 * <p>The credit is not spent by the next assessment. Re-scoring settles it instead: the
 * points are re-priced while the model still offers the step, and released once it stops
 * offering it — which is what a reading that has actually moved looks like, and the
 * point at which the fall is in the model’s own score rather than in this overlay.</p>
 *
 * <p>The response is the whole step list, so it is written into the cache rather than
 * invalidated: the checkbox has to settle immediately, and a refetch round trip is long
 * enough to look like the tick did not register.</p>
 */
export function useToggleRiskStep(sprintId: string | undefined) {
  const { api } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (input: { riskType: RiskType; stepKey: string; done: boolean }) =>
      riskApi.toggleStep(api, sprintId!, input),

    onSuccess: (steps, input) => {
      if (!sprintId) return;
      queryClient.setQueryData(queryKeys.ai.riskSteps(sprintId), steps);
      void queryClient.invalidateQueries({ queryKey: queryKeys.ai.risk(sprintId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.ai.riskAdjustments(sprintId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.ai.riskTrend(sprintId) });

      const step = steps.find((candidate) => candidate.stepKey === input.stepKey);
      const points = step ? Math.round(step.points) : null;
      toast.success(
        input.done
          ? points == null
            ? "Step marked done."
            : `Step done — risk ${points} pts lower, and it stays off across assessments.`
          : "Step reopened — its credit was withdrawn.",
      );
    },

    onError: (error) => {
      toast.error(error instanceof ApiError ? error.message : "Could not update that step.");
    },
  });
}

/**
 * Two to five sprints side by side.
 *
 * <p>Ids are sorted into the key so picking the same pair in either order reuses one
 * cache entry, and the query stays disabled below two — the endpoint rejects a
 * one-sprint comparison and there is nothing useful to show while somebody is still
 * choosing.</p>
 */
export function useSprintRiskComparison(sprintIds: string[]) {
  const { api, status } = useSession();
  const sorted = [...new Set(sprintIds)].sort();
  return useQuery({
    queryKey: queryKeys.ai.riskCompare(sorted),
    queryFn: () => riskApi.compare(api, sorted),
    enabled: status === "authenticated" && sorted.length >= 2,
  });
}

// --------------------------------------------------------- requirement changes

export function useRequirementChangeTypes() {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.requirementChanges.types(),
    queryFn: () => riskApi.requirementChangeTypes(api),
    enabled: status === "authenticated",
    // The weights are a deployment constant. Refetching them per page view would be one
    // request per navigation to learn something that cannot have changed.
    staleTime: Infinity,
  });
}

export function useSprintRequirementChanges(sprintId: string | undefined) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.requirementChanges.forSprint(sprintId ?? ""),
    queryFn: () => riskApi.sprintRequirementChanges(api, sprintId!),
    enabled: status === "authenticated" && Boolean(sprintId),
  });
}

export function useProjectRequirementChanges(projectId: string | undefined) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.requirementChanges.forProject(projectId ?? ""),
    queryFn: () => riskApi.projectRequirementChanges(api, projectId!),
    enabled: status === "authenticated" && Boolean(projectId),
  });
}

/**
 * Logs a change and refreshes everything it moved.
 *
 * <p>The risk gauges are invalidated alongside the register because logging a change
 * charges the in-flight sprint immediately. Leaving them stale would mean the whole
 * point of the form — watching the number respond — required a manual reload.</p>
 */
export function useLogRequirementChange(projectId: string) {
  const { api } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (input: {
      changeType: RequirementChangeType;
      impactDirection?: ImpactDirection;
      sprintId?: string | null;
      requirementId?: string | null;
      storyId?: string | null;
      description?: string | null;
      requestedByName?: string | null;
    }) => riskApi.logRequirementChange(api, projectId, input),

    onSuccess: (change) => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.requirementChanges.forProject(projectId),
      });
      if (change.sprintId) {
        void queryClient.invalidateQueries({
          queryKey: queryKeys.requirementChanges.forSprint(change.sprintId),
        });
        void queryClient.invalidateQueries({ queryKey: queryKeys.ai.risk(change.sprintId) });
        void queryClient.invalidateQueries({
          queryKey: queryKeys.ai.riskAdjustments(change.sprintId),
        });
        void queryClient.invalidateQueries({ queryKey: queryKeys.ai.riskTrend(change.sprintId) });
      }
      const sign = change.riskDelta >= 0 ? "+" : "";
      toast.success(`Change logged — requirement risk ${sign}${change.riskDelta} pts`);
    },
    onError: (error) => {
      toast.error(error instanceof ApiError ? error.message : "Could not log that change.");
    },
  });
}

export function useDeleteRequirementChange(projectId: string) {
  const { api } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (input: { changeId: string; sprintId?: string | null }) =>
      riskApi.deleteRequirementChange(api, input.changeId),
    onSuccess: (_result, input) => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.requirementChanges.forProject(projectId),
      });
      if (input.sprintId) {
        void queryClient.invalidateQueries({
          queryKey: queryKeys.requirementChanges.forSprint(input.sprintId),
        });
        void queryClient.invalidateQueries({ queryKey: queryKeys.ai.risk(input.sprintId) });
        void queryClient.invalidateQueries({
          queryKey: queryKeys.ai.riskAdjustments(input.sprintId),
        });
      }
      toast.success("Change withdrawn — its risk charge was reversed.");
    },
    onError: (error) => {
      toast.error(error instanceof ApiError ? error.message : "Could not withdraw that change.");
    },
  });
}

/**
 * Requirement-change registers for several sprints at once, for the comparison view.
 *
 * <p>`useQueries` rather than a loop of `useQuery`, because the number of sprints being
 * compared changes as the user picks them and a variable number of hook calls is not
 * something React permits.</p>
 */
export function useRequirementChangesFor(sprintIds: string[]) {
  const { api, status } = useSession();
  return useQueries({
    queries: sprintIds.map((sprintId) => ({
      queryKey: queryKeys.requirementChanges.forSprint(sprintId),
      queryFn: () => riskApi.sprintRequirementChanges(api, sprintId),
      enabled: status === "authenticated",
    })),
  });
}
