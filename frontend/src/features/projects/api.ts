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
 * Workspaces, needed only so the create form can say where a project goes.
 *
 * <p>Lives here rather than in its own feature because this is the only screen that
 * asks: a project must belong to one, and most organizations have exactly the single
 * default workspace provisioning created.</p>
 */
export const workspaceSchema = z.object({
  id: z.uuid(),
  name: z.string(),
  workspaceKey: z.string().nullish(),
  defaultWorkspace: z.boolean().nullish(),
});
export type Workspace = z.infer<typeof workspaceSchema>;

export const workspacesApi = {
  // A bare array, not a page — the controller returns List<WorkspaceResponse>.
  list: (api: ApiClient) =>
    api.request("/workspaces", { schema: z.array(workspaceSchema) }),
};

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

      // What the story-point model needs about the team rather than the story.
      // Optional throughout: omitted, the model falls back to the medians of its
      // training set and says so. Sending 0 would instead tell it this team has
      // never delivered anything, so these must stay absent rather than defaulted.
      domain?: string | null;
      techStack?: string[];
      teamSize?: number | null;
      devExperienceLevel?: "junior" | "mid" | "senior" | null;
      teamVelocityAvg?: number | null;
      recentCompletionRate?: number | null;
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
