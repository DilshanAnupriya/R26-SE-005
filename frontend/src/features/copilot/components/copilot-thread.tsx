"use client";

import { useEffect, useRef, useState } from "react";
import { Bot, CornerDownLeft, Info, Loader2, ShieldCheck, Sparkles, User } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";
import { ApiError } from "@/lib/api/errors";
import type { CopilotMessage } from "@/lib/api/copilot-schemas";
import { cn } from "@/lib/utils";
import {
  useConfirmProposal,
  useConversation,
  useCopilotStatus,
  useCreateConversation,
  useRejectProposal,
  useSendMessage,
} from "../hooks";
import { MessageBody } from "./message-body";
import { ProposalCard } from "./proposal-card";
import { ToolSteps } from "./tool-steps";

/**
 * Openers that exercise the four models, so the first use shows what the Copilot is
 * for rather than starting from a blank box.
 */
const SUGGESTIONS = [
  "Which sprint is most at risk right now, and why?",
  "Estimate the unestimated stories in the active sprint",
  "Who should pick up the next unassigned task?",
  "Will the current sprint come in on budget?",
] as const;

const MAX_CHARS = 4000;

/**
 * One Copilot conversation: the thread, proposal cards and the composer.
 *
 * <p>With no conversation open, the first question creates one and is sent to it in
 * the same gesture — asking "where do I start a chat?" before being allowed to ask
 * anything would be the wrong first impression.</p>
 */
