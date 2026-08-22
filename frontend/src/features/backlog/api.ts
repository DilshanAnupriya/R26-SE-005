import { z } from "zod";
import type { ApiClient } from "@/lib/api/client";
import { pageResponseSchema } from "@/lib/api/schemas";
import { epicSchema, storySchema } from "@/lib/api/delivery-schemas";

export const backlogApi = {
  /** `sprintId=none` is the backend's filter for items not yet committed anywhere. */
  list: (
    api: ApiClient,
    projectId: string,
    filters: Record<string, string | number | undefined> = {},
  ) =>
    api.request(`/projects/${projectId}/backlog`, {
      schema: pageResponseSchema(storySchema),
      query: filters,
    }),

  story: (api: ApiClient, storyId: string) =>
    api.request(`/stories/${storyId}`, { schema: storySchema }),

  epics: (api: ApiClient, projectId: string) =>
    api.request("/epics", {
      schema: pageResponseSchema(epicSchema),
      query: { projectId, size: 100 },
    }),

  createStory: (
    api: ApiClient,
    body: {
      projectId: string;
      title: string;
      description?: string | null;
      // Acceptance criteria and epic are not decoration: the estimator reads the
      // criteria count as its component signal and scores the combined text, so a
      // story created without them is one it has less to go on for.
      acceptanceCriteria?: string | null;
      epicId?: string | null;
      type?: string | null;
      priority?: string | null;
      storyPoints?: number | null;
      assigneeId?: string | null;
      sprintId?: string | null;
    },
  ) => api.request("/stories", { method: "POST", body, schema: storySchema }),

  createEpic: (
    api: ApiClient,
    body: { projectId: string; title: string; description?: string | null },
  ) => api.request("/epics", { method: "POST", body, schema: epicSchema }),

  updateStory: (api: ApiClient, storyId: string, body: Record<string, unknown>) =>
    api.request(`/stories/${storyId}`, { method: "PATCH", body, schema: storySchema }),

  estimate: (
    api: ApiClient,
    storyId: string,
    body: { storyPoints: number; source?: string; predictionId?: string | null },
  ) => api.request(`/stories/${storyId}/estimate`, { method: "PATCH", body, schema: storySchema }),

  changeStatus: (api: ApiClient, storyId: string, status: string) =>
    api.request(`/stories/${storyId}/status`, {
      method: "PATCH",
      body: { status },
      schema: storySchema,
    }),

  assign: (api: ApiClient, storyId: string, assigneeId: string | null) =>
    api.request(`/stories/${storyId}/assign`, {
      method: "PATCH",
      body: { assigneeId },
      schema: storySchema,
    }),

  /**
   * Reorders one item between two neighbours.
   *
   * <p>Sends the ids either side of the drop rather than an index. The backend allocates
   * a LexoRank between them, so only the row that moved is rewritten — an index-based
   * reorder renumbers everything below the drop point on every drag.</p>
   */
  rank: (
    api: ApiClient,
    projectId: string,
    body: { itemId: string; entityType: string; beforeId?: string | null; afterId?: string | null },
  ) =>
    api.request(`/projects/${projectId}/backlog/rank`, {
      method: "POST",
      body,
      schema: z.array(z.object({ id: z.uuid(), rank: z.string() })),
    }),
};
