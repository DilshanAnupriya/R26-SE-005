import { z } from "zod";
import type { ApiClient } from "@/lib/api/client";
import {
  dailyRiskPointSchema,
  riskAdjustmentSchema,
  sprintRiskComparisonSchema,
  sprintRiskSchema,
} from "@/lib/api/ai-schemas";

// --------------------------------------------------------- requirement changes

export const REQUIREMENT_CHANGE_TYPES = [
  "SCOPE_ADDED",
  "SCOPE_REMOVED",
  "ACCEPTANCE_CRITERIA_CHANGED",
  "PRIORITY_CHANGED",
  "DESCRIPTION_UPDATED",
  "OTHER",
] as const;
export type RequirementChangeType = (typeof REQUIREMENT_CHANGE_TYPES)[number];

export const requirementChangeTypeSchema = z.enum(REQUIREMENT_CHANGE_TYPES);
export const impactDirectionSchema = z.enum(["INCREASE", "DECREASE"]);
export type ImpactDirection = z.infer<typeof impactDirectionSchema>;

export const requirementChangeSchema = z.object({
  id: z.uuid(),
  projectId: z.uuid(),
  sprintId: z.uuid().nullish(),
  requirementId: z.uuid().nullish(),
  requirementCode: z.string().nullish(),
  storyId: z.uuid().nullish(),
  changeType: requirementChangeTypeSchema,
  impactDirection: impactDirectionSchema,
  description: z.string().nullish(),
  requestedBy: z.uuid().nullish(),
  requestedByName: z.string().nullish(),
  /** Points this change contributed, on the ledger's 0-100 scale. Signed. */
  riskDelta: z.coerce.number(),
  changedAt: z.string(),
});
export type RequirementChange = z.infer<typeof requirementChangeSchema>;

export const requirementChangeSummarySchema = z.object({
  sprintId: z.uuid(),
  totalChanges: z.number().int(),
  increasingChanges: z.number().int(),
  decreasingChanges: z.number().int(),
  netRiskDelta: z.coerce.number(),
  changes: z.array(requirementChangeSchema).default([]),
});
export type RequirementChangeSummary = z.infer<typeof requirementChangeSummarySchema>;

/**
 * What a change type will cost, served by the server.
 *
 * <p>Not duplicated in the client on purpose. A form that promised "+8" while the
 * ledger recorded "+5" would be worse than one that promised nothing, and the weights
 * live on the server enum where they are defined.</p>
 */
export const requirementChangeTypeInfoSchema = z.object({
  changeType: requirementChangeTypeSchema,
  /** True for the two scope changes, whose sign is inherent. The UI hides the toggle. */
  directionFixed: z.boolean(),
  increaseDelta: z.coerce.number(),
  decreaseDelta: z.coerce.number(),
  increaseHint: z.string(),
  decreaseHint: z.string(),
});
export type RequirementChangeTypeInfo = z.infer<typeof requirementChangeTypeInfoSchema>;

// ------------------------------------------------------------------------ api

export const riskApi = {
  /** Latest stored scoring per model, with board activity since each one folded in. */
  latest: (api: ApiClient, sprintId: string) =>
    api.request(`/ai/risk/sprint/${sprintId}`, { schema: sprintRiskSchema }),

  /** Runs all three models now. Re-scoring resets the adjustment overlay to zero. */
  assess: (api: ApiClient, sprintId: string) =>
    api.request(`/ai/risk/sprint/${sprintId}/assess`, {
      method: "POST",
      schema: sprintRiskSchema,
    }),

  dailyTrend: (api: ApiClient, sprintId: string) =>
    api.request(`/ai/risk/sprint/${sprintId}/daily-trend`, {
      schema: z.array(dailyRiskPointSchema),
    }),

  adjustments: (api: ApiClient, sprintId: string) =>
    api.request(`/ai/risk/sprint/${sprintId}/adjustments`, {
      schema: z.array(riskAdjustmentSchema),
    }),

  compare: (api: ApiClient, sprintIds: string[]) =>
    api.request("/ai/risk/compare", {
      schema: z.array(sprintRiskComparisonSchema),
      // Repeated rather than comma-joined: Spring binds `sprintIds=a&sprintIds=b` to a
      // List<UUID> without a converter, and a joined string needs one.
      query: { sprintIds },
    }),

  requirementChangeTypes: (api: ApiClient) =>
    api.request("/requirement-changes/types", {
      schema: z.array(requirementChangeTypeInfoSchema),
    }),

  sprintRequirementChanges: (api: ApiClient, sprintId: string) =>
    api.request(`/sprints/${sprintId}/requirement-changes`, {
      schema: requirementChangeSummarySchema,
    }),

  projectRequirementChanges: (api: ApiClient, projectId: string) =>
    api.request(`/projects/${projectId}/requirement-changes`, {
      schema: z.array(requirementChangeSchema),
    }),

  logRequirementChange: (
    api: ApiClient,
    projectId: string,
    body: {
      changeType: RequirementChangeType;
      impactDirection?: ImpactDirection;
      sprintId?: string | null;
      requirementId?: string | null;
      storyId?: string | null;
      description?: string | null;
      requestedByName?: string | null;
    },
  ) =>
    api.request(`/projects/${projectId}/requirement-changes`, {
      method: "POST",
      body,
      schema: requirementChangeSchema,
    }),

  deleteRequirementChange: (api: ApiClient, changeId: string) =>
    api.request(`/requirement-changes/${changeId}`, { method: "DELETE" }),
};
