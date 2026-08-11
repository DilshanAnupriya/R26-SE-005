import { z } from "zod";
import type { ApiClient } from "@/lib/api/client";
import { pageResponseSchema } from "@/lib/api/schemas";
import { notificationSchema, unreadCountSchema } from "@/lib/api/ai-schemas";

const preferenceSchema = z.object({
  type: z.string(),
  description: z.string().nullish(),
  inApp: z.boolean(),
  email: z.boolean(),
  digest: z.boolean(),
});
export type NotificationPreference = z.infer<typeof preferenceSchema>;

export const notificationsApi = {
  list: (api: ApiClient, filters: { unreadOnly?: boolean; type?: string; page?: number } = {}) =>
    api.request("/notifications", {
      schema: pageResponseSchema(notificationSchema),
      query: { ...filters, size: 30 },
    }),

  unreadCount: (api: ApiClient) =>
    api.request("/notifications/unread-count", { schema: unreadCountSchema }),

  markRead: (api: ApiClient, notificationId: string) =>
    api.request(`/notifications/${notificationId}/read`, {
      method: "PATCH",
      schema: notificationSchema,
    }),

  markAllRead: (api: ApiClient) =>
    api.request("/notifications/read-all", { method: "POST", schema: unreadCountSchema }),

  preferences: (api: ApiClient) =>
    api.request("/notifications/preferences", { schema: z.array(preferenceSchema) }),

  updatePreferences: (
    api: ApiClient,
    preferences: { type: string; inApp: boolean; email: boolean; digest?: boolean }[],
  ) =>
    api.request("/notifications/preferences", {
      method: "PUT",
      body: { preferences },
      schema: z.array(preferenceSchema),
    }),
};
