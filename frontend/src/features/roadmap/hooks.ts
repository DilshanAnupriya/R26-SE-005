"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { useSession } from "@/components/session-provider";
import { ApiError } from "@/lib/api/errors";
import { roadmapApi, type RoadmapFilters } from "./api";

const keys = {
  all: ["roadmap"] as const,
  board: (filters: RoadmapFilters) => [...keys.all, "board", filters] as const,
  initiative: (id: string) => [...keys.all, "initiative", id] as const,
};

export function useRoadmap(filters: RoadmapFilters = {}) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: keys.board(filters),
    queryFn: () => roadmapApi.get(api, filters),
    // Not merely disabled while loading: firing before the session resolves gives a
    // 401 that starts a refresh, which then races the boot exchange already running.
    enabled: status === "authenticated",
  });
}

export function useInitiative(initiativeId: string | null) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: keys.initiative(initiativeId ?? ""),
    queryFn: () => roadmapApi.initiative(api, initiativeId!),
    enabled: status === "authenticated" && !!initiativeId,
  });
}

export function useCreateInitiative() {
  const { api } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (body: Parameters<typeof roadmapApi.create>[1]) => roadmapApi.create(api, body),
    onSuccess: () => {
      // The whole subtree: a new initiative changes the lanes, the quarter columns
      // and the header counts, and naming them separately is how one gets forgotten.
      void queryClient.invalidateQueries({ queryKey: keys.all });
      toast.success("Initiative added to the roadmap");
    },
    onError: (error: unknown) => toast.error(message(error)),
  });
}

export function useUpdateInitiative() {
  const { api } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({
      initiativeId,
      ...body
    }: { initiativeId: string } & Parameters<typeof roadmapApi.update>[2]) =>
      roadmapApi.update(api, initiativeId, body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: keys.all });
      toast.success("Initiative updated");
    },
    onError: (error: unknown) => toast.error(message(error)),
  });
}

export function useDeleteInitiative() {
  const { api } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (initiativeId: string) => roadmapApi.remove(api, initiativeId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: keys.all });
      toast.success("Initiative removed");
    },
    onError: (error: unknown) => toast.error(message(error)),
  });
}

/** The backend's message when it sent one — it explains the rule that was broken. */
function message(error: unknown): string {
  return error instanceof ApiError ? error.message : "Something went wrong. Please try again.";
}
