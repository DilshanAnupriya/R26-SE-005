"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { MessageSquare, Send, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";
import { useSession } from "@/components/session-provider";
import { ApiError } from "@/lib/api/errors";
import { queryKeys } from "@/lib/api/query-keys";
import type { Comment } from "@/lib/api/ai-schemas";
import { commentsApi, renderMentions, toMentionMarkup } from "../api";

function initials(name?: string | null) {
  if (!name) return "?";
  return name.split(/\s+/).slice(0, 2).map((part) => part[0]?.toUpperCase() ?? "").join("");
}

function relativeTime(iso: string) {
  const then = new Date(iso).getTime();
  const seconds = Math.round((then - Date.now()) / 1000);
  const units: [Intl.RelativeTimeFormatUnit, number][] = [
    ["second", 60],
    ["minute", 60],
    ["hour", 24],
    ["day", 7],
    ["week", 4.35],
    ["month", 12],
    ["year", Number.POSITIVE_INFINITY],
  ];

  let value = seconds;
  for (const [unit, size] of units) {
    if (Math.abs(value) < size) {
      return new Intl.RelativeTimeFormat(undefined, { numeric: "auto" }).format(
        Math.round(value),
        unit,
      );
    }
    value /= size;
  }
  return new Date(iso).toLocaleDateString();
}

/**
 * A comment thread on any work item.
 *
 * <p>Mentions are picked, never typed free-hand. Typing `@priya` would leave the client
 * guessing which Priya, and a wrong guess sends someone's work notification to a
 * stranger — so the composer inserts `@[Name](id)` markup and the display layer renders
 * it back to a plain name.</p>
 */
export function CommentThread({
  entityType,
  entityId,
  people = [],
  onChanged,
}: {
  entityType: string;
  entityId: string;
  /** Candidates for the mention picker — usually the project's members. */
  people?: { id: string; fullName: string; avatarUrl?: string | null }[];
  /**
   * Called after a comment is posted or removed, for callers holding a count of their
   * own. This component invalidates its own thread and count; it cannot know that a
   * kanban card three components away is also showing the number.
   */
  onChanged?: () => void;
}) {
  const { api, user } = useSession();
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState("");
  const [replyTo, setReplyTo] = useState<string | null>(null);

  const thread = useQuery({
    queryKey: queryKeys.comments.list(entityType, entityId),
    queryFn: () => commentsApi.list(api, entityType, entityId),
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.comments.list(entityType, entityId) });
    void queryClient.invalidateQueries({ queryKey: queryKeys.comments.count(entityType, entityId) });
    onChanged?.();
  };

  const post = useMutation({
    mutationFn: (body: string) =>
      commentsApi.create(api, { entityType, entityId, parentCommentId: replyTo, body }),
    onSuccess: () => {
      setDraft("");
      setReplyTo(null);
      invalidate();
    },
    onError: (error) => {
      toast.error(error instanceof ApiError ? error.message : "Could not post that comment.");
    },
  });

  const remove = useMutation({
    mutationFn: (commentId: string) => commentsApi.remove(api, commentId),
    onSuccess: invalidate,
    onError: (error) => {
      toast.error(error instanceof ApiError ? error.message : "Could not delete that comment.");
    },
  });

  const comments = thread.data?.content ?? [];

  return (
    <section aria-labelledby={`comments-${entityId}`} className="space-y-3">
      <h3 id={`comments-${entityId}`} className="flex items-center gap-1.5 text-sm font-medium">
        <MessageSquare className="size-4" aria-hidden />
        Comments
        {comments.length > 0 ? (
          <span className="text-xs font-normal text-muted-foreground tabular-nums">
            ({thread.data?.totalElements ?? comments.length})
          </span>
        ) : null}
      </h3>

      {thread.isPending ? (
        <div className="space-y-2">
          <Skeleton className="h-14 w-full" />
          <Skeleton className="h-14 w-full" />
        </div>
      ) : comments.length === 0 ? (
        <p className="text-xs text-muted-foreground">
          No comments yet. Mention someone with @ to bring them in.
        </p>
      ) : (
        <ul className="space-y-3">
          {comments.map((comment) => (
            <li key={comment.id}>
              <CommentItem
                comment={comment}
                currentUserId={user?.id}
                onReply={() => setReplyTo(comment.id)}
                onDelete={() => remove.mutate(comment.id)}
                deleting={remove.isPending}
              />
            </li>
          ))}
        </ul>
      )}

      <form
        className="space-y-2"
        onSubmit={(event) => {
          event.preventDefault();
          const body = draft.trim();
          if (!body) return;
          post.mutate(body);
        }}
      >
        {replyTo ? (
          <p className="flex items-center justify-between text-xs text-muted-foreground">
            <span>Replying in thread</span>
            <Button type="button" size="sm" variant="ghost" onClick={() => setReplyTo(null)}>
              Cancel
            </Button>
          </p>
        ) : null}

        <Textarea
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          placeholder="Add a comment…"
          rows={3}
          aria-label="New comment"
          onKeyDown={(event) => {
            // Ctrl/Cmd+Enter submits. Plain Enter inserts a newline, because a comment
            // is prose and losing a half-written paragraph to a stray keypress is worse
            // than one extra modifier.
            if ((event.metaKey || event.ctrlKey) && event.key === "Enter") {
              event.preventDefault();
              const body = draft.trim();
              if (body) post.mutate(body);
            }
          }}
        />

        <div className="flex flex-wrap items-center justify-between gap-2">
          {people.length > 0 ? (
            <MentionPicker
              people={people}
              onPick={(person) =>
                setDraft((current) =>
                  `${current}${current && !current.endsWith(" ") ? " " : ""}${toMentionMarkup(person.fullName, person.id)} `,
                )
              }
            />
          ) : (
            <span />
          )}

          <Button type="submit" size="sm" disabled={post.isPending || !draft.trim()}>
            <Send className="size-3.5" aria-hidden />
            {post.isPending ? "Posting…" : "Comment"}
          </Button>
        </div>
      </form>
    </section>
  );
}

