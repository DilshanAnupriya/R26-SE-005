import { z } from "zod";
import type { ApiClient } from "@/lib/api/client";

/** Zod mirrors of the roadmap contract. */
export const initiativeStatusSchema = z.enum([
  "PLANNED",
  "IN_PROGRESS",
  "AT_RISK",
  "SHIPPED",
  "CANCELLED",
]);
export type InitiativeStatus = z.infer<typeof initiativeStatusSchema>;

export const confidenceSchema = z.enum(["LOW", "MEDIUM", "HIGH"]);
export type Confidence = z.infer<typeof confidenceSchema>;

export const initiativeItemSchema = z.object({
  id: z.uuid(),
  entityType: z.string(),
  entityId: z.uuid(),
  itemKey: z.string().nullish(),
  title: z.string().nullish(),
  exists: z.boolean(),
  totalItems: z.number(),
  completedItems: z.number(),
  progressPct: z.number(),
});
export type InitiativeItem = z.infer<typeof initiativeItemSchema>;

export const initiativeSchema = z.object({
  id: z.uuid(),
  projectId: z.uuid().nullish(),
  projectKey: z.string().nullish(),
  projectName: z.string().nullish(),
  title: z.string(),
  description: z.string().nullish(),
  startDate: z.string().nullish(),
  targetDate: z.string().nullish(),
  quarter: z.string().nullish(),
  status: initiativeStatusSchema,
  confidence: confidenceSchema,
  ownerUserId: z.uuid().nullish(),
  ownerName: z.string().nullish(),
  progressPct: z.number(),
  expectedProgressPct: z.number().nullish(),
  overdue: z.boolean(),
  daysRemaining: z.number().nullish(),
  position: z.number(),
  items: z.array(initiativeItemSchema).default([]),
  createdAt: z.string().nullish(),
  updatedAt: z.string().nullish(),
});
export type Initiative = z.infer<typeof initiativeSchema>;

export const roadmapLaneSchema = z.object({
  projectId: z.uuid().nullish(),
  projectKey: z.string(),
  name: z.string(),
  initiatives: z.array(initiativeSchema).default([]),
});
export type RoadmapLane = z.infer<typeof roadmapLaneSchema>;

export const roadmapSchema = z.object({
  quarters: z.array(z.string()).default([]),
  lanes: z.array(roadmapLaneSchema).default([]),
  totalInitiatives: z.number(),
  atRisk: z.number(),
  shipped: z.number(),
});
export type Roadmap = z.infer<typeof roadmapSchema>;

export interface RoadmapFilters {
  projectId?: string;
  status?: InitiativeStatus;
}

export const roadmapApi = {
  get: (api: ApiClient, filters: RoadmapFilters = {}) =>
    api.get("/roadmap", {
      schema: roadmapSchema,
      query: { projectId: filters.projectId, status: filters.status },
    }),

  initiative: (api: ApiClient, initiativeId: string) =>
    api.get(`/roadmap/initiatives/${initiativeId}`, { schema: initiativeSchema }),

  create: (
    api: ApiClient,
    body: {
      title: string;
      description?: string;
      projectId?: string;
      startDate?: string;
      targetDate?: string;
      confidence?: Confidence;
      items?: { entityType: string; entityId: string }[];
    },
  ) => api.post("/roadmap/initiatives", body, { schema: initiativeSchema }),

  update: (
    api: ApiClient,
    initiativeId: string,
    body: {
      title?: string;
      description?: string;
      startDate?: string;
      targetDate?: string;
      status?: InitiativeStatus;
      confidence?: Confidence;
      position?: number;
    },
  ) => api.patch(`/roadmap/initiatives/${initiativeId}`, body, { schema: initiativeSchema }),

  remove: (api: ApiClient, initiativeId: string) =>
    api.delete(`/roadmap/initiatives/${initiativeId}`),
};

export function statusTone(
  status: InitiativeStatus,
): "green" | "amber" | "red" | "neutral" | "accent" {
  switch (status) {
    case "SHIPPED":
      return "green";
    case "IN_PROGRESS":
      return "accent";
    case "AT_RISK":
      return "red";
    default:
      return "neutral";
  }
}

export function statusColour(status: InitiativeStatus): string {
  switch (status) {
    case "SHIPPED":
      return "var(--green)";
    case "IN_PROGRESS":
      return "var(--accent)";
    case "AT_RISK":
      return "var(--red)";
    default:
      return "var(--ink-3)";
  }
}

export function statusLabel(status: InitiativeStatus): string {
  return status
    .toLowerCase()
    .split("_")
    .join(" ")
    .replace(/^./, (c) => c.toUpperCase());
}

/**
 * How an initiative is doing against its own dates, in words.
 *
 * <p>The bar shows how far through the work is; this says whether that is far
 * enough. Without it a reader has to hold the target date in their head and do the
 * arithmetic, which nobody does — they just look at the colour and move on.</p>
 */
export function paceLabel(initiative: Initiative): string | null {
  if (initiative.expectedProgressPct === null || initiative.expectedProgressPct === undefined) {
    return null;
  }
  // Nothing linked means nothing to be behind on. The backend already refuses to call
  // such an initiative at risk; saying "15 behind pace" beside "Planned" would
  // contradict it, and the reader would believe the accusation over the label.
  if (!initiative.items.some((item) => item.exists)) {
    return null;
  }
  const delta = Math.round(initiative.progressPct - initiative.expectedProgressPct);
  if (Math.abs(delta) < 5) return "On pace";
  return delta > 0 ? `${delta} ahead of pace` : `${Math.abs(delta)} behind pace`;
}

/** Human label for a quarter key like "2026-Q3". */
export function quarterLabel(quarter: string): string {
  const [year, q] = quarter.split("-");
  return `${q} ${year}`;
}
