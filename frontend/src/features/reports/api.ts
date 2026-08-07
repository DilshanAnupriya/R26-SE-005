import { z } from "zod";
import type { ApiClient } from "@/lib/api/client";
import { pageResponseSchema } from "@/lib/api/schemas";

const velocityPointSchema = z.object({
  sprintId: z.uuid(),
  sprintName: z.string().nullish(),
  sequenceNo: z.number().int(),
  committedPoints: z.coerce.number(),
  completedPoints: z.coerce.number(),
  addedPoints: z.coerce.number(),
  removedPoints: z.coerce.number(),
  completionRate: z.coerce.number().nullish(),
  endDate: z.string().nullish(),
});

export const velocityReportSchema = z.object({
  projectId: z.uuid(),
  sprints: z.array(velocityPointSchema).default([]),
  averageVelocity: z.coerce.number(),
  /** How predictable the team is — the number that makes the average worth quoting. */
  velocityStdDev: z.coerce.number(),
  averageCompletionRate: z.coerce.number(),
  unit: z.string(),
});
export type VelocityReport = z.infer<typeof velocityReportSchema>;

const burnPointSchema = z.object({
  date: z.string(),
  ideal: z.coerce.number().nullish(),
  actual: z.coerce.number().nullish(),
  completed: z.coerce.number().nullish(),
  scopeChange: z.coerce.number().nullish(),
});

export const burnReportSchema = z.object({
  sprintId: z.uuid(),
  startDate: z.string().nullish(),
  endDate: z.string().nullish(),
  committedPoints: z.coerce.number(),
  completedPoints: z.coerce.number(),
  points: z.array(burnPointSchema).default([]),
  unit: z.string(),
});
export type BurnReport = z.infer<typeof burnReportSchema>;

export const cfdSchema = z.object({
  projectId: z.uuid(),
  dates: z.array(z.string()).default([]),
  bands: z.array(z.object({ state: z.string(), counts: z.array(z.number()) })).default([]),
});

export const durationDistributionSchema = z.object({
  projectId: z.uuid(),
  metric: z.string(),
  sampleSize: z.number().int(),
  median: z.coerce.number(),
  p85: z.coerce.number(),
  p95: z.coerce.number(),
  average: z.coerce.number(),
  longest: z.coerce.number(),
  samples: z
    .array(
      z.object({
        itemId: z.uuid(),
        itemKey: z.string().nullish(),
        title: z.string().nullish(),
        days: z.coerce.number(),
        completedAt: z.string().nullish(),
      }),
    )
    .default([]),
  unit: z.string(),
});
export type DurationDistribution = z.infer<typeof durationDistributionSchema>;

export const productivitySchema = z.object({
  projectId: z.uuid(),
  sprintId: z.uuid().nullish(),
  rows: z
    .array(
      z.object({
        userId: z.uuid(),
        fullName: z.string().nullish(),
        avatarUrl: z.string().nullish(),
        itemsCompleted: z.number().int(),
        pointsCompleted: z.coerce.number(),
        minutesLogged: z.number().int(),
        averageCycleTimeDays: z.coerce.number().nullish(),
        reopenedCount: z.number().int(),
      }),
    )
    .default([]),
  /** The backend's own caveat about what these numbers are not. Rendered verbatim. */
  note: z.string().nullish(),
});

export const workloadBalanceSchema = z.object({
  projectId: z.uuid(),
  rows: z
    .array(
      z.object({
        userId: z.uuid(),
        fullName: z.string().nullish(),
        avatarUrl: z.string().nullish(),
        openItems: z.number().int(),
        blockedItems: z.number().int(),
        estimatedMinutes: z.number().int(),
        loadPct: z.coerce.number(),
        overAllocated: z.boolean(),
      }),
    )
    .default([]),
  averageLoadPct: z.coerce.number(),
  imbalancePct: z.coerce.number(),
  unassignedItems: z.number().int(),
});

export const exportJobSchema = z.object({
  jobId: z.uuid(),
  report: z.string(),
  format: z.string(),
  status: z.enum(["QUEUED", "RUNNING", "COMPLETED", "FAILED", "EXPIRED"]),
  downloadUrl: z.string().nullish(),
  errorMessage: z.string().nullish(),
  requestedAt: z.string().nullish(),
  completedAt: z.string().nullish(),
});
export type ExportJob = z.infer<typeof exportJobSchema>;

export const reportsApi = {
  velocity: (api: ApiClient, projectId: string) =>
    api.request("/reports/velocity", { schema: velocityReportSchema, query: { projectId } }),

  burndown: (api: ApiClient, sprintId: string) =>
    api.request("/reports/burndown", { schema: burnReportSchema, query: { sprintId } }),

  burnup: (api: ApiClient, sprintId: string) =>
    api.request("/reports/burnup", { schema: burnReportSchema, query: { sprintId } }),

  cfd: (api: ApiClient, projectId: string, days = 30) =>
    api.request("/reports/cfd", { schema: cfdSchema, query: { projectId, days } }),

  cycleTime: (api: ApiClient, projectId: string) =>
    api.request("/reports/cycle-time", { schema: durationDistributionSchema, query: { projectId } }),

  leadTime: (api: ApiClient, projectId: string) =>
    api.request("/reports/lead-time", { schema: durationDistributionSchema, query: { projectId } }),

  productivity: (api: ApiClient, projectId: string, sprintId?: string) =>
    api.request("/reports/productivity", { schema: productivitySchema, query: { projectId, sprintId } }),

  workloadBalance: (api: ApiClient, projectId: string) =>
    api.request("/reports/workload-balance", { schema: workloadBalanceSchema, query: { projectId } }),

  // ------------------------------------------------------------------- export

  requestExport: (
    api: ApiClient,
    body: { report: string; projectId?: string | null; sprintId?: string | null },
  ) => api.request("/reports/export", { method: "POST", body: { format: "CSV", ...body }, schema: exportJobSchema }),

  exportStatus: (api: ApiClient, jobId: string) =>
    api.request(`/reports/export/${jobId}`, { schema: exportJobSchema }),

  myExports: (api: ApiClient) =>
    api.request("/reports/export", { schema: pageResponseSchema(exportJobSchema), query: { size: 10 } }),

  /**
   * Downloads a finished export.
   *
   * <p>Through the client, not a link. The endpoint needs a bearer token, and neither
   * `window.open` nor `<a download>` sends one — both would arrive unauthenticated and
   * look to the user like a broken download.</p>
   */
  downloadExport: (api: ApiClient, jobId: string) =>
    api.blob(`/reports/export/${jobId}/download`),
};