function MentionPicker({
  people,
  onPick,
}: {
  people: { id: string; fullName: string; avatarUrl?: string | null }[];
  onPick: (person: { id: string; fullName: string }) => void;
}) {
  return (
    <label className="text-xs">
      <span className="sr-only">Mention someone</span>
      <select
        className="h-8 rounded-md border bg-background px-2 text-xs focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
        value=""
        onChange={(event) => {
          const person = people.find((candidate) => candidate.id === event.target.value);
          if (person) onPick(person);
        }}
      >
        <option value="">Mention…</option>
        {people.map((person) => (
          <option key={person.id} value={person.id}>
            {person.fullName}
          </option>
        ))}
      </select>
    </label>
  );
}

function CommentItem({
  comment,
  currentUserId,
  onReply,
  onDelete,
  deleting,
  nested,
}: {
  comment: Comment;
  currentUserId?: string;
  onReply?: () => void;
  onDelete?: () => void;
  deleting?: boolean;
  nested?: boolean;
}) {
  const { text } = renderMentions(comment.body);
  const mine = comment.author.id === currentUserId;

  return (
    <article className={nested ? "pl-8" : undefined}>
      <div className="flex items-start gap-2.5">
        <Avatar className="size-7 shrink-0">
          <AvatarImage src={comment.author.avatarUrl ?? undefined} alt="" />
          <AvatarFallback className="text-[11px]">
            {initials(comment.author.fullName)}
          </AvatarFallback>
        </Avatar>

        <div className="min-w-0 flex-1">
          <p className="flex flex-wrap items-baseline gap-1.5">
            <span className="text-sm font-medium">{comment.author.fullName}</span>
            <time
              dateTime={comment.createdAt}
              className="text-[11px] text-muted-foreground"
              title={new Date(comment.createdAt).toLocaleString()}
            >
              {relativeTime(comment.createdAt)}
            </time>
            {comment.edited ? (
              <span className="text-[11px] text-muted-foreground">(edited)</span>
            ) : null}
          </p>

          <p className="mt-0.5 text-sm whitespace-pre-wrap break-words">{text}</p>

          <div className="mt-1 flex gap-1">
            {onReply ? (
              <Button type="button" size="sm" variant="ghost" className="h-6 px-1.5 text-xs" onClick={onReply}>
                Reply
              </Button>
            ) : null}
            {(mine || comment.canEdit) && onDelete ? (
              <Button
                type="button"
                size="sm"
                variant="ghost"
                className="h-6 px-1.5 text-xs"
                disabled={deleting}
                onClick={onDelete}
              >
                <Trash2 className="size-3" aria-hidden />
                <span className="sr-only">Delete comment</span>
              </Button>
            ) : null}
          </div>
        </div>
      </div>

      {comment.replies.length > 0 ? (
        <ul className="mt-2 space-y-2 border-l pl-3">
          {comment.replies.map((reply) => (
            <li key={reply.id}>
              <CommentItem comment={reply} currentUserId={currentUserId} nested />
            </li>
          ))}
        </ul>
      ) : null}
    </article>
  );
}
