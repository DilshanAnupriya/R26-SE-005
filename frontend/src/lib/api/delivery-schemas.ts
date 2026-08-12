import { z } from "zod";

/**
 * Zod mirrors of the delivery, AI and collaboration wire contracts.
 *
 * <p>Separate from `schemas.ts`, which covers auth and the shared envelopes. That file
 * is imported by the BFF routes and by middleware; dragging the whole delivery contract
 * into those bundles would cost every auth request a schema tree it never uses.</p>
 *
 * <p>Enums are mirrored as unions so `switch` over a status is exhaustive and adding a
 * backend value fails the build here rather than rendering as a blank badge. The one
 * exception is anything the backend may extend without a UI change — those stay
 * `z.string()`, and each says why.</p>
 */

// ------------------------------------------------------------------ shared pieces

export const prioritySchema = z.enum(["CRITICAL", "HIGH", "MEDIUM", "LOW", "TRIVIAL"]);
export type Priority = z.infer<typeof prioritySchema>;

export const entityTypeSchema = z.enum([
  "PROJECT",
  "MILESTONE",
  "REQUIREMENT",
  "EPIC",
  "STORY",
  "TASK",
  "SPRINT",
]);
export type EntityType = z.infer<typeof entityTypeSchema>;

export const userRefSchema = z.object({
  id: z.uuid(),
  fullName: z.string(),
  avatarUrl: z.string().nullish(),
});

export const labelSchema = z.object({
  id: z.uuid(),
  projectId: z.uuid().nullish(),
  name: z.string(),
  color: z.string().nullish(),
  global: z.boolean(),
});
export type Label = z.infer<typeof labelSchema>;

// ---------------------------------------------------------------------- projects

export const projectStatusSchema = z.enum([
  "PLANNING",
  "ACTIVE",
  "ON_HOLD",
  "COMPLETED",
  "CANCELLED",
]);
export const projectHealthSchema = z.enum(["ON_TRACK", "AT_RISK", "OFF_TRACK"]);

export const projectSchema = z.object({
  id: z.uuid(),
  workspaceId: z.uuid(),
  projectKey: z.string(),
  name: z.string(),
  description: z.string().nullish(),
  status: projectStatusSchema,
  health: projectHealthSchema,
  progressPct: z.coerce.number(),
  startDate: z.string().nullish(),
  targetDate: z.string().nullish(),
  budgetAmount: z.coerce.number().nullish(),
  currency: z.string().nullish(),
  leadUserId: z.uuid().nullish(),
  leadName: z.string().nullish(),
  memberCount: z.number().int(),
  domain: z.string().nullish(),
  techStack: z.array(z.string()).default([]),
  defaultSprintDays: z.number().int().nullish(),
  createdAt: z.string(),
  updatedAt: z.string().nullish(),
});
export type Project = z.infer<typeof projectSchema>;

export const projectOverviewSchema = z.object({
  project: projectSchema,
  storyCount: z.number().int(),
  completedStoryCount: z.number().int(),
  taskCount: z.number().int(),
  openTaskCount: z.number().int(),
  blockedCount: z.number().int(),
  epicCount: z.number().int(),
  totalPoints: z.coerce.number(),
  completedPoints: z.coerce.number(),
  completionPct: z.coerce.number(),
  activeSprintId: z.uuid().nullish(),
  activeSprintName: z.string().nullish(),
});
export type ProjectOverview = z.infer<typeof projectOverviewSchema>;

export const workflowStateSchema = z.object({
  id: z.uuid(),
  projectId: z.uuid(),
  name: z.string(),
  code: z.string(),
  category: z.enum(["TODO", "IN_PROGRESS", "DONE", "CANCELLED"]),
  position: z.number().int(),
  wipLimit: z.number().int().nullish(),
  color: z.string().nullish(),
});
export type WorkflowState = z.infer<typeof workflowStateSchema>;

// ----------------------------------------------------------------------- backlog

export const storyStatusSchema = z.enum([
  "BACKLOG",
  "READY",
  "IN_PROGRESS",
  "IN_REVIEW",
  "BLOCKED",
  "DONE",
  "CANCELLED",
]);
export type StoryStatus = z.infer<typeof storyStatusSchema>;

export const storyTypeSchema = z.enum(["STORY", "BUG", "SPIKE", "CHORE", "TECH_DEBT"]);

export const estimateSourceSchema = z.enum([
  "UNESTIMATED",
  "MANUAL",
  "AI_ACCEPTED",
  "AI_OVERRIDDEN",
]);

/** The model's suggestion, kept beside the human's number so the UI can compare them. */
export const aiEstimateSchema = z.object({
  points: z.coerce.number().nullish(),
  confidence: z.coerce.number().nullish(),
  lowerBound: z.coerce.number().nullish(),
  upperBound: z.coerce.number().nullish(),
  predictionId: z.uuid().nullish(),
});
export type AiEstimate = z.infer<typeof aiEstimateSchema>;

