"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useSession } from "@/components/session-provider";
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

export function useCreateStory(projectId: string) {
  const { api } = useSession();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { title: string; description?: string | null }) =>
      backlogApi.createStory(api, { projectId, ...body }),
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
