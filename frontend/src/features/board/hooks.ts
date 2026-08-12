"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { useSession } from "@/components/session-provider";
import { ApiError } from "@/lib/api/errors";
import { queryKeys } from "@/lib/api/query-keys";
import { boardApi, statusForColumn, type TaskBoard } from "./api";

export function useTaskBoard(projectId: string | undefined, sprintId?: string, assigneeId?: string) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: [...queryKeys.projects.board(projectId ?? ""), sprintId ?? null, assigneeId ?? null],
    queryFn: () => boardApi.board(api, projectId!, sprintId, assigneeId),
    enabled: status === "authenticated" && Boolean(projectId),
    staleTime: 15_000,
  });
}

export function useWorkload(projectId: string | undefined) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.tasks.workload(projectId ?? ""),
    queryFn: () => boardApi.workload(api, projectId!),
    enabled: status === "authenticated" && Boolean(projectId),
  });
}

/**
 * Moves a card, optimistically.
 *
 * <p>Drag-and-drop is the one interaction where a round-trip before the card moves is
 * unacceptable — the card would spring back under the cursor. The optimistic update
 * writes into the cached board, and `onError` restores the exact snapshot rather than
 * refetching, so a failed move puts the card back where it was rather than wherever the
 * server happens to be a moment later.</p>
 */
export function useMoveTask(projectId: string) {
  const { api } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (input: {
      taskId: string;
      state: { id: string; code: string; category: string };
    }) => boardApi.changeStatus(api, input.taskId, input.state.id, statusForColumn(input.state)),

    onMutate: async (input) => {
      const prefix = queryKeys.projects.board(projectId);
      await queryClient.cancelQueries({ queryKey: prefix });

      const snapshots = queryClient.getQueriesData<TaskBoard>({ queryKey: prefix });

      queryClient.setQueriesData<TaskBoard>({ queryKey: prefix }, (board) => {
        if (!board) return board;

        const moving = board.columns
          .flatMap((column) => column.tasks)
          .find((task) => task.id === input.taskId);
        if (!moving) return board;

        return {
          ...board,
          columns: board.columns.map((column) => {
            const without = column.tasks.filter((task) => task.id !== input.taskId);
            const isTarget = column.state.id === input.state.id;
            const tasks = isTarget
              ? [...without, { ...moving, workflowStateId: input.state.id }]
              : without;
            // itemCount drives the WIP badge, so it has to move with the card —
            // otherwise a drag that breaks a limit shows the old count until refetch.
            return { ...column, tasks, itemCount: tasks.length };
          }),
        };
      });

      return { snapshots };
    },

    onError: (error, _input, context) => {
      context?.snapshots.forEach(([key, board]) => queryClient.setQueryData(key, board));
      toast.error(
        error instanceof ApiError ? error.message : "Could not move that card.",
      );
    },

    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.board(projectId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.overview(projectId) });
    },
  });
}

export function useAssignTask(projectId: string) {
  const { api } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (input: { taskId: string; assigneeId: string | null; predictionId?: string | null }) =>
      boardApi.assign(api, input.taskId, input.assigneeId, input.predictionId),
    onSuccess: (task) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.board(projectId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.tasks.detail(task.id) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.tasks.workload(projectId) });
      toast.success(
        task.assigneeName ? `Assigned to ${task.assigneeName}.` : "Unassigned.",
      );
    },
    onError: (error) => {
      toast.error(error instanceof ApiError ? error.message : "Could not assign that task.");
    },
  });
}
