"use client";

import { createContext, useContext, useEffect, useMemo, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { useSession } from "@/components/session-provider";
import { queryKeys } from "@/lib/api/query-keys";
import { getTokenStore } from "@/lib/api/token-store";
import { env } from "@/lib/env";
import { destinations, RealtimeClient, type RealtimeEvent } from "@/lib/realtime/client";

interface RealtimeContextValue {
  connected: boolean;
  subscribeToProject: (projectId: string, handler: (event: RealtimeEvent) => void) => () => void;
  subscribeToSprint: (sprintId: string, handler: (event: RealtimeEvent) => void) => () => void;
}

const RealtimeContext = createContext<RealtimeContextValue | null>(null);

/**
 * Owns the one STOMP connection and turns server events into cache invalidations.
 *
 * <p>Invalidation rather than patching the cache with the frame's payload. A push says
 * *that* something changed; making it also say *what it now is* would mean every
 * publisher has to send a complete, correctly-shaped entity, and any drift between that
 * shape and the REST one shows up as a board that renders differently depending on
 * whether you dragged the card or reloaded the page.</p>
 *
 * <p>Personal notifications are the exception: they are additive, self-contained, and
 * the badge should move the instant one arrives.</p>
 */
export function RealtimeProvider({ children }: { children: React.ReactNode }) {
  const { status, activeOrganizationId } = useSession();
  const queryClient = useQueryClient();
  const [connected, setConnected] = useState(false);

  const tokenStore = useMemo(() => getTokenStore(), []);
  const clientRef = useRef<RealtimeClient | null>(null);

  useEffect(() => {
    if (status !== "authenticated" || !activeOrganizationId) {
      return;
    }

    const client = new RealtimeClient({
      apiUrl: env.NEXT_PUBLIC_API_URL,
      getAccessToken: () => tokenStore.get(),
      onConnect: () => setConnected(true),
      onDisconnect: () => setConnected(false),
    });
    clientRef.current = client;
    client.activate();

    const stopNotifications = client.subscribe(destinations.notifications(), (event) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.notifications.all });

      const payload = event.data as { title?: string; body?: string } | null;
      if (event.event === "notification.created" && payload?.title) {
        toast(payload.title, { description: payload.body ?? undefined });
      }
    });

    return () => {
      stopNotifications();
      client.deactivate();
      clientRef.current = null;
      setConnected(false);
    };
    // Rebuilt on organization switch: the destinations are tenant-prefixed, and the
    // server refuses a subscription whose org segment is not the caller's.
  }, [status, activeOrganizationId, queryClient, tokenStore]);

  const value = useMemo<RealtimeContextValue>(
    () => ({
      connected,

      subscribeToProject: (projectId, handler) => {
        const client = clientRef.current;
        if (!client || !activeOrganizationId) return () => {};
        return client.subscribe(destinations.project(activeOrganizationId, projectId), (event) => {
          invalidateForProject(queryClient, projectId, event);
          handler(event);
        });
      },

      subscribeToSprint: (sprintId, handler) => {
        const client = clientRef.current;
        if (!client || !activeOrganizationId) return () => {};
        return client.subscribe(destinations.sprint(activeOrganizationId, sprintId), (event) => {
          void queryClient.invalidateQueries({ queryKey: queryKeys.sprints.board(sprintId) });
          void queryClient.invalidateQueries({ queryKey: queryKeys.sprints.detail(sprintId) });
          handler(event);
        });
      },
    }),
    [connected, activeOrganizationId, queryClient],
  );

  return <RealtimeContext.Provider value={value}>{children}</RealtimeContext.Provider>;
}

/**
 * Maps an event to the keys it makes stale.
 *
 * <p>Narrow on purpose. Invalidating everything under `projects` on any event would
 * refetch the overview, the member list and the workflow states every time someone moves
 * a card — turning a realtime feature into a load generator.</p>
 */
function invalidateForProject(
  queryClient: ReturnType<typeof useQueryClient>,
  projectId: string,
  event: RealtimeEvent,
) {
  switch (event.event) {
    case "task.assigned":
    case "task.moved":
    case "task.updated":
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.board(projectId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.tasks.workload(projectId) });
      break;
    case "story.updated":
    case "story.moved":
      void queryClient.invalidateQueries({ queryKey: queryKeys.backlog.all });
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.overview(projectId) });
      break;
    case "comment.created":
    case "comment.deleted": {
      const payload = event.data as { entityType?: string; entityId?: string } | null;
      if (payload?.entityType && payload.entityId) {
        void queryClient.invalidateQueries({
          queryKey: queryKeys.comments.list(payload.entityType, payload.entityId),
        });
        void queryClient.invalidateQueries({
          queryKey: queryKeys.comments.count(payload.entityType, payload.entityId),
        });
      }
      break;
    }
    default:
      // An event this build does not know about is a newer backend, not an error.
      break;
  }
}

export function useRealtime(): RealtimeContextValue {
  const context = useContext(RealtimeContext);
  if (!context) {
    throw new Error("useRealtime() must be used inside <RealtimeProvider>");
  }
  return context;
}

/** Subscribes to a project's events for as long as the component is mounted. */
export function useProjectRealtime(projectId: string | undefined) {
  const { subscribeToProject } = useRealtime();

  useEffect(() => {
    if (!projectId) return;
    return subscribeToProject(projectId, () => {});
  }, [projectId, subscribeToProject]);
}
