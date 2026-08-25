"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { useSession } from "@/components/session-provider";
import { ApiError } from "@/lib/api/errors";
import { queryKeys } from "@/lib/api/query-keys";
import { backlogApi } from "./api";

export function useBacklog(
  projectId: string | undefined,
  filters: Record<string, string | number | undefined> = {},
) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.backlog.list(projectId ?? "", filters),
    queryFn: () => backlogApi.list(api, projectId!, filters),
    enabled: status === "authenticated" && Boolean(projectId),
  });
}

export function useStory(storyId: string | undefined) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.stories.detail(storyId ?? ""),
    queryFn: () => backlogApi.story(api, storyId!),
    enabled: status === "authenticated" && Boolean(storyId),
  });
}

export function useEpics(projectId: string | undefined) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: [...queryKeys.backlog.all, "epics", projectId ?? ""],
    queryFn: () => backlogApi.epics(api, projectId!),
    enabled: status === "authenticated" && Boolean(projectId),
  });
}

export function useCreateStory(projectId: string) {
  const { api } = useSession();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: Omit<Parameters<typeof backlogApi.createStory>[1], "projectId">) =>
      backlogApi.createStory(api, { projectId, ...body }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.backlog.all });
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.overview(projectId) });
    },
  });
}

export function useCreateEpic(projectId: string) {
  const { api } = useSession();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { title: string; description?: string | null }) =>
      backlogApi.createEpic(api, { projectId, ...body }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.backlog.all });
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.overview(projectId) });
    },
  });
}

/**
 * Applies an estimate a person typed.
 *
 * <p>Distinct from accepting an AI suggestion, which goes through the feedback endpoint
 * so the verdict lands in `ai_feedback`. Routing a manual estimate through that path
 * would record agreement with a model nobody consulted.</p>
 */
export function useEstimateStory(projectId: string) {
  const { api } = useSession();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { storyId: string; storyPoints: number }) =>
      backlogApi.estimate(api, input.storyId, {
        storyPoints: input.storyPoints,
        source: "MANUAL",
      }),
    onSuccess: (story) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.stories.detail(story.id) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.backlog.all });
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.overview(projectId) });
    },
  });
}

export function useChangeStoryStatus(projectId: string) {
  const { api } = useSession();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { storyId: string; status: string }) =>
      backlogApi.changeStatus(api, input.storyId, input.status),
    onSuccess: (story) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.stories.detail(story.id) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.backlog.all });
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.board(projectId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.overview(projectId) });
    },
  });
}

/**
 * Edits one field of a story and logs it in the requirement-change register.
 *
 * <p>Invalidates the risk views alongside the backlog, because the point of logging a
 * change here is watching the sprint's requirement-change score respond. Leaving the
 * gauges stale would make the whole feature require a manual reload to be believed.</p>
 *
 * <p>The toast quotes the delta rather than a generic success, and the sign is the
 * server's answer, not the form's — a change the user thought was a simplification and
 * the register scored as an expansion has to say so at the moment it happens.</p>
 */
export function useApplyRequirementChange(storyId: string | undefined) {
  const { api } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (input: Parameters<typeof backlogApi.applyRequirementChange>[2]) =>
      backlogApi.applyRequirementChange(api, storyId!, input),

    onSuccess: (result) => {
      const { story, change } = result;
      queryClient.setQueryData(queryKeys.stories.detail(story.id), story);
      void queryClient.invalidateQueries({ queryKey: queryKeys.backlog.all });
      void queryClient.invalidateQueries({ queryKey: ["timeline"] });
      void queryClient.invalidateQueries({
        queryKey: queryKeys.requirementChanges.forProject(story.projectId),
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
      toast.success(
        change.sprintId
          ? `${story.itemKey} updated — requirement risk ${sign}${change.riskDelta} pts`
          : `${story.itemKey} updated — logged outside a sprint, so no sprint was charged`,
      );
    },

    onError: (error) => {
      toast.error(error instanceof ApiError ? error.message : "Could not log that change.");
    },
  });
}
