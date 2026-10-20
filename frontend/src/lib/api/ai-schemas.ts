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
  "M2_COMMUNICATION_COLLABORATION",
  "M3_ALLOCATION",
  "M4_COST_INITIAL",
  "M4_COST_MID",
]);
export type AiModelCode = z.infer<typeof aiModelCodeSchema>;

/**
 * A remedy the model measured for one driver raising its score, read off SHAP.
 *
 * <p>Sent by the over-budget model only. Snake-case because it is the model service's
 * own object, passed through the backend untouched.</p>
 */
export const shapFixSchema = z.object({
  method: z.string(),
  /**
   * `direct`: move the reading itself until its SHAP value stops being positive.
   * `offset`: the reading is history, so move a controllable one by enough to cancel it.
   * `none`: nothing within reach helps. `unsupplied`: the reading was a stand-in value.
   */
  kind: z.string(),
  complete: z.boolean().nullish(),
  summary: z.string(),
  shap_now: z.coerce.number().nullish(),
  shap_after: z.coerce.number().nullish(),
  removed: z.coerce.number().nullish(),
  cancels: z.coerce.number().nullish(),
  probability_now: z.coerce.number().nullish(),
  probability_after: z.coerce.number().nullish(),
  move: z
    .object({
      feature: z.string(),
      label: z.string().nullish(),
      from: z.union([z.number(), z.string()]).nullish(),
      to: z.union([z.number(), z.string()]).nullish(),
    })
    .nullish(),
});
export type ShapFix = z.infer<typeof shapFixSchema>;

