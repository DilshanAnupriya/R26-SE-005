import { z } from "zod";
import { entityTypeSchema } from "./delivery-schemas";

/**
 * Zod mirrors of the AI and collaboration contracts.
 *
 * <p>Numbers arrive as JSON numbers from Jackson's `BigDecimal` serialisation, but
 * `z.coerce.number()` is used throughout because a configured `WRITE_BIGDECIMAL_AS_PLAIN`
 * sends them as strings — and a confidence rendered as `"0.720"` in a progress bar's
 * width is a silent layout bug rather than a loud parse error.</p>
 */

// -------------------------------------------------------------------- envelope

export const predictionStatusSchema = z.enum(["SUCCESS", "DEGRADED", "FAILED", "TIMEOUT"]);

export const aiModelCodeSchema = z.enum([
  "M1_STORY_POINTS",
  "M1_VELOCITY",
  "M2_OVER_BUDGET",
  "M2_REQUIREMENT_CHANGE",
  "M3_ALLOCATION",
  "M4_COST_INITIAL",
  "M4_COST_MID",
]);
export type AiModelCode = z.infer<typeof aiModelCodeSchema>;

export const predictionDriverSchema = z.object({
  feature: z.string(),
  label: z.string().nullish(),
  contribution: z.coerce.number(),
  direction: z.string(),
  detail: z.string().nullish(),
});
export type PredictionDriver = z.infer<typeof predictionDriverSchema>;

export const predictionEnvelopeSchema = z.object({
  predictionId: z.uuid(),
  modelCode: aiModelCodeSchema,
  modelVersion: z.string(),
  mode: z.string(),
  entityType: entityTypeSchema.nullish(),
  entityId: z.uuid().nullish(),
  value: z.coerce.number().nullish(),
  label: z.string().nullish(),
  confidence: z.coerce.number().nullish(),
  lowerBound: z.coerce.number().nullish(),
  upperBound: z.coerce.number().nullish(),
  drivers: z.array(predictionDriverSchema).default([]),
  explanation: z.string().nullish(),
  status: predictionStatusSchema,
  /** Answered, but late or low-confidence: show it with a caveat rather than hiding it. */
  degraded: z.boolean(),
  cacheHit: z.boolean(),
  latencyMs: z.number().int().nullish(),
  createdAt: z.string(),
});
export type PredictionEnvelope = z.infer<typeof predictionEnvelopeSchema>;

// ------------------------------------------------------------------ M1 · sizing

export const storyPointEstimateSchema = z.object({
  storyId: z.uuid(),
  itemKey: z.string().nullish(),
  title: z.string().nullish(),
  currentPoints: z.coerce.number().nullish(),
  suggestedPoints: z.coerce.number().nullish(),
  prediction: predictionEnvelopeSchema.nullish(),
});
export type StoryPointEstimate = z.infer<typeof storyPointEstimateSchema>;

export const batchEstimateSchema = z.object({
  estimates: z.array(storyPointEstimateSchema).default([]),
  requested: z.number().int(),
  succeeded: z.number().int(),
});

export const commitmentVerdictSchema = z.enum([
  "OVER_COMMITTED",
  "STRETCHED",
  "BALANCED",
  "UNDER_COMMITTED",
  "UNKNOWN",
]);
export type CommitmentVerdict = z.infer<typeof commitmentVerdictSchema>;

export const velocityPredictionSchema = z.object({
  sprintId: z.uuid(),
  predictedVelocity: z.coerce.number().nullish(),
  rollingAverage: z.coerce.number().nullish(),
  committedPoints: z.coerce.number().nullish(),
  capacityPoints: z.coerce.number().nullish(),
  commitmentRatio: z.coerce.number().nullish(),
  commitmentVerdict: commitmentVerdictSchema,
  prediction: predictionEnvelopeSchema.nullish(),
});
export type VelocityPrediction = z.infer<typeof velocityPredictionSchema>;

// -------------------------------------------------------------------- M2 · risk

export const riskSeveritySchema = z.enum(["LOW", "MEDIUM", "HIGH", "CRITICAL"]);
export type RiskSeverity = z.infer<typeof riskSeveritySchema>;

export const riskTypeSchema = z.enum(["OVER_BUDGET", "REQUIREMENT_CHANGE"]);

export const riskAssessmentSchema = z.object({
  id: z.uuid(),
  sprintId: z.uuid(),
  riskType: riskTypeSchema,
  riskScore: z.coerce.number(),
  atRisk: z.boolean(),
  severity: riskSeveritySchema,
  confidence: z.coerce.number().nullish(),
  drivers: z.array(predictionDriverSchema).default([]),
  mitigations: z.array(z.string()).default([]),
  explanation: z.string().nullish(),
  predictionId: z.uuid().nullish(),
  acknowledgedBy: z.uuid().nullish(),
  acknowledgedAt: z.string().nullish(),
  createdAt: z.string(),
});
export type RiskAssessment = z.infer<typeof riskAssessmentSchema>;

