"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useSession } from "@/components/session-provider";
import { queryKeys, type ProjectFilters } from "@/lib/api/query-keys";
import { projectsApi, workspacesApi } from "./api";

/**
 * Query hooks for projects.
 *
 * <p>Each takes the client from the session rather than importing one, so a query
 * automatically re-runs against the right tenant after an organization switch — the
 * client's header builder reads the active organization on every request.</p>
 */

export function useProjects(filters: ProjectFilters = {}) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.projects.list(filters),
    queryFn: () => projectsApi.list(api, filters as Record<string, string | number | undefined>),
    // Not merely disabled while loading: firing before the session resolves produces a
    // 401 that triggers a refresh, which then races the boot exchange already in flight.
    enabled: status === "authenticated",
  });
}

export function useProject(projectId: string | undefined) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.projects.detail(projectId ?? ""),
    queryFn: () => projectsApi.get(api, projectId!),
    enabled: status === "authenticated" && Boolean(projectId),
  });
}

export function useProjectOverview(projectId: string | undefined) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.projects.overview(projectId ?? ""),
    queryFn: () => projectsApi.overview(api, projectId!),
    enabled: status === "authenticated" && Boolean(projectId),
  });
}

export function useWorkflowStates(projectId: string | undefined) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.projects.workflowStates(projectId ?? ""),
    queryFn: () => projectsApi.workflowStates(api, projectId!),
    // Board columns change when someone edits the workflow, which is rare and
    // deliberate — no reason to refetch them alongside every card move.
    staleTime: 5 * 60_000,
    enabled: status === "authenticated" && Boolean(projectId),
  });
}

export function useProjectMembers(projectId: string | undefined) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.projects.members(projectId ?? ""),
    queryFn: () => projectsApi.members(api, projectId!),
    enabled: status === "authenticated" && Boolean(projectId),
  });
}

export function useWorkspaces() {
  const { api, status } = useSession();
  return useQuery({
    queryKey: ["workspaces"],
    queryFn: () => workspacesApi.list(api),
    enabled: status === "authenticated",
  });
}

export function useCreateProject() {
  const { api } = useSession();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: Parameters<typeof projectsApi.create>[1]) => projectsApi.create(api, body),
    onSuccess: () => {
      // The prefix invalidation catches every filtered list, not just the one the
      // creator happened to be looking at.
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
    },
  });
}
