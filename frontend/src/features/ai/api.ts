import { z } from "zod";
import type { ApiClient } from "@/lib/api/client";
import {
  aiInsightsSchema,
  aiModelSchema,
  allocationRankingSchema,
  batchEstimateSchema,
  costForecastSchema,
  costTrendPointSchema,
  feedbackSchema,
  riskAssessmentSchema,
  sprintRiskSchema,
  storyPointEstimateSchema,
  velocityPredictionSchema,
  type FeedbackAction,
} from "@/lib/api/ai-schemas";

/** Calls to the AI layer. Every one returns the same envelope shape. */
export const aiApi = {
  estimateStory: (api: ApiClient, storyId: string) =>
    api.request("/ai/estimation/story-points", {
      method: "POST",
      body: { storyId },
      schema: storyPointEstimateSchema,
    }),

  estimateBatch: (api: ApiClient, storyIds: string[]) =>
    api.request("/ai/estimation/story-points/batch", {
      method: "POST",
      body: { storyIds },
      schema: batchEstimateSchema,
    }),

  predictVelocity: (api: ApiClient, sprintId: string) =>
    api.request("/ai/estimation/velocity", {
      method: "POST",
      body: { sprintId },
      schema: velocityPredictionSchema,
    }),

  assessSprintRisk: (api: ApiClient, sprintId: string) =>
    api.request(`/ai/risk/sprint/${sprintId}/assess`, {
      method: "POST",
      schema: sprintRiskSchema,
    }),

  latestRisk: (api: ApiClient, sprintId: string) =>
    api.request(`/ai/risk/sprint/${sprintId}`, { schema: sprintRiskSchema }),

  acknowledgeRisk: (api: ApiClient, assessmentId: string) =>
    api.request(`/ai/risk/${assessmentId}/acknowledge`, {
      method: "POST",
      schema: riskAssessmentSchema,
    }),

  rankCandidates: (
    api: ApiClient,
    entityType: "TASK" | "STORY",
    entityId: string,
    limit = 5,
  ) =>
    api.request("/ai/allocation/rank", {
      method: "POST",
      body: { entityType, entityId, limit },
      schema: allocationRankingSchema,
    }),

  forecastCost: (api: ApiClient, sprintId: string, stage: "initial" | "mid-sprint") =>
    api.request(`/ai/cost/${stage}`, {
      method: "POST",
      body: { sprintId },
      schema: costForecastSchema,
    }),

  costHistory: (api: ApiClient, sprintId: string) =>
    api.request(`/ai/cost/sprint/${sprintId}/history`, {
      schema: z.array(costTrendPointSchema),
    }),

  /**
   * Sends the verdict back.
   *
   * <p>`apply` defaults on the server to true for accept and override, which is what a
   * user pressing Accept during planning means. The comparison view passes false so it
   * can show what the model would have said without changing anything.</p>
   */
  submitFeedback: (
    api: ApiClient,
    predictionId: string,
    body: { action: FeedbackAction; finalValue?: number | null; reason?: string; apply?: boolean },
  ) =>
    api.request(`/ai/predictions/${predictionId}/feedback`, {
      method: "POST",
      body,
      schema: feedbackSchema,
    }),

  projectInsights: (api: ApiClient, projectId: string) =>
    api.request(`/ai/insights/project/${projectId}`, { schema: aiInsightsSchema }),

  sprintInsights: (api: ApiClient, sprintId: string) =>
    api.request(`/ai/insights/sprint/${sprintId}`, { schema: aiInsightsSchema }),

  refreshSprintInsights: (api: ApiClient, sprintId: string) =>
    api.request(`/ai/insights/sprint/${sprintId}/refresh`, {
      method: "POST",
      schema: aiInsightsSchema,
    }),

  models: (api: ApiClient, workspaceId?: string) =>
    api.request("/ai/models", {
      schema: z.array(aiModelSchema),
      query: { workspaceId },
    }),

  updateModel: (
    api: ApiClient,
    code: string,
    body: { enabled?: boolean; version?: string; latencyBudgetMs?: number },
  ) => api.request(`/ai/models/${code}`, { method: "PATCH", body, schema: aiModelSchema }),
};
