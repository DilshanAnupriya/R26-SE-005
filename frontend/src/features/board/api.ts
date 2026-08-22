import { z } from "zod";
import type { ApiClient } from "@/lib/api/client";
import { taskSchema, workflowStateSchema, workloadEntrySchema } from "@/lib/api/delivery-schemas";

/**
 * The column shape the backend actually sends: the workflow state nested whole, rather
 * than flattened into the column. Mirroring it exactly — instead of reshaping here — is
 * what lets the schema catch a contract change loudly at the boundary.
 */
const taskBoardColumnSchema = z.object({
  state: workflowStateSchema,
  tasks: z.array(taskSchema).default([]),
  itemCount: z.number().int(),
  wipLimitExceeded: z.boolean(),
});
export type TaskBoardColumn = z.infer<typeof taskBoardColumnSchema>;

export const taskBoardSchema = z.object({
  projectId: z.uuid(),
  columns: z.array(taskBoardColumnSchema).default([]),
  totalItems: z.number().int(),
});
export type TaskBoard = z.infer<typeof taskBoardSchema>;

/** Task statuses the API accepts. */
const TASK_STATUSES = new Set([
  "TODO",
  "IN_PROGRESS",
  "IN_REVIEW",
  "BLOCKED",
  "DONE",
  "CANCELLED",
]);

/**
 * The status a column implies.
 *
 * <p>A column's `code` is preferred when it names a real status — that is what makes the
 * default IN_REVIEW column set IN_REVIEW rather than the coarser IN_PROGRESS its category
 * would give. A custom column falls back to its category, which is always one of the four
 * the backend guarantees.</p>
 */
export function statusForColumn(state: { code: string; category: string }): string {
  const code = state.code.toUpperCase();
  return TASK_STATUSES.has(code) ? code : state.category;
}

export const boardApi = {
  board: (api: ApiClient, projectId: string, sprintId?: string, assigneeId?: string) =>
    api.request(`/projects/${projectId}/board`, {
      schema: taskBoardSchema,
      query: { sprintId, assigneeId },
    }),

  workload: (api: ApiClient, projectId: string) =>
    api.request(`/projects/${projectId}/workload`, {
      schema: z.array(workloadEntrySchema),
    }),

  /**
   * Moves a task to a different column.
   *
   * <p>Both the workflow state and the status are sent, because the endpoint requires
   * the status and the two are not interchangeable: a project can have several columns
   * in the same category, and only the state says which one the card landed in.</p>
   */
  changeStatus: (api: ApiClient, taskId: string, workflowStateId: string, status: string) =>
    api.request(`/tasks/${taskId}/status`, {
      method: "PATCH",
      body: { workflowStateId, status },
      schema: taskSchema,
    }),

  assign: (api: ApiClient, taskId: string, assigneeId: string | null, predictionId?: string | null) =>
    api.request(`/tasks/${taskId}/assign`, {
      method: "PATCH",
      body: {
        assigneeId,
        predictionId,
        // Recorded so the M3 suggestion table can tell an accepted recommendation from
        // a coincidence — the model's ground truth depends on this flag being honest.
        source: predictionId ? "AI_SUGGESTED" : "MANUAL",
      },
      schema: taskSchema,
    }),

  get: (api: ApiClient, taskId: string) =>
    api.request(`/tasks/${taskId}`, { schema: taskSchema }),

  /**
   * Creates a task straight onto the board.
   *
   * <p>No story is required. The board is where work gets noticed, and forcing every
   * card through the backlog first is how "just add a task for it" turns into a
   * five-click detour that people skip — leaving the work untracked, which is worse for
   * every downstream number than an unparented task.</p>
   */
  createTask: (
    api: ApiClient,
    body: {
      projectId: string;
      title: string;
      description?: string | null;
      storyId?: string | null;
      sprintId?: string | null;
      priority?: string | null;
      assigneeId?: string | null;
      estimateMinutes?: number | null;
      dueDate?: string | null;
    },
  ) => api.request("/tasks", { method: "POST", body, schema: taskSchema }),

  /** Sends a finished task back for rework: counts the reopen, resumes the work clock. */
  reopen: (api: ApiClient, taskId: string, body?: { status?: string; reason?: string }) =>
    api.request(`/tasks/${taskId}/reopen`, {
      method: "POST",
      body: body ?? {},
      schema: taskSchema,
    }),

  /**
   * Reports that a card's countdown has hit zero while the task is still open.
   *
   * <p>Reported from here rather than swept for on the server: the board is already
   * ticking every second to draw the countdown, so it knows the instant it crosses,
   * whereas a scheduler would run for every sprint in every tenant to catch the handful
   * that matter and still be up to a minute late. The endpoint is idempotent per task,
   * so a dozen open tabs cost the sprint exactly what one does.</p>
   */
  reportOverdue: (api: ApiClient, taskId: string) =>
    api.request(`/tasks/${taskId}/overdue`, { method: "POST", schema: taskSchema }),
};
