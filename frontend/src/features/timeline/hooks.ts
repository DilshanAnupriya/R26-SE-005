"use client";

import { useQuery } from "@tanstack/react-query";
import { useSession } from "@/components/session-provider";
import { timelineApi } from "./api";

export function useTimeline(projectId: string | undefined) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: ["timeline", projectId],
    queryFn: () => timelineApi.get(api, projectId!),
    // Not merely disabled while loading: firing before the session resolves gives a
    // 401 that starts a refresh, which then races the boot exchange already running.
    enabled: status === "authenticated" && !!projectId,
  });
}
