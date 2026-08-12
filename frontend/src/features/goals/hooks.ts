"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { useSession } from "@/components/session-provider";
import { ApiError } from "@/lib/api/errors";
import { goalsApi, type GoalFilters } from "./api";

const keys = {
  all: ["goals"] as const,
  list: (filters: GoalFilters) => [...keys.all, "list", filters] as const,
  summary: (period?: string) => [...keys.all, "summary", period ?? "all"] as const,
  history: (keyResultId: string) => [...keys.all, "history", keyResultId] as const,
};

export function useGoals(filters: GoalFilters = {}) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: keys.list(filters),
    queryFn: () => goalsApi.list(api, filters),
    // Not merely disabled while loading: firing before the session resolves gives a
    // 401 that starts a refresh, which then races the boot exchange already running.
    enabled: status === "authenticated",
  });
}

export function useGoalSummary(period?: string) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: keys.summary(period),
    queryFn: () => goalsApi.summary(api, period),
    enabled: status === "authenticated",
  });
}

export function useCheckinHistory(keyResultId: string | null) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: keys.history(keyResultId ?? ""),
    queryFn: () => goalsApi.history(api, keyResultId!),
    enabled: status === "authenticated" && !!keyResultId,
  });
}

export function useCreateGoal() {
  const { api } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (body: Parameters<typeof goalsApi.create>[1]) => goalsApi.create(api, body),
    onSuccess: () => {
      // Invalidate the whole subtree: a new goal changes both the list and the
      // summary counts, and naming them separately is how one gets forgotten.
      void queryClient.invalidateQueries({ queryKey: keys.all });
      toast.success("Goal created");
    },
    onError: (error: unknown) => toast.error(message(error)),
  });
}

export function useCheckIn() {
  const { api } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({
      keyResultId,
      ...body
    }: {
      keyResultId: string;
      value: number;
      confidence?: number;
      note?: string;
    }) => goalsApi.checkIn(api, keyResultId, body),
    onSuccess: (checkin) => {
      void queryClient.invalidateQueries({ queryKey: keys.all });
      const delta = checkin.delta ?? 0;
      toast.success(
        delta === 0
          ? "Check-in recorded"
          : `Check-in recorded — moved ${delta > 0 ? "+" : ""}${delta}`,
      );
    },
    onError: (error: unknown) => toast.error(message(error)),
  });
}

export function useDeleteGoal() {
  const { api } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (goalId: string) => goalsApi.remove(api, goalId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: keys.all });
      toast.success("Goal deleted");
    },
    onError: (error: unknown) => toast.error(message(error)),
  });
}

/** The backend's message when it sent one — it explains the rule that was broken. */
function message(error: unknown): string {
  return error instanceof ApiError ? error.message : "Something went wrong. Please try again.";
}
