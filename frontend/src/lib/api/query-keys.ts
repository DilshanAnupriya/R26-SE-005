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
    /** A project's own labels plus the organization-wide ones, as one list. */
    labels: (id: string) => [...queryKeys.projects.all, "labels", id] as const,
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
    workload: (projectId: string) => [...queryKeys.tasks.all, "workload", projectId] as const,
  },

  /**
   * AI keys are split by capability rather than lumped under one `ai` list, so
   * accepting an estimate invalidates estimates without discarding a cost forecast
   * that cost a model call to produce.
   */
  ai: {
    all: ["ai"] as const,
    estimate: (storyId: string) => [...queryKeys.ai.all, "estimate", storyId] as const,
    velocity: (sprintId: string) => [...queryKeys.ai.all, "velocity", sprintId] as const,
    risk: (sprintId: string) => [...queryKeys.ai.all, "risk", sprintId] as const,
    riskTrend: (sprintId: string) => [...queryKeys.ai.all, "risk-trend", sprintId] as const,
    riskAdjustments: (sprintId: string) =>
      [...queryKeys.ai.all, "risk-adjustments", sprintId] as const,
    riskSteps: (sprintId: string) => [...queryKeys.ai.all, "risk-steps", sprintId] as const,
    // Sorted at the call site, so picking the same two sprints in either order hits one
    // cache entry rather than two.
    riskCompare: (sprintIds: string[]) => [...queryKeys.ai.all, "risk-compare", sprintIds] as const,
    cost: (sprintId: string) => [...queryKeys.ai.all, "cost", sprintId] as const,
    costHistory: (sprintId: string) => [...queryKeys.ai.all, "cost-history", sprintId] as const,
    costValidation: (sprintId: string) => [...queryKeys.ai.all, "cost-validation", sprintId] as const,
    costRiskCrossCheck: (sprintId: string) =>
      [...queryKeys.ai.all, "cost-risk-cross-check", sprintId] as const,
    allocation: (entityType: string, entityId: string) =>
      [...queryKeys.ai.all, "allocation", entityType, entityId] as const,
    insights: (scope: "project" | "sprint", id: string) =>
      [...queryKeys.ai.all, "insights", scope, id] as const,
    models: () => [...queryKeys.ai.all, "models"] as const,
    predictions: (filters: Record<string, unknown> = {}) =>
      [...queryKeys.ai.all, "predictions", filters] as const,
  },

  /** Conversations are per user, so the keys need no user id: the cache is per session. */
  copilot: {
    all: ["copilot"] as const,
    status: () => [...queryKeys.copilot.all, "status"] as const,
    conversations: () => [...queryKeys.copilot.all, "conversations"] as const,
    conversation: (id: string) => [...queryKeys.copilot.all, "conversation", id] as const,
  },

  requirementChanges: {
    all: ["requirement-changes"] as const,
    types: () => [...queryKeys.requirementChanges.all, "types"] as const,
    forSprint: (sprintId: string) =>
      [...queryKeys.requirementChanges.all, "sprint", sprintId] as const,
    forProject: (projectId: string) =>
      [...queryKeys.requirementChanges.all, "project", projectId] as const,
  },

  comments: {
    all: ["comments"] as const,
    list: (entityType: string, entityId: string) =>
      [...queryKeys.comments.all, entityType, entityId] as const,
    count: (entityType: string, entityId: string) =>
      [...queryKeys.comments.all, "count", entityType, entityId] as const,
  },

  attachments: {
    all: ["attachments"] as const,
    list: (entityType: string, entityId: string) =>
      [...queryKeys.attachments.all, entityType, entityId] as const,
  },

  notifications: {
    all: ["notifications"] as const,
    list: (filters: Record<string, unknown> = {}) =>
      [...queryKeys.notifications.all, "list", filters] as const,
    unreadCount: () => [...queryKeys.notifications.all, "unread-count"] as const,
    preferences: () => [...queryKeys.notifications.all, "preferences"] as const,
  },

  activity: {
    all: ["activity"] as const,
    feed: (filters: Record<string, unknown> = {}) =>
      [...queryKeys.activity.all, "feed", filters] as const,
    history: (entityType: string, entityId: string) =>
      [...queryKeys.activity.all, "history", entityType, entityId] as const,
  },

  reports: {
    all: ["reports"] as const,
    velocity: (projectId: string) => [...queryKeys.reports.all, "velocity", projectId] as const,
    burndown: (sprintId: string) => [...queryKeys.reports.all, "burndown", sprintId] as const,
    burnup: (sprintId: string) => [...queryKeys.reports.all, "burnup", sprintId] as const,
    cfd: (projectId: string) => [...queryKeys.reports.all, "cfd", projectId] as const,
    cycleTime: (projectId: string) => [...queryKeys.reports.all, "cycle-time", projectId] as const,
    leadTime: (projectId: string) => [...queryKeys.reports.all, "lead-time", projectId] as const,
    productivity: (projectId: string, sprintId?: string) =>
      [...queryKeys.reports.all, "productivity", projectId, sprintId ?? null] as const,
    completionRate: (projectId: string) =>
      [...queryKeys.reports.all, "completion-rate", projectId] as const,
    workloadBalance: (projectId: string) =>
      [...queryKeys.reports.all, "workload-balance", projectId] as const,
    costTrend: (projectId: string) => [...queryKeys.reports.all, "cost-trend", projectId] as const,
    riskSummary: (projectId: string) => [...queryKeys.reports.all, "risk-summary", projectId] as const,
  },

  settings: {
    all: ["settings"] as const,
    workspace: (workspaceId: string) => [...queryKeys.settings.all, "workspace", workspaceId] as const,
    featureFlags: (workspaceId: string) => [...queryKeys.settings.all, "flags", workspaceId] as const,
    integrations: (workspaceId?: string) =>
      [...queryKeys.settings.all, "integrations", workspaceId ?? null] as const,
  },

  users: {
    all: ["users"] as const,
    list: (filters: Record<string, unknown> = {}) => [...queryKeys.users.all, "list", filters] as const,
    developerProfile: (userId: string) =>
      [...queryKeys.users.all, "developer-profile", userId] as const,
    skills: (userId: string) => [...queryKeys.users.all, "skills", userId] as const,
  },

  recycleBin: {
    all: ["recycle-bin"] as const,
    list: (entityType: string) => [...queryKeys.recycleBin.all, entityType] as const,
  },
} as const;
