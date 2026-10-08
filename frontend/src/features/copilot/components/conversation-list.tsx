"use client";

import { MessageSquarePlus, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import type { ConversationSummary } from "@/lib/api/copilot-schemas";
import { cn } from "@/lib/utils";

/** The user's saved conversations, newest first. */
export function ConversationList({
  conversations,
  loading,
  activeId,
  onSelect,
  onNew,
  onDelete,
}: {
  conversations: ConversationSummary[];
  loading: boolean;
  activeId: string | null;
  onSelect: (id: string) => void;
  onNew: () => void;
  onDelete: (id: string) => void;
}) {
  return (
    <nav className="flex h-full min-h-0 flex-col" aria-label="Copilot conversations">
      <div className="p-3">
        <Button variant="outline" className="w-full justify-start" onClick={onNew}>
          <MessageSquarePlus className="size-4" aria-hidden />
          New conversation
        </Button>
      </div>
      <ul className="min-h-0 flex-1 space-y-0.5 overflow-y-auto px-2 pb-3">
        {loading
          ? Array.from({ length: 4 }, (_, i) => (
              <li key={i}>
                <Skeleton className="h-8 w-full" />
              </li>
            ))
          : null}
        {!loading && conversations.length === 0 ? (
          <li className="px-2 py-1 text-xs text-muted-foreground">No conversations yet.</li>
        ) : null}
        {conversations.map((conversation) => (
          <li key={conversation.id} className="group relative">
            <button
              type="button"
              onClick={() => onSelect(conversation.id)}
              aria-current={conversation.id === activeId ? "page" : undefined}
              className={cn(
                "w-full truncate rounded-md py-1.5 pr-8 pl-2 text-left text-sm transition-colors",
                "hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none",
                conversation.id === activeId && "bg-muted font-medium",
              )}
            >
              {conversation.title}
            </button>
            <button
              type="button"
              onClick={() => onDelete(conversation.id)}
              aria-label={`Delete conversation “${conversation.title}”`}
              className="absolute top-1/2 right-1 -translate-y-1/2 rounded p-1 text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100 hover:text-destructive focus-visible:opacity-100 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
            >
              <Trash2 className="size-3.5" aria-hidden />
            </button>
          </li>
        ))}
      </ul>
    </nav>
  );
}
