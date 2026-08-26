import { z } from "zod";
import type { ApiClient } from "@/lib/api/client";
import {
  dailyRiskPointSchema,
  riskAdjustmentSchema,
  riskRemediationStepSchema,
  sprintRiskComparisonSchema,
  sprintRiskSchema,
  type RiskType,
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

  /**
   * Runs all three models now.
   *
   * <p>Re-scoring clears the board readings from the overlay — the models have just
   * re-read them — and settles the claimed remediation steps against the new figures
   * rather than discarding them, so a ticked step does not hand its points back.</p>
   */
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

  /** What to do about each driver, with what completing it currently returns. */
  steps: (api: ApiClient, sprintId: string) =>
    api.request(`/ai/risk/sprint/${sprintId}/steps`, {
      schema: z.array(riskRemediationStepSchema),
    }),

  /**
   * Ticks a step, or unticks it.
   *
   * <p>Sends the step's identity and nothing else. The points it is worth are decided
   * server-side from the stored assessment — a client that could name its own delta
   * could set the sprint's risk to anything and have the ledger record it as
   * evidence.</p>
   */
  toggleStep: (
    api: ApiClient,
    sprintId: string,
    body: { riskType: RiskType; stepKey: string; done: boolean },
  ) =>
    api.request(`/ai/risk/sprint/${sprintId}/steps`, {
      method: "POST",
      body,
      schema: z.array(riskRemediationStepSchema),
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