export const sprintRiskSchema = z.object({
  sprintId: z.uuid(),
  overBudget: riskAssessmentSchema.nullish(),
  requirementChange: riskAssessmentSchema.nullish(),
  highestSeverity: riskSeveritySchema.nullish(),
});
export type SprintRisk = z.infer<typeof sprintRiskSchema>;

// -------------------------------------------------------------- M3 · allocation

export const allocationCandidateSchema = z.object({
  userId: z.uuid(),
  fullName: z.string().nullish(),
  avatarUrl: z.string().nullish(),
  rankPosition: z.number().int(),
  fitScore: z.coerce.number(),
  predictedPerformance: z.coerce.number().nullish(),
  consistencyScore: z.coerce.number().nullish(),
  learningRate: z.coerce.number().nullish(),
  skillMatchRatio: z.coerce.number().nullish(),
  currentLoadPct: z.coerce.number().nullish(),
  /** Best match and already drowning are different facts; the UI shows both. */
  overloadRisk: z.boolean(),
  reasoning: z.array(predictionDriverSchema).default([]),
  explanation: z.string().nullish(),
});
export type AllocationCandidate = z.infer<typeof allocationCandidateSchema>;

export const allocationRankingSchema = z.object({
  entityType: entityTypeSchema,
  entityId: z.uuid(),
  itemKey: z.string().nullish(),
  currentAssigneeId: z.uuid().nullish(),
  requiredSkills: z.array(z.string()).default([]),
  candidates: z.array(allocationCandidateSchema).default([]),
  predictionId: z.uuid().nullish(),
  createdAt: z.string().nullish(),
});
export type AllocationRanking = z.infer<typeof allocationRankingSchema>;

// -------------------------------------------------------------------- M4 · cost

export const costStageSchema = z.enum(["INITIAL", "MID_SPRINT"]);

export const costForecastSchema = z.object({
  id: z.uuid(),
  sprintId: z.uuid(),
  stage: costStageSchema,
  forecastAmount: z.coerce.number(),
  errorMarginAmount: z.coerce.number().nullish(),
  lowerBound: z.coerce.number().nullish(),
  upperBound: z.coerce.number().nullish(),
  currency: z.string(),
  confidence: z.coerce.number().nullish(),
  trend: z.enum(["UP", "FLAT", "DOWN"]).nullish(),
  varianceVsBudgetPct: z.coerce.number().nullish(),
  budgetAmount: z.coerce.number().nullish(),
  drivers: z.array(predictionDriverSchema).default([]),
  explanation: z.string().nullish(),
  predictionId: z.uuid().nullish(),
  snapshotAt: z.string(),
});
export type CostForecast = z.infer<typeof costForecastSchema>;

export const costTrendPointSchema = z.object({
  sprintId: z.uuid(),
  sprintName: z.string().nullish(),
  stage: costStageSchema,
  forecastAmount: z.coerce.number(),
  lowerBound: z.coerce.number().nullish(),
  upperBound: z.coerce.number().nullish(),
  budgetAmount: z.coerce.number().nullish(),
  snapshotAt: z.string(),
});
export type CostTrendPoint = z.infer<typeof costTrendPointSchema>;

export const costPredictionAccuracySchema = z.object({
  forecastId: z.uuid(),
  stage: costStageSchema,
  predictedCost: z.coerce.number(),
  actualCost: z.coerce.number(),
  errorAmount: z.coerce.number(),
  absoluteError: z.coerce.number(),
  absolutePercentageError: z.coerce.number().nullish(),
  snapshotAt: z.string(),
});
export type CostPredictionAccuracy = z.infer<typeof costPredictionAccuracySchema>;

export const costValidationSchema = z.object({
  sprintId: z.uuid(),
  evaluationStatus: z.enum(["PLANNING", "LIVE_TRACKING", "FINAL", "CANCELLED"]).nullish(),
  accuracyEligible: z.boolean().nullish(),
  actualCost: z.coerce.number(),
  actualCostSoFar: z.coerce.number().nullish(),
  estimatedRemainingCost: z.coerce.number().nullish(),
  projectedFinalCost: z.coerce.number().nullish(),
  latestForecastAmount: z.coerce.number().nullish(),
  liveVariance: z.coerce.number().nullish(),
  mae: z.coerce.number().nullish(),
  rmse: z.coerce.number().nullish(),
  mape: z.coerce.number().nullish(),
  initial: costPredictionAccuracySchema.nullish(),
  midSprint: costPredictionAccuracySchema.nullish(),
  closerStage: costStageSchema.nullish(),
  predictions: z.array(costPredictionAccuracySchema).default([]),
});
export type CostValidation = z.infer<typeof costValidationSchema>;

// -------------------------------------------------------------------- feedback

export const feedbackActionSchema = z.enum(["ACCEPTED", "OVERRIDDEN", "DISMISSED"]);
export type FeedbackAction = z.infer<typeof feedbackActionSchema>;

