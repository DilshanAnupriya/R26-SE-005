import { z } from "zod";
import type { ApiClient } from "@/lib/api/client";
import { pageResponseSchema } from "@/lib/api/schemas";
import { entityTypeSchema } from "@/lib/api/delivery-schemas";

export const auditLogSchema = z.object({
  id: z.uuid(),
  actorUserId: z.uuid().nullish(),
  actorEmail: z.string().nullish(),
  action: z.string(),
  entityType: z.string().nullish(),
  entityId: z.uuid().nullish(),
  outcome: z.enum(["SUCCESS", "FAILURE", "DENIED"]),
  ipAddress: z.string().nullish(),
  correlationId: z.string().nullish(),
  createdAt: z.string(),
});
export type AuditLog = z.infer<typeof auditLogSchema>;

export const recycleBinEntrySchema = z.object({
  entityType: entityTypeSchema,
  entityId: z.uuid(),
  itemKey: z.string().nullish(),
  title: z.string().nullish(),
  projectId: z.uuid().nullish(),
  deletedBy: z.uuid().nullish(),
  deletedByName: z.string().nullish(),
  deletedAt: z.string().nullish(),
  /** When it stops being recoverable — the half of "deleted 12 days ago" that matters. */
  purgeAfter: z.string().nullish(),
  restorable: z.boolean(),
  blockedReason: z.string().nullish(),
});
export type RecycleBinEntry = z.infer<typeof recycleBinEntrySchema>;

export const featureFlagSchema = z.object({
  flagKey: z.string(),
  label: z.string(),
  enabled: z.boolean(),
  /** True when this workspace has its own row, rather than inheriting the org default. */
  overridden: z.boolean(),
  config: z.record(z.string(), z.unknown()).default({}),
});
export type FeatureFlag = z.infer<typeof featureFlagSchema>;

export const adminApi = {
  auditLogs: (
    api: ApiClient,
    filters: { action?: string; entityType?: string; actorId?: string; page?: number } = {},
  ) =>
    api.request("/audit-logs", {
      schema: pageResponseSchema(auditLogSchema),
      query: { ...filters, size: 30 },
    }),

  recycleBinTypes: (api: ApiClient) =>
    api.request("/admin/recycle-bin/types", { schema: z.array(entityTypeSchema) }),

  recycleBin: (api: ApiClient, entityType: string) =>
    api.request("/admin/recycle-bin", {
      schema: pageResponseSchema(recycleBinEntrySchema),
      query: { entityType, size: 50 },
    }),

  restore: (api: ApiClient, entityType: string, entityId: string) =>
    api.request(`/admin/recycle-bin/${entityType}/${entityId}/restore`, { method: "POST" }),

  featureFlags: (api: ApiClient, workspaceId: string) =>
    api.request(`/workspaces/${workspaceId}/feature-flags`, { schema: z.array(featureFlagSchema) }),

  setFeatureFlag: (api: ApiClient, workspaceId: string, flagKey: string, enabled: boolean) =>
    api.request(`/workspaces/${workspaceId}/feature-flags`, {
      method: "PATCH",
      body: { flagKey, enabled },
      schema: featureFlagSchema,
    }),

  workspaces: (api: ApiClient) =>
    api.request("/workspaces", {
      schema: z.array(z.object({ id: z.uuid(), name: z.string(), slug: z.string().nullish() })),
    }),
};
