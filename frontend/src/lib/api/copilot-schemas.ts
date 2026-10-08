import { z } from "zod";

/** Mirrors `CopilotDtos` on the server. */

export const copilotStatusSchema = z.object({
  enabled: z.boolean(),
  model: z.string().nullish(),
  /** Why it is off, worded for the person reading it. Null when enabled. */
  reason: z.string().nullish(),
});
export type CopilotStatus = z.infer<typeof copilotStatusSchema>;

export const conversationSummarySchema = z.object({
  id: z.uuid(),
  title: z.string(),
  createdAt: z.string().nullish(),
  updatedAt: z.string().nullish(),
});
export type ConversationSummary = z.infer<typeof conversationSummarySchema>;

export const proposalStatusSchema = z.enum([
  "PENDING",
  "CONFIRMED",
  "REJECTED",
  "FAILED",
  "EXPIRED",
]);
export type ProposalStatus = z.infer<typeof proposalStatusSchema>;

export const proposalTypeSchema = z.enum(["ACCEPT_ESTIMATE", "ASSIGN_TASK", "MOVE_TO_SPRINT"]);
export type ProposalType = z.infer<typeof proposalTypeSchema>;

export const proposalSchema = z.object({
  id: z.uuid(),
  conversationId: z.uuid(),
  messageId: z.uuid().nullish(),
  type: proposalTypeSchema,
  /** Written by the server from real data. This is what Confirm does. */
  summary: z.string(),
  /** The assistant's own words. Shown, but never as the description of the action. */
  reason: z.string().nullish(),
  status: proposalStatusSchema,
  expiresAt: z.string().nullish(),
  resolvedAt: z.string().nullish(),
  resultMessage: z.string().nullish(),
  payload: z.record(z.string(), z.unknown()).default({}),
});
export type Proposal = z.infer<typeof proposalSchema>;

export const toolStepSchema = z.object({
  tool: z.string(),
  ok: z.boolean(),
  summary: z.string().nullish(),
});
export type ToolStep = z.infer<typeof toolStepSchema>;

export const copilotMessageSchema = z.object({
  id: z.uuid(),
  role: z.enum(["USER", "ASSISTANT"]),
  content: z.string(),
  model: z.string().nullish(),
  toolSteps: z.array(toolStepSchema).default([]),
  proposals: z.array(proposalSchema).default([]),
  createdAt: z.string().nullish(),
});
export type CopilotMessage = z.infer<typeof copilotMessageSchema>;

export const conversationDetailSchema = z.object({
  id: z.uuid(),
  title: z.string(),
  messages: z.array(copilotMessageSchema).default([]),
  createdAt: z.string().nullish(),
  updatedAt: z.string().nullish(),
});
export type ConversationDetail = z.infer<typeof conversationDetailSchema>;

export const sendMessageResponseSchema = z.object({
  conversation: conversationSummarySchema,
  userMessage: copilotMessageSchema,
  assistantMessage: copilotMessageSchema,
});
export type SendMessageResponse = z.infer<typeof sendMessageResponseSchema>;
