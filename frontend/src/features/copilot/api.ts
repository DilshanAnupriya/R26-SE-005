import { z } from "zod";
import type { ApiClient } from "@/lib/api/client";
import {
  conversationDetailSchema,
  conversationSummarySchema,
  copilotStatusSchema,
  proposalSchema,
  sendMessageResponseSchema,
} from "@/lib/api/copilot-schemas";

export const copilotApi = {
  status: (api: ApiClient) =>
    api.request("/copilot/status", { method: "GET", schema: copilotStatusSchema }),

  conversations: (api: ApiClient) =>
    api.request("/copilot/conversations", {
      method: "GET",
      schema: z.array(conversationSummarySchema),
    }),

  conversation: (api: ApiClient, id: string) =>
    api.request(`/copilot/conversations/${id}`, {
      method: "GET",
      schema: conversationDetailSchema,
    }),

  create: (api: ApiClient) =>
    api.request("/copilot/conversations", { method: "POST", schema: conversationSummarySchema }),

  remove: (api: ApiClient, id: string) =>
    api.request(`/copilot/conversations/${id}`, { method: "DELETE" }),

  send: (api: ApiClient, id: string, content: string) =>
    api.request(`/copilot/conversations/${id}/messages`, {
      method: "POST",
      body: { content },
      schema: sendMessageResponseSchema,
    }),

  confirm: (api: ApiClient, proposalId: string) =>
    api.request(`/copilot/proposals/${proposalId}/confirm`, {
      method: "POST",
      schema: proposalSchema,
    }),

  reject: (api: ApiClient, proposalId: string) =>
    api.request(`/copilot/proposals/${proposalId}/reject`, {
      method: "POST",
      schema: proposalSchema,
    }),
};
