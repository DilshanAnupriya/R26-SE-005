"use client";

import { use, useState } from "react";
import { MessagesSquare } from "lucide-react";
import { ConversationList } from "@/features/copilot/components/conversation-list";
import { CopilotThread } from "@/features/copilot/components/copilot-thread";
import { useConversations, useDeleteConversation } from "@/features/copilot/hooks";

/**
 * The Copilot: an LLM assistant over the organisation's projects and the four models.
 *
 * <p>The open conversation is local state, not a URL parameter. A conversation is
 * private to its owner, so a shareable link to one would only ever open for the person
 * who already has it.</p>
 */
export default function CopilotPage({ params }: { params: Promise<{ orgSlug: string }> }) {
  use(params);
  const conversations = useConversations();
  const remove = useDeleteConversation();

  // `undefined` means "nothing chosen yet": default to the newest conversation, derived
  // during render. `null` is an explicit "new conversation" and must stay blank.
  const [chosenId, setChosenId] = useState<string | null | undefined>(undefined);
  const list = conversations.data ?? [];
  const activeId = chosenId === undefined ? (list[0]?.id ?? null) : chosenId;

  return (
    <div className="animate-fade-up mx-auto flex h-[calc(100dvh-7rem)] max-w-6xl flex-col gap-4">
      <div>
        <h1 className="flex items-center gap-2 text-2xl font-semibold tracking-tight">
          <MessagesSquare className="size-5 text-primary" aria-hidden />
          Copilot
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Ask about any project, sprint or work item. The Copilot uses the estimation, risk,
          allocation and cost models to answer, and suggests changes you confirm.
        </p>
      </div>

      <div className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-lg border md:flex-row">
        <aside className="max-h-48 shrink-0 border-b md:max-h-none md:w-64 md:border-r md:border-b-0">
          <ConversationList
            conversations={list}
            loading={conversations.isLoading}
            activeId={activeId}
            onSelect={setChosenId}
            onNew={() => setChosenId(null)}
            onDelete={(id) => {
              remove.mutate(id);
              if (id === activeId) setChosenId(null);
            }}
          />
        </aside>
        <section className="min-h-0 min-w-0 flex-1" aria-label="Conversation">
          <CopilotThread
            conversationId={activeId}
            onConversationCreated={setChosenId}
          />
        </section>
      </div>
    </div>
  );
}