export function CopilotThread({
  conversationId,
  onConversationCreated,
}: {
  conversationId: string | null;
  onConversationCreated: (id: string) => void;
}) {
  const status = useCopilotStatus();
  const conversation = useConversation(conversationId);
  const create = useCreateConversation();
  const send = useSendMessage();
  const confirm = useConfirmProposal(conversationId ?? "");
  const reject = useRejectProposal(conversationId ?? "");

  const [draft, setDraft] = useState("");
  const [lastQuestion, setLastQuestion] = useState<string | null>(null);
  const threadRef = useRef<HTMLDivElement>(null);

  const messages: CopilotMessage[] = conversation.data?.messages ?? [];
  // The thread stays mounted across conversation switches, so the send state is only
  // this conversation's when the last send was addressed to it.
  const sentHere = send.variables?.conversationId === conversationId;
  const thinking = send.isPending && sentHere;
  const failed = send.isError && sentHere;
  const busy = send.isPending || create.isPending;

  useEffect(() => {
    const thread = threadRef.current;
    // Optional-called: `scrollTo` is absent in jsdom.
    thread?.scrollTo?.({ top: thread.scrollHeight, behavior: "smooth" });
  }, [messages.length, thinking]);

  async function submit(text: string) {
    const content = text.trim();
    if (!content || busy) return;

    let id = conversationId;
    if (!id) {
      const created = await create.mutateAsync();
      id = created.id;
      onConversationCreated(id);
    }
    setDraft("");
    setLastQuestion(content);
    send.mutate({ conversationId: id, content });
  }

  if (status.isLoading) {
    return (
      <div className="space-y-3 p-6">
        <Skeleton className="h-6 w-2/3" />
        <Skeleton className="h-20 w-full" />
      </div>
    );
  }

  const enabled = status.data?.enabled ?? false;

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div
        ref={threadRef}
        className="min-h-0 flex-1 space-y-4 overflow-y-auto px-4 py-4 sm:px-6"
        role="log"
        aria-live="polite"
        aria-label="Copilot conversation"
      >
        {messages.length === 0 && !thinking && !create.isPending ? (
          <div className="mx-auto max-w-xl space-y-4 py-8 text-center">
            <Sparkles className="mx-auto size-8 text-primary" aria-hidden />
            <div className="space-y-1">
              <h2 className="text-lg font-semibold">Ask about your projects</h2>
              <p className="text-sm text-muted-foreground">
                The Copilot looks things up across your projects, sprints and work items, and
                asks the estimation, risk, allocation and cost models when a question needs
                them. It can suggest changes; nothing happens until you confirm.
              </p>
            </div>
            {enabled ? (
              <div className="grid gap-2 sm:grid-cols-2">
                {SUGGESTIONS.map((suggestion) => (
                  <Button
                    key={suggestion}
                    variant="outline"
                    className="h-auto justify-start py-2 text-left text-sm font-normal whitespace-normal"
                    onClick={() => void submit(suggestion)}
                  >
                    {suggestion}
                  </Button>
                ))}
              </div>
            ) : null}
          </div>
        ) : null}

        {messages.map((message) => (
          <MessageRow
            key={message.id}
            message={message}
            onConfirm={(id) => confirm.mutate(id)}
            onReject={(id) => reject.mutate(id)}
            busyProposalId={
              confirm.isPending ? confirm.variables : reject.isPending ? reject.variables : undefined
            }
          />
        ))}

        {thinking ? (
          <p className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="size-4 animate-spin" aria-hidden />
            Looking things up — this can take a little while when several models are involved…
          </p>
        ) : null}

        {failed ? (
          <div className="flex items-start gap-2 rounded-md border border-dashed px-3 py-2 text-sm text-muted-foreground">
            <Info className="mt-0.5 size-4 shrink-0" aria-hidden />
            <div className="space-y-1">
              <p>
                Your question was saved, but the Copilot could not answer it.{" "}
                {send.error instanceof ApiError ? send.error.message : null}
              </p>
              {lastQuestion && enabled ? (
                <Button size="sm" variant="outline" onClick={() => void submit(lastQuestion)}>
                  Try again
                </Button>
              ) : null}
            </div>
          </div>
        ) : null}
      </div>

      <div className="border-t p-3 sm:px-6">
        {enabled ? (
          <>
            <div className="flex items-end gap-2">
              <Textarea
                value={draft}
                onChange={(event) => setDraft(event.target.value.slice(0, MAX_CHARS))}
                onKeyDown={(event) => {
                  // Enter sends, Shift+Enter breaks the line; an IME commit must not send.
                  if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
                    event.preventDefault();
                    void submit(draft);
                  }
                }}
                placeholder="Ask about a sprint, a story, a person's workload, the budget…"
                rows={2}
                className="max-h-40 min-h-11 resize-none text-sm"
                aria-label="Message the Copilot"
                disabled={busy}
              />
              <Button onClick={() => void submit(draft)} disabled={busy || draft.trim().length === 0}>
                <CornerDownLeft className="size-4" aria-hidden />
                Send
              </Button>
            </div>
            <p className="mt-1.5 flex items-center gap-1 text-[11px] text-muted-foreground">
              <ShieldCheck className="size-3" aria-hidden />
              Sees only what you can see. Figures it looks up are sent to{" "}
              {status.data?.model ?? "the configured AI provider"}.
            </p>
          </>
        ) : (
          <p className="flex items-start gap-2 rounded-md border border-dashed px-3 py-2 text-sm text-muted-foreground">
            <Info className="mt-0.5 size-4 shrink-0" aria-hidden />
            <span>{status.data?.reason ?? "The Copilot is not available."}</span>
          </p>
        )}
      </div>
    </div>
  );
}

function MessageRow({
  message,
  onConfirm,
  onReject,
  busyProposalId,
}: {
  message: CopilotMessage;
  onConfirm: (id: string) => void;
  onReject: (id: string) => void;
  busyProposalId: string | undefined;
}) {
  const mine = message.role === "USER";
  return (
    <div className={cn("flex gap-2.5", mine && "justify-end")}>
      {!mine ? <Bot className="mt-1 size-5 shrink-0 text-primary" aria-hidden /> : null}
      <div className={cn("min-w-0 space-y-2", mine ? "max-w-[85%]" : "max-w-[90%] flex-1")}>
        <div
          className={cn(
            "rounded-lg px-3.5 py-2.5 text-sm leading-relaxed",
            mine ? "bg-primary/10" : "bg-muted",
          )}
        >
          {mine ? <p className="whitespace-pre-wrap">{message.content}</p> : <MessageBody content={message.content} />}
        </div>
        {!mine ? <ToolSteps steps={message.toolSteps} /> : null}
        {message.proposals.map((proposal) => (
          <ProposalCard
            key={proposal.id}
            proposal={proposal}
            onConfirm={onConfirm}
            onReject={onReject}
            busy={busyProposalId === proposal.id}
          />
        ))}
      </div>
      {mine ? <User className="mt-1 size-5 shrink-0 text-muted-foreground" aria-hidden /> : null}
    </div>
  );
}
