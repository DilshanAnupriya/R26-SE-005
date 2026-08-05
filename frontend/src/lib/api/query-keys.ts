/**
 * One factory for every TanStack Query key.
 *
 * <p>Keys built inline at each call site drift — `["projects", filters]` in one file
 * and `["project-list", filters]` in another look identical in review and never
 * invalidate each other. Centralising them makes invalidation greppable, and gives
 * Phase 4's WebSocket handler a single obvious place to invalidate from when a
 * server-pushed change arrives.</p>
 *
 * <p>Keys are hierarchical: invalidating `projects.all` also invalidates every list
 * and detail beneath it, because TanStack matches on key prefixes.</p>
 */

export type ProjectFilters = {
  workspaceId?: string;
  status?: string;
  health?: string;
  leadId?: string;
  q?: string;
  page?: number;
};

export const queryKeys = {
  session: ["session"] as const,

  organizations: {
    all: ["organizations"] as const,
    list: () => [...queryKeys.organizations.all, "list"] as const,
    detail: (id: string) => [...queryKeys.organizations.all, "detail", id] as const,
    members: (id: string) => [...queryKeys.organizations.all, "members", id] as const,
  },

  workspaces: {
    all: ["workspaces"] as const,
    list: () => [...queryKeys.workspaces.all, "list"] as const,
    detail: (id: string) => [...queryKeys.workspaces.all, "detail", id] as const,
  },

  projects: {
    all: ["projects"] as const,
    list: (filters: ProjectFilters = {}) => [...queryKeys.projects.all, "list", filters] as const,
    detail: (id: string) => [...queryKeys.projects.all, "detail", id] as const,
    overview: (id: string) => [...queryKeys.projects.all, "overview", id] as const,
    health: (id: string) => [...queryKeys.projects.all, "health", id] as const,
    members: (id: string) => [...queryKeys.projects.all, "members", id] as const,
    workflowStates: (id: string) => [...queryKeys.projects.all, "workflow-states", id] as const,
    board: (id: string) => [...queryKeys.projects.all, "board", id] as const,
  },

  backlog: {
    all: ["backlog"] as const,
    list: (projectId: string, filters: Record<string, unknown> = {}) =>
      [...queryKeys.backlog.all, projectId, filters] as const,
  },

  stories: {
    all: ["stories"] as const,
    detail: (id: string) => [...queryKeys.stories.all, "detail", id] as const,
    history: (id: string) => [...queryKeys.stories.all, "history", id] as const,
  },

  sprints: {
    all: ["sprints"] as const,
    list: (projectId: string) => [...queryKeys.sprints.all, "list", projectId] as const,
    detail: (id: string) => [...queryKeys.sprints.all, "detail", id] as const,
    board: (id: string) => [...queryKeys.sprints.all, "board", id] as const,
    burndown: (id: string) => [...queryKeys.sprints.all, "burndown", id] as const,
    capacity: (id: string) => [...queryKeys.sprints.all, "capacity", id] as const,
  },

  tasks: {
    all: ["tasks"] as const,
    list: (filters: Record<string, unknown> = {}) => [...queryKeys.tasks.all, "list", filters] as const,
    detail: (id: string) => [...queryKeys.tasks.all, "detail", id] as const,
  },
} as const;
