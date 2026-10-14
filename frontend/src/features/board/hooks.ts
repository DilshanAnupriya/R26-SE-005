"use client";

import { useCallback, useRef } from "react";
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

        const newStatus = statusForColumn(input.state) as any;

        return {
          ...board,
          columns: board.columns.map((column) => {
            const without = column.tasks.filter((task) => task.id !== input.taskId);
            const isTarget = column.state.id === input.state.id;
            const tasks = isTarget
              ? [...without, { ...moving, workflowStateId: input.state.id, status: newStatus }]
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

    onSettled: (task) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.board(projectId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.overview(projectId) });
      // A move into or out of Blocked charges the sprint's over-budget risk, so the
      // gauges and the ledger behind them have to hear about it — otherwise the risk
      // page shows the pre-drag figure until its cache happens to go stale.
      if (task?.sprintId) {
        void queryClient.invalidateQueries({ queryKey: queryKeys.ai.risk(task.sprintId) });
        void queryClient.invalidateQueries({ queryKey: queryKeys.ai.riskAdjustments(task.sprintId) });
        void queryClient.invalidateQueries({ queryKey: queryKeys.ai.riskTrend(task.sprintId) });
      }
    },
  });
}

/** Adds a card to the board. The new task lands in the project's default column. */
export function useCreateTask(projectId: string) {
  const { api } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (input: {
      title: string;
      description?: string | null;
      sprintId?: string | null;
      storyId?: string | null;
      priority?: string | null;
      assigneeId?: string | null;
      estimateMinutes?: number | null;
    }) => boardApi.createTask(api, { projectId, ...input }),

    onSuccess: (task) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.board(projectId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.overview(projectId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.tasks.workload(projectId) });
      // A task added to a sprint with nobody on it is charged to collaboration at once.
      if (task.sprintId) {
        void queryClient.invalidateQueries({ queryKey: queryKeys.ai.risk(task.sprintId) });
        void queryClient.invalidateQueries({ queryKey: queryKeys.ai.riskAdjustments(task.sprintId) });
      }
      toast.success(`${task.itemKey} added.`);
    },
    onError: (error) => {
      toast.error(error instanceof ApiError ? error.message : "Could not add that task.");
    },
  });
}

/**
 * Sends a finished card back for rework.
 *
 * <p>Not the same as dragging it, though both end in the same column: this says why,
 * and the sprint's collaboration risk reads the reopen count. The board is refetched
 * rather than patched optimistically — the server decides where a reopened card lands
 * and restarts its clock, and guessing either would show a number that changes under
 * the cursor a moment later.</p>
 */
export function useReopenTask(projectId: string) {
  const { api } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (input: { taskId: string; reason?: string }) =>
      boardApi.reopen(api, input.taskId, { reason: input.reason }),

    onSuccess: (task) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.board(projectId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.tasks.detail(task.id) });
      if (task.sprintId) {
        void queryClient.invalidateQueries({ queryKey: queryKeys.ai.risk(task.sprintId) });
      }
      toast.success(`${task.itemKey} reopened.`);
    },
    onError: (error) => {
      toast.error(error instanceof ApiError ? error.message : "Could not reopen that task.");
    },
  });
}

/**
 * Counts a reopen for a card that has been opened for a look.
 *
 * <p>Fired when the detail dialog opens, not on every render of it — the count is what
 * the sprint's over-budget and collaboration risk read, so a component that re-rendered
 * twice would charge the sprint twice for one look.</p>
 *
 * <p>The board is patched in place with the new count instead of refetched. Nothing
 * about the card has moved, so a full board refetch would be a round trip to change one
 * badge, and the dialog sitting on top of it would flicker as its task was replaced.</p>
 */
export function useCountReopen(projectId: string) {
  const { api } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (input: { taskId: string }) => boardApi.countReopen(api, input.taskId),

    onSuccess: (task) => {
      queryClient.setQueriesData<TaskBoard>(
        { queryKey: queryKeys.projects.board(projectId) },
        (board) =>
          board
            ? {
                ...board,
                columns: board.columns.map((column) => ({
                  ...column,
                  tasks: column.tasks.map((existing) =>
                    existing.id === task.id
                      ? { ...existing, reopenedCount: task.reopenedCount }
                      : existing,
                  ),
                })),
              }
            : board,
      );
      void queryClient.invalidateQueries({ queryKey: queryKeys.tasks.detail(task.id) });
      if (task.sprintId) {
        void queryClient.invalidateQueries({ queryKey: queryKeys.ai.risk(task.sprintId) });
      }
    },

    onError: (error) => {
      // Loud rather than silent: the count is visible on the card the user just opened,
      // and a badge that quietly failed to move would read as the feature not working.
      toast.error(
        error instanceof ApiError ? error.message : "Could not count that reopen.",
      );
    },
  });
}

/**
 * Reports an expired countdown, at most once per task per session.
 *
 * <p>The server is idempotent per task, so a duplicate report is harmless rather than a
 * double charge — but the local guard means a board with forty overdue cards does not
 * fire forty POSTs on every mount, which it otherwise would.</p>
 */
export function useReportOverdue() {
  const { api } = useSession();
  const queryClient = useQueryClient();
  const reported = useRef(new Set<string>());

  return useCallback(
    (taskId: string, sprintId?: string | null) => {
      if (reported.current.has(taskId)) return;
      reported.current.add(taskId);
      boardApi
        .reportOverdue(api, taskId)
        .then(() => {
          if (sprintId) {
            void queryClient.invalidateQueries({ queryKey: queryKeys.ai.risk(sprintId) });
          }
        })
        .catch(() => {
          // Silent. The countdown on the card is the user-visible signal; a toast for a
          // background bookkeeping call would interrupt work to report nothing they can
          // act on. Dropped from the set so the next tick can retry.
          reported.current.delete(taskId);
        });
    },
    [api, queryClient],
  );
}

/**
 * Reports a comment nobody has answered, at most once per task per session.
 *
 * <p>Same shape as {@link useReportOverdue} and for the same reasons: the board is the
 * only thing holding both the estimate and the age of the oldest unanswered comment, the
 * server is idempotent per task, and the local guard stops a board of forty stale threads
 * firing forty POSTs on every mount.</p>
 */
export function useReportUnansweredComment() {
  const { api } = useSession();
  const queryClient = useQueryClient();
  const reported = useRef(new Set<string>());

  return useCallback(
    (taskId: string, sprintId?: string | null) => {
      if (reported.current.has(taskId)) return;
      reported.current.add(taskId);
      boardApi
        .reportUnansweredComment(api, taskId)
        .then(() => {
          if (sprintId) {
            void queryClient.invalidateQueries({ queryKey: queryKeys.ai.risk(sprintId) });
          }
        })
        .catch(() => {
          // Silent, like the overdue report. The waiting badge on the card is the
          // user-visible signal; a toast about a background bookkeeping call would
          // interrupt work to report something nobody can act on.
          reported.current.delete(taskId);
        });
    },
    [api, queryClient],
  );
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
      // Ownership moves collaboration risk: an owner gives back part of the unassigned
      // charge, and removing one puts it all back.
      if (task.sprintId) {
        void queryClient.invalidateQueries({ queryKey: queryKeys.ai.risk(task.sprintId) });
        void queryClient.invalidateQueries({ queryKey: queryKeys.ai.riskAdjustments(task.sprintId) });
      }
      toast.success(
        task.assigneeName ? `Assigned to ${task.assigneeName}.` : "Unassigned.",
      );
    },
    onError: (error) => {
      toast.error(error instanceof ApiError ? error.message : "Could not assign that task.");
    },
  });
}
