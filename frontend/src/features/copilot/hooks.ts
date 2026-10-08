"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { useSession } from "@/components/session-provider";
import { ApiError } from "@/lib/api/errors";
import { queryKeys } from "@/lib/api/query-keys";
import type { ConversationDetail, Proposal } from "@/lib/api/copilot-schemas";
import { copilotApi } from "./api";

/**
 * Copilot hooks.
 *
 * <p>Sending a message is a mutation, never a query: each turn is a paid call to a third
 * party and must only happen when somebody presses Send.</p>
 */

export function useCopilotStatus() {
  const { api } = useSession();
  return useQuery({
    queryKey: queryKeys.copilot.status(),
    queryFn: () => copilotApi.status(api),
    staleTime: 5 * 60_000,
  });
}

export function useConversations() {
  const { api } = useSession();
  return useQuery({
    queryKey: queryKeys.copilot.conversations(),
    queryFn: () => copilotApi.conversations(api),
  });
}

export function useConversation(id: string | null) {
  const { api } = useSession();
  return useQuery({
    queryKey: queryKeys.copilot.conversation(id ?? "none"),
    queryFn: () => copilotApi.conversation(api, id!),
    enabled: id !== null,
  });
}

export function useCreateConversation() {
  const { api } = useSession();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => copilotApi.create(api),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.copilot.conversations() });
    },
    onError: () => toast.error("Could not start a conversation."),
  });
}

export function useDeleteConversation() {
  const { api } = useSession();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => copilotApi.remove(api, id),
    onSuccess: (_, id) => {
      queryClient.removeQueries({ queryKey: queryKeys.copilot.conversation(id) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.copilot.conversations() });
    },
    onError: () => toast.error("Could not delete the conversation."),
  });
}

/**
 * One turn. The question appears in the thread straight away, before the answer —
 * a turn can take a while when the model calls several tools, and a message that
 * vanishes from the box without appearing anywhere reads as lost.
 */
export function useSendMessage(conversationId: string) {
  const { api } = useSession();
  const queryClient = useQueryClient();
  const key = queryKeys.copilot.conversation(conversationId);

  return useMutation({
    mutationFn: (content: string) => copilotApi.send(api, conversationId, content),
    onMutate: async (content) => {
      await queryClient.cancelQueries({ queryKey: key });
      queryClient.setQueryData<ConversationDetail>(key, (current) =>
        current
          ? {
              ...current,
              messages: [
                ...current.messages,
                {
                  id: `pending-${Date.now()}`,
                  role: "USER",
                  content,
                  toolSteps: [],
                  proposals: [],
                },
              ],
            }
          : current,
      );
    },
    // Refetched either way: on failure the server has still saved the question, and
    // the thread should show exactly what it holds.
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: key });
      void queryClient.invalidateQueries({ queryKey: queryKeys.copilot.conversations() });
    },
  });
}

/** The parts of the app a confirmed proposal may have changed. */
function invalidateAffected(queryClient: ReturnType<typeof useQueryClient>, proposal: Proposal) {
  const keys = {
    ACCEPT_ESTIMATE: [queryKeys.stories.all, queryKeys.backlog.all, queryKeys.ai.all],
    ASSIGN_TASK: [queryKeys.tasks.all, queryKeys.ai.all],
    MOVE_TO_SPRINT: [queryKeys.sprints.all, queryKeys.backlog.all, queryKeys.stories.all],
  }[proposal.type];
  keys.forEach((queryKey) => void queryClient.invalidateQueries({ queryKey }));
}

function useResolveProposal(kind: "confirm" | "reject", conversationId: string) {
  const { api } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (proposalId: string) =>
      kind === "confirm" ? copilotApi.confirm(api, proposalId) : copilotApi.reject(api, proposalId),
    onSuccess: (proposal) => {
      if (kind === "confirm" && proposal.status === "CONFIRMED") {
        invalidateAffected(queryClient, proposal);
        toast.success(proposal.resultMessage ?? "Done.");
      } else if (proposal.status === "FAILED") {
        toast.error(proposal.resultMessage ?? "The change could not be applied.");
      }
    },
    onError: (error) => {
      toast.error(error instanceof ApiError ? error.message : "Something went wrong.");
    },
    onSettled: () => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.copilot.conversation(conversationId),
      });
    },
  });
}

export function useConfirmProposal(conversationId: string) {
  return useResolveProposal("confirm", conversationId);
}

export function useRejectProposal(conversationId: string) {
  return useResolveProposal("reject", conversationId);
}
