"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { useSession } from "@/components/session-provider";
import { ApiError } from "@/lib/api/errors";
import { queryKeys } from "@/lib/api/query-keys";
import type { FeedbackAction } from "@/lib/api/ai-schemas";
import { aiApi } from "./api";

/**
 * AI hooks.
 *
 * <p>Suggestions are mutations, not queries, even though they read nothing. Asking a
 * model costs latency and money, so it must happen when a person asks for it — a query
 * would refetch on window focus and quietly bill for a suggestion nobody wanted.</p>
 */

export function useEstimateStory() {
  const { api } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (storyId: string) => aiApi.estimateStory(api, storyId),
    onSuccess: (estimate) => {
      // The suggestion is written to the story's ai* columns server-side, so the story
      // itself is now stale even though its own points did not change.
      void queryClient.invalidateQueries({ queryKey: queryKeys.stories.detail(estimate.storyId) });
    },
    onError: (error) => {
      if (error instanceof ApiError && error.code === "AI_FEATURE_DISABLED") {
        toast.info("Story point suggestions are switched off for this workspace.");
        return;
      }
      toast.error(
        error instanceof ApiError
          ? error.message
          : "The estimator is unavailable. Enter a number yourself.",
      );
    },
  });
}

export function useEstimateBatch() {
  const { api } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (storyIds: string[]) => aiApi.estimateBatch(api, storyIds),
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.backlog.all });
      // Reports partial success honestly: nineteen of twenty is a useful outcome, and
      // silently showing nineteen would leave someone hunting for the twentieth.
      if (result.succeeded < result.requested) {
        toast.warning(
          `Estimated ${result.succeeded} of ${result.requested}. The rest need more detail.`,
        );
      } else {
        toast.success(`Suggested estimates for ${result.succeeded} stories.`);
      }
    },
  });
}

export function useSubmitFeedback() {
  const { api } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (input: {
      predictionId: string;
      action: FeedbackAction;
      finalValue?: number | null;
      reason?: string;
      apply?: boolean;
      storyId?: string;
    }) => aiApi.submitFeedback(api, input.predictionId, input),
    onSuccess: (_feedback, input) => {
      if (input.storyId) {
        void queryClient.invalidateQueries({ queryKey: queryKeys.stories.detail(input.storyId) });
      }
      void queryClient.invalidateQueries({ queryKey: queryKeys.backlog.all });
      void queryClient.invalidateQueries({ queryKey: queryKeys.ai.models() });
    },
  });
}

/**
 * The cost model and the over-budget risk model, read against each other.
 *
 * A pure comparison of two readings that are already stored — no model runs, so this is
 * a plain query and not a mutation like `useForecastCost`. It follows whichever of the
 * two was refreshed last, which is why it invalidates alongside both.
 */
export function useCostRiskCrossCheck(sprintId: string | undefined) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.ai.costRiskCrossCheck(sprintId ?? ""),
    queryFn: () => aiApi.costRiskCrossCheck(api, sprintId!),
    enabled: status === "authenticated" && Boolean(sprintId),
  });
}

export function useSprintRisk(sprintId: string | undefined) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.ai.risk(sprintId ?? ""),
    // Reads the stored assessment. Producing a fresh one is an explicit action.
    queryFn: () => aiApi.latestRisk(api, sprintId!),
    enabled: status === "authenticated" && Boolean(sprintId),
  });
}

/**
 * The ledger behind a sprint's adjusted score — the board activity since it was scored.
 *
 * <p>Read separately from the assessment because it answers a different question. The
 * assessment says how risky the sprint is; this says which specific things are making
 * it so, each naming a task and each withdrawable by fixing that task.</p>
 */
export function useRiskAdjustments(sprintId: string | undefined) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: [...queryKeys.ai.risk(sprintId ?? ""), "adjustments"],
    queryFn: () => aiApi.riskAdjustments(api, sprintId!),
    enabled: status === "authenticated" && Boolean(sprintId),
  });
}

export function useAssessSprintRisk() {
  const { api } = useSession();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (sprintId: string) => aiApi.assessSprintRisk(api, sprintId),
    onSuccess: (_risk, sprintId) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.ai.risk(sprintId) });
      // A re-score is the other half of the cross-check.
      void queryClient.invalidateQueries({
        queryKey: queryKeys.ai.costRiskCrossCheck(sprintId),
      });
    },
  });
}

export function usePredictVelocity() {
  const { api } = useSession();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (sprintId: string) => aiApi.predictVelocity(api, sprintId),
    onSuccess: (_velocity, sprintId) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.ai.velocity(sprintId) });
    },
  });
}

export function useForecastCost() {
  const { api } = useSession();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { sprintId: string; stage: "initial" | "mid-sprint" }) =>
      aiApi.forecastCost(api, input.sprintId, input.stage),
    onSuccess: (_forecast, input) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.ai.cost(input.sprintId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.ai.costHistory(input.sprintId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.ai.costValidation(input.sprintId) });
      // The cross-check reads this forecast, so a new one changes what the panel says.
      void queryClient.invalidateQueries({
        queryKey: queryKeys.ai.costRiskCrossCheck(input.sprintId),
      });
    },
  });
}

export function useRankCandidates() {
  const { api } = useSession();
  return useMutation({
    mutationFn: (input: { entityType: "TASK" | "STORY"; entityId: string; limit?: number }) =>
      aiApi.rankCandidates(api, input.entityType, input.entityId, input.limit),
    onError: (error) => {
      toast.error(
        error instanceof ApiError ? error.message : "Could not rank candidates. Assign manually.",
      );
    },
  });
}

export function useProjectInsights(projectId: string | undefined) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.ai.insights("project", projectId ?? ""),
    queryFn: () => aiApi.projectInsights(api, projectId!),
    enabled: status === "authenticated" && Boolean(projectId),
  });
}

export function useSprintInsights(sprintId: string | undefined) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.ai.insights("sprint", sprintId ?? ""),
    queryFn: () => aiApi.sprintInsights(api, sprintId!),
    enabled: status === "authenticated" && Boolean(sprintId),
  });
}

export function useAiModels(workspaceId?: string) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.ai.models(),
    queryFn: () => aiApi.models(api, workspaceId),
    enabled: status === "authenticated",
  });
}
