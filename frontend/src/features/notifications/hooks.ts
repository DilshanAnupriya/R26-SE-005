"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useSession } from "@/components/session-provider";
import { queryKeys } from "@/lib/api/query-keys";
import { notificationsApi } from "./api";

export function useNotifications(filters: { unreadOnly?: boolean; type?: string } = {}) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.notifications.list(filters),
    queryFn: () => notificationsApi.list(api, filters),
    enabled: status === "authenticated",
  });
}

/**
 * The bell badge.
 *
 * <p>No polling interval. The WebSocket pushes new notifications and invalidates this
 * key, so a timer would be a second, worse source of the same information — and one that
 * keeps a tab hitting the API all day whether anything happened or not.</p>
 */
export function useUnreadCount() {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.notifications.unreadCount(),
    queryFn: () => notificationsApi.unreadCount(api),
    enabled: status === "authenticated",
    staleTime: 60_000,
  });
}

export function useMarkNotificationRead() {
  const { api } = useSession();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (notificationId: string) => notificationsApi.markRead(api, notificationId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.notifications.all });
    },
  });
}

export function useMarkAllRead() {
  const { api } = useSession();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => notificationsApi.markAllRead(api),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.notifications.all });
    },
  });
}

export function useNotificationPreferences() {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.notifications.preferences(),
    queryFn: () => notificationsApi.preferences(api),
    enabled: status === "authenticated",
  });
}

export function useUpdateNotificationPreferences() {
  const { api } = useSession();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (preferences: Parameters<typeof notificationsApi.updatePreferences>[1]) =>
      notificationsApi.updatePreferences(api, preferences),
    onSuccess: (updated) => {
      queryClient.setQueryData(queryKeys.notifications.preferences(), updated);
    },
  });
}
