import { z } from "zod";
import type { ApiClient } from "@/lib/api/client";
import { pageResponseSchema } from "@/lib/api/schemas";
import { burndownSchema, sprintBoardSchema, sprintSchema } from "@/lib/api/delivery-schemas";

const capacityMemberSchema = z.object({
  userId: z.uuid(),
  fullName: z.string().nullish(),
  avatarUrl: z.string().nullish(),
  availableHours: z.coerce.number().nullish(),
  absenceDays: z.coerce.number().nullish(),
  effectiveHours: z.coerce.number().nullish(),
  committedPoints: z.coerce.number().nullish(),
});

export const sprintCapacitySchema = z.object({
  sprintId: z.uuid(),
  totalAvailableHours: z.coerce.number().nullish(),
  totalEffectiveHours: z.coerce.number().nullish(),
  capacityPoints: z.coerce.number().nullish(),
  plannedPoints: z.coerce.number().nullish(),
  utilisationPct: z.coerce.number().nullish(),
  members: z.array(capacityMemberSchema).default([]),
});
export type SprintCapacity = z.infer<typeof sprintCapacitySchema>;

export const retrospectiveSchema = z.object({
  id: z.uuid().nullish(),
  sprintId: z.uuid(),
  wentWell: z.array(z.string()).default([]),
  wentWrong: z.array(z.string()).default([]),
  actionItems: z.array(z.record(z.string(), z.unknown())).default([]),
  summary: z.string().nullish(),
  facilitatorId: z.uuid().nullish(),
});
export type Retrospective = z.infer<typeof retrospectiveSchema>;

export const sprintsApi = {
  list: (api: ApiClient, projectId: string, status?: string) =>
    api.request("/sprints", {
      schema: pageResponseSchema(sprintSchema),
      query: { projectId, status, size: 50, sort: "sequenceNo,desc" },
    }),

  get: (api: ApiClient, sprintId: string) =>
    api.request(`/sprints/${sprintId}`, { schema: sprintSchema }),

  board: (api: ApiClient, sprintId: string) =>
    api.request(`/sprints/${sprintId}/board`, { schema: sprintBoardSchema }),

  burndown: (api: ApiClient, sprintId: string) =>
    api.request(`/sprints/${sprintId}/burndown`, { schema: burndownSchema }),

  capacity: (api: ApiClient, sprintId: string) =>
    api.request(`/sprints/${sprintId}/capacity`, { schema: sprintCapacitySchema }),

  create: (
    api: ApiClient,
    body: {
      projectId: string;
      name?: string | null;
      goal?: string | null;
      startDate?: string | null;
      endDate?: string | null;
      capacityPoints?: number | null;
      capacityHours?: number | null;
      budgetAmount?: number | null;
    },
  ) => api.request("/sprints", { method: "POST", body, schema: sprintSchema }),

  update: (
    api: ApiClient,
    sprintId: string,
    body: {
      budgetAmount?: number;
    },
  ) => api.request(`/sprints/${sprintId}`, { method: "PATCH", body, schema: sprintSchema }),

  /** Freezes the commitment. Everything added afterwards is recorded as scope creep. */
  start: (api: ApiClient, sprintId: string) =>
    api.request(`/sprints/${sprintId}/start`, { method: "POST", schema: sprintSchema }),

  /**
   * Closes the sprint and routes what did not finish.
   *
   * <p>The strategy is required rather than defaulted: leaving unfinished work attached
   * to a closed sprint is the one outcome that corrupts every future velocity figure,
   * so the caller has to say where it goes.</p>
   */
  complete: (
    api: ApiClient,
    sprintId: string,
    body: {
      spilloverStrategy: "BACKLOG" | "NEXT_SPRINT" | "TARGET_SPRINT";
      targetSprintId?: string | null;
      retrospectiveSummary?: string | null;
    },
  ) =>
    api.request(`/sprints/${sprintId}/complete`, {
      method: "POST",
      body,
      schema: z.object({
        sprintId: z.uuid(),
        completedPoints: z.coerce.number().nullish(),
        spilledCount: z.number().int().nullish(),
        spilledToSprintId: z.uuid().nullish(),
        velocity: z.coerce.number().nullish(),
      }),
    }),

  addItems: (api: ApiClient, sprintId: string, storyIds: string[]) =>
    api.request(`/sprints/${sprintId}/items`, {
      method: "POST",
      body: { storyIds },
      schema: z.array(z.unknown()),
    }),

  removeItem: (api: ApiClient, sprintId: string, storyId: string) =>
    api.request(`/sprints/${sprintId}/items/${storyId}`, { method: "DELETE" }),

  retrospective: (api: ApiClient, sprintId: string) =>
    api.request(`/sprints/${sprintId}/retrospective`, { schema: retrospectiveSchema.nullish() }),

  saveRetrospective: (
    api: ApiClient,
    sprintId: string,
    body: {
      wentWell: string[];
      wentWrong: string[];
      actionItems: Record<string, unknown>[];
      summary?: string | null;
    },
  ) =>
    api.request(`/sprints/${sprintId}/retrospective`, {
      method: "POST",
      body,
      schema: retrospectiveSchema,
    }),
};
