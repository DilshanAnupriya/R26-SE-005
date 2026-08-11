import type { ApiClient } from "@/lib/api/client";
import { pageResponseSchema } from "@/lib/api/schemas";
import {
  projectOverviewSchema,
  projectSchema,
  workflowStateSchema,
  type Project,
} from "@/lib/api/delivery-schemas";
import { z } from "zod";

/**
 * The projects feature's calls, as plain functions taking the client.
 *
 * <p>They take an {@link ApiClient} rather than reaching for a module-level singleton:
 * the client carries the session's token and active organization, so a module-scoped one
 * would be shared across users on the server and would need rebuilding on every org
 * switch on the client.</p>
 */
export const projectsApi = {
  list: (api: ApiClient, filters: Record<string, string | number | undefined> = {}) =>
    api.request("/projects", {
      schema: pageResponseSchema(projectSchema),
      query: filters,
    }),

  get: (api: ApiClient, projectId: string) =>
    api.request(`/projects/${projectId}`, { schema: projectSchema }),

  overview: (api: ApiClient, projectId: string) =>
    api.request(`/projects/${projectId}/overview`, { schema: projectOverviewSchema }),

  workflowStates: (api: ApiClient, projectId: string) =>
    api.request(`/projects/${projectId}/workflow-states`, {
      schema: z.array(workflowStateSchema),
    }),

  members: (api: ApiClient, projectId: string) =>
    api.request(`/projects/${projectId}/members`, {
      schema: z.array(
        z.object({
          userId: z.uuid(),
          fullName: z.string().nullish(),
          avatarUrl: z.string().nullish(),
          role: z.string().nullish(),
          stakeholder: z.boolean().nullish(),
          allocationPct: z.number().int().nullish(),
        }),
      ),
    }),

  create: (
    api: ApiClient,
    body: {
      workspaceId: string;
      name: string;
      projectKey?: string | null;
      description?: string | null;
      startDate?: string | null;
      targetDate?: string | null;
      budgetAmount?: number | null;
      currency?: string | null;
    },
  ) =>
    api.request("/projects", {
      method: "POST",
      body: {
        techStack: [],
        definitionOfReady: [],
        definitionOfDone: [],
        defaultSprintDays: 14,
        currency: "USD",
        ...body,
      },
      schema: projectSchema,
    }) as Promise<Project>,

  update: (api: ApiClient, projectId: string, body: Record<string, unknown>) =>
    api.request(`/projects/${projectId}`, { method: "PATCH", body, schema: projectSchema }),

  remove: (api: ApiClient, projectId: string) =>
    api.request(`/projects/${projectId}`, { method: "DELETE" }),
};