export const predictionDriverSchema = z.object({
  feature: z.string(),
  label: z.string().nullish(),
  contribution: z.coerce.number(),
  direction: z.string(),
  detail: z.string().nullish(),
  fix: shapFixSchema.nullish(),
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
  /**
   * The model's number before it was snapped to the Fibonacci scale.
   *
   * Everything between 6.5 and 10.5 comes back as an 8, so two stories the model rated
   * 6.8 and 9.9 look identical on screen. This is what tells them apart.
   */
  rawValue: z.coerce.number().nullish(),
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

/**
 * The project context an estimate was scored against.
 *
 * <p>All four of these were being sent to the model and none of them were ever shown,
 * so a panel listing four drivers about the story's own words read as though the team
 * doing the work were not part of the estimate. It is — it just never ranks high enough
 * in a per-story breakdown to surface as a driver, because it is the one input that is
 * identical for every story in the project.</p>
 */
export const sizingContextSchema = z.object({
  teamSize: z.coerce.number().nullish(),
  devExperienceLevel: z.string().nullish(),
  domain: z.string().nullish(),
  techStack: z.string().nullish(),
  /**
   * Model-side names of the inputs that fell back to a training median. Not derivable
   * from the four fields above: a tech stack the model was never trained on is a
   * filled-in field that scores as a blank one, and only the model service knows it.
   */
  defaulted: z.array(z.string()).default([]),
  inferredFromText: z.array(z.string()).default([]),
  textTermsRecognised: z.number().int().nullish(),
});
export type SizingContext = z.infer<typeof sizingContextSchema>;

export const storyPointEstimateSchema = z.object({
  storyId: z.uuid(),
  itemKey: z.string().nullish(),
  title: z.string().nullish(),
  currentPoints: z.coerce.number().nullish(),
  suggestedPoints: z.coerce.number().nullish(),
  prediction: predictionEnvelopeSchema.nullish(),
  context: sizingContextSchema.nullish(),
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

export const riskTypeSchema = z.enum([
  "OVER_BUDGET",
  "REQUIREMENT_CHANGE",
  "COMMUNICATION_COLLABORATION",
]);
export type RiskType = z.infer<typeof riskTypeSchema>;

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
  /**
   * Points the board has added since this scoring, on a 0-100 scale — unlike
   * `riskScore`, which is a 0-1 probability. The mismatch is deliberate on the server:
   * the ledger is written and read by people ("scope added, +8").
   */
  adjustmentDelta: z.coerce.number().default(0),
  /** `riskScore` with the adjustment folded in, on the same 0-1 scale. */
  adjustedScore: z.coerce.number().nullish(),
  /** `adjustedScore` banded — what the badge should say. */
  adjustedSeverity: riskSeveritySchema.nullish(),
});
export type RiskAssessment = z.infer<typeof riskAssessmentSchema>;

/** One entry in the ledger behind an adjusted score. */
export const riskAdjustmentSchema = z.object({
  id: z.uuid(),
  sprintId: z.uuid(),
  riskType: riskTypeSchema,
  delta: z.coerce.number(),
  reason: z.string(),
  createdAt: z.string(),
  /** Names the task this came from, when something specific produced it. */
  sourceKey: z.string().nullish(),
  /** The sourceKey's prefix — what kind of problem this is, without parsing ids. */
  code: z.string().nullish(),
  /**
   * Whether fixing the underlying thing withdraws this entry. Only standing conditions
   * do; a logged event is history and stays. It decides whether the fix flow can
   * honestly promise the number will move.
   */
  resolvable: z.boolean().default(false),
  /**
   * Whether re-scoring absorbs this entry or it keeps counting.
   *
   * <p>Board state — a blocked task, a reopen — is folded into the model's own figure by
   * the next assessment. A logged requirement change is in no feature vector, so it
   * stands. Shown, because "this one survives Assess" is the difference between a number
   * somebody trusts and one they think reverted.</p>
   */
  survivesScoring: z.boolean().default(false),
});
export type RiskAdjustment = z.infer<typeof riskAdjustmentSchema>;

/**
 * One concrete move against one driver of one risk.
 *
 * <p>Not stored advice. The server re-runs the model with this one reading moved and
 * reads the new probability back, so `targetValue` is the value the model rewards and
 * `points` is the measured fall between `baselineScore` and `projectedScore`, on the
 * ledger's 0-100 scale. Recorded as a negative adjustment, which is why the gauge drops
 * when it is ticked.</p>
 */
export const riskRemediationStepSchema = z.object({
  riskType: riskTypeSchema,
  driverFeature: z.string(),
  driverLabel: z.string().nullish(),
  /** The driver's machine name — sent back to toggle it. The points are the server's to decide. */
  stepKey: z.string(),
  title: z.string(),
  detail: z.string(),
  points: z.coerce.number(),
  /** The reading as it stands. */
  currentValue: z.string().nullish(),
  /** Where the search found the model scores this sprint lower. */
  targetValue: z.string().nullish(),
  /** The model's probability now, and with only this reading moved. */
  baselineScore: z.coerce.number().nullish(),
  projectedScore: z.coerce.number().nullish(),
  /**
   * How to go about it, in plain words — the one hand-written field here, and one that
   * decides nothing. Null where no method has been written for the reading.
   */
  how: z.string().nullish(),
  done: z.boolean(),
  doneAt: z.string().nullish(),
});
export type RiskRemediationStep = z.infer<typeof riskRemediationStepSchema>;

/**
 * One calendar day of a sprint.
 *
 * <p>Every score is nullable and `hasData` says whether the day was ever scored. The
 * two are not redundant: a chart that drew an unscored day as zero would report "no
 * risk" for every day of a sprint nobody has assessed, which is the most misleading
 * thing this shape could express.</p>
 */
export const dailyRiskPointSchema = z.object({
  date: z.string(),
  overallScore: z.coerce.number().nullish(),
  overallSeverity: riskSeveritySchema.nullish(),
  overBudgetScore: z.coerce.number().nullish(),
  requirementChangeScore: z.coerce.number().nullish(),
  communicationCollaborationScore: z.coerce.number().nullish(),
  hasData: z.boolean(),
  current: z.boolean(),
});
export type DailyRiskPoint = z.infer<typeof dailyRiskPointSchema>;

/** One sprint's column in a side-by-side risk comparison. */
export const sprintRiskComparisonSchema = z.object({
  sprintId: z.uuid(),
  sprintName: z.string().nullish(),
  status: z.string().nullish(),
  startDate: z.string().nullish(),
  endDate: z.string().nullish(),
  overallScore: z.coerce.number().nullish(),
  overallSeverity: riskSeveritySchema.nullish(),
  overBudgetScore: z.coerce.number().nullish(),
  requirementChangeScore: z.coerce.number().nullish(),
  communicationCollaborationScore: z.coerce.number().nullish(),
  committedPoints: z.coerce.number().nullish(),
  completedPoints: z.coerce.number().nullish(),
  blockedCount: z.coerce.number().nullish(),
  reopenedCount: z.coerce.number().nullish(),
  adjustmentDelta: z.coerce.number().default(0),
  /** Null when the sprint has never been assessed — itself worth seeing. */
  assessedAt: z.string().nullish(),
});
export type SprintRiskComparison = z.infer<typeof sprintRiskComparisonSchema>;


export const overallRiskSchema = z.object({
  /** 0–1, on the same scale as each individual model's score. */
  score: z.coerce.number(),
  level: riskSeveritySchema,
  /**
   * The figure rests on incomplete evidence — a model declined to score, or answered
   * outside its latency budget. `level` has already been bumped one band; this is why.
   */
  degraded: z.boolean(),
  /** Which models contributed, at what weight after renormalising. */
  weights: z.record(z.string(), z.coerce.number()).default({}),
  rationale: z.string().nullish(),
});
export type OverallRisk = z.infer<typeof overallRiskSchema>;

export const sprintRiskSchema = z.object({
  sprintId: z.uuid(),
  overBudget: riskAssessmentSchema.nullish(),
  requirementChange: riskAssessmentSchema.nullish(),
  // Null until the sprint has some collaboration to read — a comment, a blocker, a
  // reopen. Three of that model's five readings are healthiest at zero, so an
  // untouched sprint would otherwise be scored as a well-coordinated one.
  communicationCollaboration: riskAssessmentSchema.nullish(),
  highestSeverity: riskSeveritySchema.nullish(),
  // The three weighted into one figure. Null when none of them scored — which is a
  // different statement from zero, and the panel says so rather than showing a dial
  // at the calm end for a sprint nobody could assess.
  overall: overallRiskSchema.nullish(),
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

/**
 * The two over-budget verdicts read against each other.
 *
 * `agreement` is the whole point: the cost model and the risk model share no inputs, so
 * agreement is corroboration and disagreement names which half of the sprint went wrong.
 */
export const costRiskCrossCheckSchema = z.object({
  sprintId: z.uuid(),
  agreement: z.enum(["BOTH_HIGH", "BOTH_LOW", "COST_ONLY", "RISK_ONLY", "UNKNOWN"]),
  headline: z.string(),
  detail: z.string(),
  riskScore: z.coerce.number().nullish(),
  riskSeverity: riskSeveritySchema.nullish(),
  forecastAmount: z.coerce.number().nullish(),
  budgetAmount: z.coerce.number().nullish(),
  variancePct: z.coerce.number().nullish(),
  currency: z.string().nullish(),
  /** The cost model's own error rate. A variance inside this is noise, not an overrun. */
  marginPct: z.coerce.number(),
  costCharged: z.boolean(),
});
export type CostRiskCrossCheck = z.infer<typeof costRiskCrossCheckSchema>;
export type CostRiskAgreement = CostRiskCrossCheck["agreement"];

export const costTrendPointSchema = z.object({
  sprintId: z.uuid(),
  sprintName: z.string().nullish(),
  stage: costStageSchema,
  forecastAmount: z.coerce.number(),
  errorMarginAmount: z.coerce.number().nullish(),
  lowerBound: z.coerce.number().nullish(),
  upperBound: z.coerce.number().nullish(),
  budgetAmount: z.coerce.number().nullish(),
  confidence: z.coerce.number().nullish(),
  drivers: z.array(predictionDriverSchema).default([]),
  explanation: z.string().nullish(),
  predictionId: z.uuid().nullish(),
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

/**
 * The advisor's answer, or the honest reason there isn't one.
 *
 * <p>`available: false` is not an error. The advisor is the one model that calls an
 * external provider, so it is off until a key is configured, and a sprint that has
 * never been assessed has nothing to discuss. Both come back as a reason to show
 * rather than a failed request.</p>
 */
export const riskAdvisorSchema = z.object({
  available: z.boolean(),
  /** Markdown. Null when unavailable. */
  report: z.string().nullish(),
  /** Which model wrote it, for the record. */
  model: z.string().nullish(),
  reason: z.string().nullish(),
});
export type RiskAdvisor = z.infer<typeof riskAdvisorSchema>;

/** One turn of an advisor conversation. The server accepts these two roles only. */
export const riskQuestionTurnSchema = z.object({
  role: z.enum(["user", "assistant"]),
  content: z.string(),
});
export type RiskQuestionTurn = z.infer<typeof riskQuestionTurnSchema>;

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
