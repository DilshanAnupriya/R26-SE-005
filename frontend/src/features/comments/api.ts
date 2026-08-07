import { z } from "zod";
import type { ApiClient } from "@/lib/api/client";
import { pageResponseSchema } from "@/lib/api/schemas";
import { commentSchema, type Comment } from "@/lib/api/ai-schemas";

export const commentsApi = {
  list: (api: ApiClient, entityType: string, entityId: string) =>
    api.request("/comments", {
      schema: pageResponseSchema(commentSchema),
      query: { entityType, entityId, size: 50 },
    }),

  count: (api: ApiClient, entityType: string, entityId: string) =>
    api.request("/comments/count", {
      schema: z.object({ entityType: z.string(), entityId: z.uuid(), count: z.number().int() }),
      query: { entityType, entityId },
    }),

  create: (
    api: ApiClient,
    body: { entityType: string; entityId: string; parentCommentId?: string | null; body: string },
  ) => api.request("/comments", { method: "POST", body, schema: commentSchema }) as Promise<Comment>,

  update: (api: ApiClient, commentId: string, body: string) =>
    api.request(`/comments/${commentId}`, {
      method: "PATCH",
      body: { body },
      schema: commentSchema,
    }) as Promise<Comment>,

  remove: (api: ApiClient, commentId: string) =>
    api.request(`/comments/${commentId}`, { method: "DELETE" }),
};

/**
 * Turns `@[Name](uuid)` markup into the plain name a person reads.
 *
 * <p>The id is what the backend notifies on, so it must survive the round trip in the
 * stored body — but it must never be what a human sees. Rendering happens at display
 * time rather than being stored pre-rendered, so a renamed colleague's old mentions show
 * their current name.</p>
 */
export function renderMentions(body: string): { text: string; mentionIds: string[] } {
  const ids: string[] = [];
  const text = body.replace(
    /@\[([^\]]{1,150})]\(([0-9a-fA-F-]{36})\)/g,
    (_match, name: string, id: string) => {
      ids.push(id);
      return `@${name}`;
    },
  );
  return { text, mentionIds: ids };
}

/** Builds the markup the backend parses, from a picked person. */
export function toMentionMarkup(fullName: string, userId: string) {
  return `@[${fullName}](${userId})`;
}
