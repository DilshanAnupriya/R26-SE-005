import { z } from "zod";
import type { ApiClient } from "@/lib/api/client";
import { pageResponseSchema } from "@/lib/api/schemas";

/**
 * Zod mirrors of the goals contract.
 *
 * <p>Numbers arrive as JSON numbers from Jackson's BigDecimal serialisation, so they
 * are parsed as numbers rather than strings. Precision is not a concern here — these
 * are percentages and counts, not money.</p>
 */
export const goalStatusSchema = z.enum([
  "DRAFT",
  "ON_TRACK",
  "AT_RISK",
  "OFF_TRACK",
  "ACHIEVED",
  "MISSED",
]);
export type GoalStatus = z.infer<typeof goalStatusSchema>;

export const goalLevelSchema = z.enum(["ORGANIZATION", "WORKSPACE", "TEAM", "INDIVIDUAL"]);
export type GoalLevel = z.infer<typeof goalLevelSchema>;

export const metricTypeSchema = z.enum(["NUMBER", "PERCENT", "CURRENCY", "BOOLEAN"]);
export type MetricType = z.infer<typeof metricTypeSchema>;

export const keyResultSchema = z.object({
  id: z.uuid(),
  goalId: z.uuid(),
  title: z.string(),
  metricType: metricTypeSchema,
  unit: z.string().nullish(),
  startValue: z.number(),
  currentValue: z.number(),
  targetValue: z.number(),
  direction: z.enum(["INCREASE", "DECREASE"]),
  status: goalStatusSchema,
  progressPct: z.number(),
  achieved: z.boolean(),
  ownerUserId: z.uuid().nullish(),
  ownerName: z.string().nullish(),
  position: z.number(),
  linkedItems: z
    .array(
      z.object({
        id: z.uuid(),
        entityType: z.string(),
        entityId: z.uuid(),
        itemKey: z.string().nullish(),
        title: z.string().nullish(),
      }),
    )
    .default([]),
  updatedAt: z.string().nullish(),
});
export type KeyResult = z.infer<typeof keyResultSchema>;

export const goalSchema = z.object({
  id: z.uuid(),
  workspaceId: z.uuid().nullish(),
  projectId: z.uuid().nullish(),
  parentGoalId: z.uuid().nullish(),
  title: z.string(),
  description: z.string().nullish(),
  period: z.string().nullish(),
  startDate: z.string().nullish(),
  targetDate: z.string().nullish(),
  status: goalStatusSchema,
  level: goalLevelSchema,
  ownerUserId: z.uuid().nullish(),
  ownerName: z.string().nullish(),
  ownerAvatarUrl: z.string().nullish(),
  progressPct: z.number(),
  overdue: z.boolean(),
  daysRemaining: z.number().nullish(),
  keyResults: z.array(keyResultSchema).default([]),
  createdAt: z.string().nullish(),
  updatedAt: z.string().nullish(),
});
export type Goal = z.infer<typeof goalSchema>;

export const goalSummarySchema = z.object({
  period: z.string().nullish(),
  total: z.number(),
  onTrack: z.number(),
  atRisk: z.number(),
  offTrack: z.number(),
  achieved: z.number(),
  averageProgress: z.number(),
  availablePeriods: z.array(z.string()).default([]),
});
export type GoalSummary = z.infer<typeof goalSummarySchema>;

export const checkinSchema = z.object({
  id: z.uuid(),
  keyResultId: z.uuid(),
  value: z.number(),
  previousValue: z.number().nullish(),
  delta: z.number().nullish(),
  confidence: z.number().nullish(),
  note: z.string().nullish(),
  recordedBy: z.uuid().nullish(),
  recordedByName: z.string().nullish(),
  recordedAt: z.string(),
});
export type Checkin = z.infer<typeof checkinSchema>;

const goalPageSchema = pageResponseSchema(goalSchema);

export interface GoalFilters {
  period?: string;
  status?: GoalStatus;
  level?: GoalLevel;
  ownerId?: string;
  q?: string;
}

export const goalsApi = {
  list: (api: ApiClient, filters: GoalFilters = {}) =>
    api.get("/goals", { schema: goalPageSchema, query: { ...filters, size: 100 } }),

  summary: (api: ApiClient, period?: string) =>
    api.get("/goals/summary", { schema: goalSummarySchema, query: { period } }),

  get: (api: ApiClient, goalId: string) =>
    api.get(`/goals/${goalId}`, { schema: goalSchema }),

  create: (
    api: ApiClient,
    body: {
      title: string;
      description?: string;
      period?: string;
      startDate?: string;
      targetDate?: string;
      level?: GoalLevel;
      keyResults?: {
        title: string;
        metricType?: MetricType;
        unit?: string;
        startValue?: number;
        targetValue: number;
      }[];
    },
  ) => api.post("/goals", body, { schema: goalSchema }),

  checkIn: (
    api: ApiClient,
    keyResultId: string,
    body: { value: number; confidence?: number; note?: string },
  ) => api.post(`/goals/key-results/${keyResultId}/checkins`, body, { schema: checkinSchema }),

  history: (api: ApiClient, keyResultId: string) =>
    api.get(`/goals/key-results/${keyResultId}/checkins`, { schema: z.array(checkinSchema) }),

  remove: (api: ApiClient, goalId: string) => api.delete(`/goals/${goalId}`),
};

/** Maps a status onto the palette's semantic tone. */
export function statusTone(status: GoalStatus): "green" | "amber" | "red" | "neutral" | "accent" {
  switch (status) {
    case "ACHIEVED":
      return "green";
    case "ON_TRACK":
      return "accent";
    case "AT_RISK":
      return "amber";
    case "OFF_TRACK":
    case "MISSED":
      return "red";
    default:
      return "neutral";
  }
}

export function statusColour(status: GoalStatus): string {
  switch (status) {
    case "ACHIEVED":
      return "var(--green)";
    case "ON_TRACK":
      return "var(--accent)";
    case "AT_RISK":
      return "var(--amber)";
    case "OFF_TRACK":
    case "MISSED":
      return "var(--red)";
    default:
      return "var(--ink-3)";
  }
}

export function statusLabel(status: GoalStatus): string {
  return status
    .toLowerCase()
    .split("_")
    .join(" ")
    .replace(/^./, (c) => c.toUpperCase());
}

/**
 * Renders a key result's value the way its metric type reads.
 *
 * <p>A percent wants a trailing sign, currency a leading one, and a boolean is
 * "Done"/"Not yet" rather than 1 and 0 — showing "1 of 1" for a shipped thing is
 * the kind of detail that makes a tool feel like it was built by someone who had
 * not used one.</p>
 */
export function formatMetric(value: number, metricType: MetricType, unit?: string | null): string {
  switch (metricType) {
    case "PERCENT":
      return `${trim(value)}%`;
    case "CURRENCY":
      return `${trim(value)}${unit ? ` ${unit}` : ""}`;
    case "BOOLEAN":
      return value >= 1 ? "Done" : "Not yet";
    default:
      return unit ? `${trim(value)} ${unit}` : trim(value);
  }
}

function trim(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(1);
}
