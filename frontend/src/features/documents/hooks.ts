"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { useSession } from "@/components/session-provider";
import { ApiError } from "@/lib/api/errors";
import { documentsApi } from "./api";

const keys = {
  all: ["documents"] as const,
  tree: (projectId?: string) => [...keys.all, "tree", projectId ?? "all"] as const,
  doc: (id: string) => [...keys.all, "doc", id] as const,
  history: (id: string) => [...keys.all, "history", id] as const,
};

export function useDocumentTree(projectId?: string) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: keys.tree(projectId),
    queryFn: () => documentsApi.tree(api, projectId),
    // Not merely disabled while loading: firing before the session resolves gives a
    // 401 that starts a refresh, which then races the boot exchange already running.
    enabled: status === "authenticated",
  });
}

export function useDocument(documentId: string | null) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: keys.doc(documentId ?? ""),
    queryFn: () => documentsApi.get(api, documentId!),
    enabled: status === "authenticated" && !!documentId,
  });
}

export function useDocumentHistory(documentId: string | null) {
  const { api, status } = useSession();
  return useQuery({
    queryKey: keys.history(documentId ?? ""),
    queryFn: () => documentsApi.history(api, documentId!),
    enabled: status === "authenticated" && !!documentId,
  });
}

export function useCreateDocument() {
  const { api } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (body: Parameters<typeof documentsApi.create>[1]) =>
      documentsApi.create(api, body),
    onSuccess: (created) => {
      void queryClient.invalidateQueries({ queryKey: keys.all });
      toast.success(created.kind === "FOLDER" ? "Folder created" : "Page created");
    },
    onError: (error: unknown) => toast.error(message(error)),
  });
}

export function useUpdateDocument() {
  const { api } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({
      documentId,
      ...body
    }: { documentId: string } & Parameters<typeof documentsApi.update>[2]) =>
      documentsApi.update(api, documentId, body),
    onSuccess: (updated) => {
      // The whole subtree: an edit changes the page, its history and the tree label.
      void queryClient.invalidateQueries({ queryKey: keys.all });
      toast.success(`Saved as version ${updated.versionNo}`);
    },
    onError: (error: unknown) => toast.error(message(error)),
  });
}

export function useRestoreVersion() {
  const { api } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ documentId, versionNo }: { documentId: string; versionNo: number }) =>
      documentsApi.restore(api, documentId, versionNo),
    onSuccess: (restored, variables) => {
      void queryClient.invalidateQueries({ queryKey: keys.all });
      toast.success(
        `Version ${variables.versionNo} restored as version ${restored.versionNo}`,
      );
    },
    onError: (error: unknown) => toast.error(message(error)),
  });
}

export function useDeleteDocument() {
  const { api } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (documentId: string) => documentsApi.remove(api, documentId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: keys.all });
      toast.success("Deleted");
    },
    onError: (error: unknown) => toast.error(message(error)),
  });
}

/** The backend's message when it sent one — it explains the rule that was broken. */
function message(error: unknown): string {
  return error instanceof ApiError ? error.message : "Something went wrong. Please try again.";
}