export const feedbackSchema = z.object({
  id: z.uuid(),
  predictionId: z.uuid(),
  action: feedbackActionSchema,
  predictedValue: z.coerce.number().nullish(),
  finalValue: z.coerce.number().nullish(),
  delta: z.coerce.number().nullish(),
  reason: z.string().nullish(),
  userId: z.uuid(),
  createdAt: z.string(),
});

// -------------------------------------------------------------------- insights

export const estimationCoverageSchema = z.object({
  totalStories: z.number().int(),
  estimated: z.number().int(),
  aiAssisted: z.number().int(),
  coveragePct: z.coerce.number(),
  aiAcceptanceRate: z.coerce.number(),
});

export const aiInsightsSchema = z.object({
  scopeId: z.uuid(),
  scopeType: z.string(),
  scopeName: z.string().nullish(),
  velocity: velocityPredictionSchema.nullish(),
  risk: sprintRiskSchema.nullish(),
  cost: costForecastSchema.nullish(),
  costTrend: z.array(costTrendPointSchema).default([]),
  recentRisks: z.array(riskAssessmentSchema).default([]),
  estimationCoverage: estimationCoverageSchema.nullish(),
  /** Named rather than silently omitted, so the page can say why a panel is missing. */
  disabledCapabilities: z.array(z.string()).default([]),
});
export type AiInsights = z.infer<typeof aiInsightsSchema>;

export const modelUsageSchema = z.object({
  predictions: z.number().int(),
  failures: z.number().int(),
  accepted: z.number().int(),
  overridden: z.number().int(),
  dismissed: z.number().int(),
  acceptanceRate: z.coerce.number().nullish(),
  meanAbsoluteError: z.coerce.number().nullish(),
});

export const aiModelSchema = z.object({
  code: aiModelCodeSchema,
  name: z.string(),
  description: z.string().nullish(),
  version: z.string(),
  taskType: z.string(),
  defaultMode: z.string(),
  enabled: z.boolean(),
  latencyBudgetMs: z.number().int(),
  endpointUrl: z.string().nullish(),
  flagKey: z.string(),
  flagEnabled: z.boolean(),
  usage: modelUsageSchema.nullish(),
});
export type AiModel = z.infer<typeof aiModelSchema>;

// --------------------------------------------------------------- collaboration

export const commentAuthorSchema = z.object({
  id: z.uuid(),
  fullName: z.string(),
  avatarUrl: z.string().nullish(),
});

export const commentSchema: z.ZodType<Comment> = z.lazy(() =>
  z.object({
    id: z.uuid(),
    entityType: entityTypeSchema,
    entityId: z.uuid(),
    parentCommentId: z.uuid().nullish(),
    author: commentAuthorSchema,
    body: z.string(),
    bodyHtml: z.string().nullish(),
    mentions: z.array(commentAuthorSchema).default([]),
    edited: z.boolean(),
    editedAt: z.string().nullish(),
    replies: z.array(commentSchema).default([]),
    canEdit: z.boolean(),
    createdAt: z.string(),
  }),
);

export interface Comment {
  id: string;
  entityType: z.infer<typeof entityTypeSchema>;
  entityId: string;
  parentCommentId?: string | null;
  author: z.infer<typeof commentAuthorSchema>;
  body: string;
  bodyHtml?: string | null;
  mentions: z.infer<typeof commentAuthorSchema>[];
  edited: boolean;
  editedAt?: string | null;
  replies: Comment[];
  canEdit: boolean;
  createdAt: string;
}

export const notificationTypeSchema = z.string();

export const notificationSchema = z.object({
  id: z.uuid(),
  type: notificationTypeSchema,
  title: z.string(),
  body: z.string().nullish(),
  entityType: entityTypeSchema.nullish(),
  entityId: z.uuid().nullish(),
  link: z.string().nullish(),
  priority: z.enum(["LOW", "NORMAL", "HIGH", "URGENT"]),
  actorId: z.uuid().nullish(),
  actorName: z.string().nullish(),
  actorAvatarUrl: z.string().nullish(),
  read: z.boolean(),
  readAt: z.string().nullish(),
  createdAt: z.string(),
});
export type Notification = z.infer<typeof notificationSchema>;

export const unreadCountSchema = z.object({ unread: z.number().int() });

export const activitySchema = z.object({
  id: z.uuid(),
  entityType: z.string(),
  entityId: z.uuid(),
  entityKey: z.string().nullish(),
  projectId: z.uuid().nullish(),
  action: z.string(),
  fieldName: z.string().nullish(),
  oldValue: z.string().nullish(),
  newValue: z.string().nullish(),
  summary: z.string().nullish(),
  actorId: z.uuid().nullish(),
  actorName: z.string().nullish(),
  actorAvatarUrl: z.string().nullish(),
  createdAt: z.string(),
});
export type Activity = z.infer<typeof activitySchema>;
