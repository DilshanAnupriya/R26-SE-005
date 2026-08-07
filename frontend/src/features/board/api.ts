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
};
