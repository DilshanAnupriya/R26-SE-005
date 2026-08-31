import type { ApiClient } from "@/lib/api/client";
import { pageResponseSchema } from "@/lib/api/schemas";
import {
  labelSchema,
  projectOverviewSchema,
  projectSchema,
  workflowStateSchema,
  type Label,
  type Project,
} from "@/lib/api/delivery-schemas";
import { z } from "zod";

/**
 * Statuses that mean a project is finished with.
 *
 * <p>Mirrors {@code ProjectStatus.isTerminal()} on the backend. Everything else —
 * PLANNING, ACTIVE, ON_HOLD — is a project somebody can still open a board or a
 * backlog for.</p>
 */
const FINISHED_STATUSES = new Set(["COMPLETED", "ARCHIVED"]);

/**
 * The projects a picker should offer.
 *
 * <p>Not "ACTIVE only", which is the trap: a project is created as PLANNING, so
 * filtering on ACTIVE hides every project the moment it is made and tells the user
 * there are none. That is exactly backwards — a brand-new project is the one you are
 * most likely to be opening a backlog for.</p>
 */
export function workableProjects<T extends { status: string }>(projects: T[]): T[] {
  return projects.filter((project) => !FINISHED_STATUSES.has(project.status));
}

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

/**
 * Labels, which the estimator reads as well as the eye.
 *
 * <p>They were reachable only through the API: the backend has listed, created and
 * applied them since the backlog module landed, and nothing in the product ever offered
 * to set one. That is a cosmetic gap everywhere except here — a story's label count is
 * one of the three things {@code num_components} is summed from, and that is the
 * strongest structural driver the story-point model has. A backlog with no labels hands
 * every story the same value for it.</p>
 */
export const labelsApi = {
  /** A project's own labels and the organization-wide ones, already merged server-side. */
  listForProject: (api: ApiClient, projectId: string) =>
    api.request(`/projects/${projectId}/labels`, { schema: z.array(labelSchema) }),

  create: (
    api: ApiClient,
    body: { name: string; color?: string | null; projectId?: string | null },
  ) => api.request("/labels", { method: "POST", body, schema: labelSchema }) as Promise<Label>,
};