export const storySchema = z.object({
  id: z.uuid(),
  projectId: z.uuid(),
  epicId: z.uuid().nullish(),
  sprintId: z.uuid().nullish(),
  workflowStateId: z.uuid().nullish(),
  itemKey: z.string(),
  title: z.string(),
  description: z.string().nullish(),
  acceptanceCriteria: z.string().nullish(),
  type: storyTypeSchema,
  status: storyStatusSchema,
  priority: prioritySchema,
  storyPoints: z.coerce.number().nullish(),
  aiEstimate: aiEstimateSchema.nullish(),
  estimateSource: estimateSourceSchema,
  businessValue: z.number().int().nullish(),
  rank: z.string(),
  assigneeId: z.uuid().nullish(),
  assigneeName: z.string().nullish(),
  assigneeAvatarUrl: z.string().nullish(),
  dueDate: z.string().nullish(),
  blocked: z.boolean(),
  blockedReason: z.string().nullish(),
  reopenedCount: z.number().int(),
  labels: z.array(labelSchema).default([]),
  startedAt: z.string().nullish(),
  completedAt: z.string().nullish(),
  createdAt: z.string(),
  updatedAt: z.string().nullish(),
});
export type Story = z.infer<typeof storySchema>;

export const epicSchema = z.object({
  id: z.uuid(),
  projectId: z.uuid(),
  itemKey: z.string(),
  title: z.string(),
  description: z.string().nullish(),
  status: z.string(),
  priority: prioritySchema,
  color: z.string().nullish(),
  storyCount: z.number().int().nullish(),
  completedStoryCount: z.number().int().nullish(),
});
export type Epic = z.infer<typeof epicSchema>;

// ------------------------------------------------------------------------ sprints

export const sprintStatusSchema = z.enum(["PLANNED", "ACTIVE", "COMPLETED", "CANCELLED"]);
export type SprintStatus = z.infer<typeof sprintStatusSchema>;

export const sprintSchema = z.object({
  id: z.uuid(),
  projectId: z.uuid(),
  sequenceNo: z.number().int(),
  name: z.string(),
  goal: z.string().nullish(),
  status: sprintStatusSchema,
  startDate: z.string().nullish(),
  endDate: z.string().nullish(),
  durationDays: z.number().int().nullish(),
  teamSize: z.number().int().nullish(),
  capacityPoints: z.coerce.number().nullish(),
  capacityHours: z.coerce.number().nullish(),
  committedPoints: z.coerce.number(),
  completedPoints: z.coerce.number(),
  addedPoints: z.coerce.number(),
  removedPoints: z.coerce.number(),
  completionRate: z.coerce.number().nullish(),
  plannedCostAmount: z.coerce.number().nullish(),
  forecastCostAmount: z.coerce.number().nullish(),
  budgetAmount: z.coerce.number().nullish(),
  daysRemaining: z.number().int().nullish(),
  storyCount: z.number().int().nullish(),
});
export type Sprint = z.infer<typeof sprintSchema>;

/** Mirrors `SprintDtos.BoardColumnResponse` — the workflow state nested, not flattened. */
export const boardColumnSchema = z.object({
  state: workflowStateSchema,
  stories: z.array(storySchema).default([]),
  itemCount: z.number().int(),
  wipLimitExceeded: z.boolean(),
});

export const sprintBoardSchema = z.object({
  sprintId: z.uuid(),
  sprintName: z.string(),
  status: sprintStatusSchema.nullish(),
  columns: z.array(boardColumnSchema).default([]),
  totalItems: z.number().int(),
});
export type SprintBoard = z.infer<typeof sprintBoardSchema>;

export const burndownPointSchema = z.object({
  date: z.string(),
  idealRemaining: z.coerce.number().nullish(),
  actualRemaining: z.coerce.number().nullish(),
  completed: z.coerce.number().nullish(),
  scopeChange: z.coerce.number().nullish(),
});

export const burndownSchema = z.object({
  sprintId: z.uuid(),
  startDate: z.string().nullish(),
  endDate: z.string().nullish(),
  committedPoints: z.coerce.number(),
  completedPoints: z.coerce.number(),
  remainingPoints: z.coerce.number(),
  points: z.array(burndownPointSchema).default([]),
});
export type Burndown = z.infer<typeof burndownSchema>;

// -------------------------------------------------------------------------- tasks

export const taskStatusSchema = z.enum([
  "TODO",
  "IN_PROGRESS",
  "IN_REVIEW",
  "BLOCKED",
  "DONE",
  "CANCELLED",
]);

export const taskSchema = z.object({
  id: z.uuid(),
  projectId: z.uuid(),
  storyId: z.uuid().nullish(),
  parentTaskId: z.uuid().nullish(),
  sprintId: z.uuid().nullish(),
  workflowStateId: z.uuid().nullish(),
  itemKey: z.string(),
  title: z.string(),
  description: z.string().nullish(),
  type: z.string(),
  status: taskStatusSchema,
  priority: prioritySchema,
  assigneeId: z.uuid().nullish(),
  assigneeName: z.string().nullish(),
  assigneeAvatarUrl: z.string().nullish(),
  estimateMinutes: z.number().int().nullish(),
  spentMinutes: z.number().int().nullish(),
  requiredSkills: z.array(z.string()).default([]),
  dueDate: z.string().nullish(),
  blocked: z.boolean(),
  blockedReason: z.string().nullish(),
  rank: z.string().nullish(),
  createdAt: z.string(),
});
export type Task = z.infer<typeof taskSchema>;

export const workloadEntrySchema = z.object({
  userId: z.uuid(),
  fullName: z.string(),
  avatarUrl: z.string().nullish(),
  openTaskCount: z.number().int(),
  blockedTaskCount: z.number().int(),
  estimatedMinutes: z.number().int(),
  spentMinutes: z.number().int(),
  loadPct: z.coerce.number(),
  overAllocated: z.boolean(),
});
export type WorkloadEntry = z.infer<typeof workloadEntrySchema>;
