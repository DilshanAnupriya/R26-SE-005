"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useSession } from "@/components/session-provider";
import { queryKeys, type ProjectFilters } from "@/lib/api/query-keys";
import { labelsApi, projectsApi, workspacesApi } from "./api";

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

/**
 * Updates a project's estimation context.
 *
 * <p>Invalidates the AI keys as well as the project ones. Domain, tech stack, team size
 * and experience level are all inputs to the story-point model, and a cached estimate
 * produced before they were filled in was scored against training medians — leaving it
 * on screen next to the corrected project would show a number the inputs no longer
 * justify.</p>
 */
export function useUpdateProject(projectId: string | undefined) {
  const { api } = useSession();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: Record<string, unknown>) => projectsApi.update(api, projectId!, body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
      void queryClient.invalidateQueries({ queryKey: queryKeys.ai.all });
    },
  });
}

/**
 * The labels a story in this project can be given.
 *
 * <p>Project-scoped rather than global because the endpoint already merges the two: a
 * project's own labels plus the organization-wide ones, in one list, which is exactly
 * what a picker wants to show.</p>
 */
export function useProjectLabels(projectId: string | undefined) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: queryKeys.projects.labels(projectId ?? ""),
    queryFn: () => labelsApi.listForProject(api, projectId!),
    enabled: status === "authenticated" && Boolean(projectId),
  });
}

/**
 * Creates a label, scoped to the project unless told otherwise.
 *
 * <p>Project-scoped by default on purpose. A label invented mid-thought while writing
 * one story — "payments", "needs-design" — is a note about this project, and pushing it
 * organization-wide would grow every other project's picker by one every time somebody
 * typed a word into this box.</p>
 */
export function useCreateLabel(projectId: string | undefined) {
  const { api } = useSession();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { name: string; color?: string | null }) =>
      labelsApi.create(api, { ...body, projectId }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.labels(projectId ?? "") });
    },
  });
}
