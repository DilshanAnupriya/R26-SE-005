"use client";

import { useQuery } from "@tanstack/react-query";
import { useSession } from "@/components/session-provider";
import { calendarApi } from "./api";

export function useCalendar(month: string, projectId?: string) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: ["calendar", month, projectId ?? "all"],
    queryFn: () => calendarApi.month(api, month, projectId),
    // Not merely disabled while loading: firing before the session resolves gives a
    // 401 that starts a refresh, which then races the boot exchange already running.
    enabled: status === "authenticated",
    // Keeps the previous month on screen while the next one loads, so paging does
    // not flash an empty grid — the layout is identical either way.
    placeholderData: (previous) => previous,
  });
}
