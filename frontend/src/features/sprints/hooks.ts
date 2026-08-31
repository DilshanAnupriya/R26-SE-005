"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { useSession } from "@/components/session-provider";
import { ApiError } from "@/lib/api/errors";
import { queryKeys } from "@/lib/api/query-keys";
import { sprintsApi } from "./api";

export function useSprints(projectId: string | undefined, status?: string) {
  const { api, status: sessionStatus } = useSession();
  return useQuery({
    queryKey: [...queryKeys.sprints.list(projectId ?? ""), status ?? null],
    queryFn: () => sprintsApi.list(api, projectId!, status),
    enabled: sessionStatus === "authenticated" && Boolean(projectId),
  });
}

export function useSprint(sprintId: string | undefined) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.sprints.detail(sprintId ?? ""),
    queryFn: () => sprintsApi.get(api, sprintId!),
    enabled: status === "authenticated" && Boolean(sprintId),
  });
}

export function useSprintBoard(sprintId: string | undefined) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.sprints.board(sprintId ?? ""),
    queryFn: () => sprintsApi.board(api, sprintId!),
    enabled: status === "authenticated" && Boolean(sprintId),
    // A board is the screen people leave open all day. The WebSocket pushes changes,
    // so this is a safety net rather than the primary path.
    staleTime: 15_000,
  });
}

export function useBurndown(sprintId: string | undefined) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.sprints.burndown(sprintId ?? ""),
    queryFn: () => sprintsApi.burndown(api, sprintId!),
    enabled: status === "authenticated" && Boolean(sprintId),
  });
}

export function useSprintCapacity(sprintId: string | undefined) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.sprints.capacity(sprintId ?? ""),
    queryFn: () => sprintsApi.capacity(api, sprintId!),
    enabled: status === "authenticated" && Boolean(sprintId),
  });
}

export function useRetrospective(sprintId: string | undefined) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: [...queryKeys.sprints.detail(sprintId ?? ""), "retrospective"],
    queryFn: () => sprintsApi.retrospective(api, sprintId!),
    enabled: status === "authenticated" && Boolean(sprintId),
  });
}

/** Invalidates everything a sprint's state can change: board, burndown, backlog, overview. */
function useSprintInvalidation(projectId?: string) {
  const queryClient = useQueryClient();
  return (sprintId: string) => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.sprints.all });
    void queryClient.invalidateQueries({ queryKey: queryKeys.backlog.all });
    void queryClient.invalidateQueries({ queryKey: queryKeys.ai.risk(sprintId) });
    if (projectId) {
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.overview(projectId) });
    }
  };
}

export function useCreateSprint(projectId: string) {
  const { api } = useSession();
  const invalidate = useSprintInvalidation(projectId);
  return useMutation({
    mutationFn: (body: Omit<Parameters<typeof sprintsApi.create>[1], "projectId">) =>
      sprintsApi.create(api, { projectId, ...body }),
    onSuccess: (sprint) => invalidate(sprint.id),
  });
}

export function useUpdateSprintBudget(projectId: string) {
  const { api } = useSession();
  const invalidate = useSprintInvalidation(projectId);
  return useMutation({
    mutationFn: (input: { sprintId: string; budgetAmount: number }) =>
      sprintsApi.update(api, input.sprintId, { budgetAmount: input.budgetAmount }),
    onSuccess: (sprint) => {
      invalidate(sprint.id);
      toast.success(`${sprint.name} budget updated.`);
    },
    onError: (error) => {
      toast.error(error instanceof ApiError ? error.message : "Could not update the sprint budget.");
    },
  });
}

export function useStartSprint(projectId: string) {
  const { api } = useSession();
  const invalidate = useSprintInvalidation(projectId);
  return useMutation({
    mutationFn: (sprintId: string) => sprintsApi.start(api, sprintId),
    onSuccess: (sprint) => {
      invalidate(sprint.id);
      toast.success(
        `${sprint.name} started. ${sprint.committedPoints} points are now the frozen commitment.`,
      );
    },
    onError: (error) => {
      toast.error(
        error instanceof ApiError ? error.message : "Could not start the sprint.",
      );
    },
  });
}

export function useCompleteSprint(projectId: string) {
  const { api } = useSession();
  const invalidate = useSprintInvalidation(projectId);
  return useMutation({
    mutationFn: (input: {
      sprintId: string;
      spilloverStrategy: "BACKLOG" | "NEXT_SPRINT" | "TARGET_SPRINT";
      targetSprintId?: string | null;
      retrospectiveSummary?: string | null;
    }) => sprintsApi.complete(api, input.sprintId, input),
    onSuccess: (result, input) => {
      invalidate(input.sprintId);
      const spilled = result.spilledCount ?? 0;
      toast.success(
        spilled > 0
          ? `Sprint closed. ${result.completedPoints ?? 0} points delivered, ${spilled} item${spilled === 1 ? "" : "s"} moved on.`
          : `Sprint closed. ${result.completedPoints ?? 0} points delivered, nothing left over.`,
      );
    },
    onError: (error) => {
      toast.error(error instanceof ApiError ? error.message : "Could not close the sprint.");
    },
    onSettled: (_data, _error, input) => {
      invalidate(input.sprintId);
    },
  });
}

export function useAddSprintItems(projectId: string) {
  const { api } = useSession();
  const invalidate = useSprintInvalidation(projectId);
  return useMutation({
    mutationFn: (input: { sprintId: string; storyIds: string[] }) =>
      sprintsApi.addItems(api, input.sprintId, input.storyIds),
    onSuccess: (_result, input) => invalidate(input.sprintId),
    onError: (error) => {
      toast.error(error instanceof ApiError ? error.message : "Could not commit those items.");
    },
  });
}

export function useRemoveSprintItem(projectId: string) {
  const { api } = useSession();
  const invalidate = useSprintInvalidation(projectId);
  return useMutation({
    mutationFn: (input: { sprintId: string; storyId: string }) =>
      sprintsApi.removeItem(api, input.sprintId, input.storyId),
    onSuccess: (_result, input) => invalidate(input.sprintId),
    onError: (error) => {
      // The backend refuses to pull started work out of a sprint — that erases the
      // commitment it was measured against. The message explains it; surface it as-is.
      toast.error(error instanceof ApiError ? error.message : "Could not remove that item.");
    },
  });
}

export function useSaveRetrospective(sprintId: string) {
  const { api } = useSession();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: Parameters<typeof sprintsApi.saveRetrospective>[2]) =>
      sprintsApi.saveRetrospective(api, sprintId, body),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: [...queryKeys.sprints.detail(sprintId), "retrospective"],
      });
      toast.success("Retrospective saved.");
    },
  });
}
