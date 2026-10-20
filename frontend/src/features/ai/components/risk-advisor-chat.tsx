"use client";

import { useEffect, useRef, useState } from "react";
import { Bot, CornerDownLeft, Info, Loader2, User } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { useAskRiskAdvisor } from "../hooks";
import type { RiskQuestionTurn, SprintRisk } from "@/lib/api/ai-schemas";
import { cn } from "@/lib/utils";

/**
 * Openers, so the box is not a blank prompt.
 *
 * <p>Each is answerable from what the server sends — the sprint's brief or the guide to
 * SprintIQ. A suggestion the advisor has to decline teaches the reader the feature is
 * broken on their first use of it.</p>
 */
const OPENERS = [
  "Why is the overall risk what it is?",
  "Which finding should we clear first?",
  "What can SprintIQ's four AI models do?",
  "How do I plan and start a sprint?",
] as const;

/**
 * Questions about one sprint's risk, answered by the advisor model.
 *
 * <p>The sprint's figures are assembled server-side and re-sent on every question, so
 * this component holds only the conversation. It deliberately cannot send anything
 * about the sprint: what the model is told is the server's decision, and a client that
 * could contribute to the brief could contradict the numbers on the page.</p>
 *
 * <p>Nothing here is persisted. The thread is a reading aid for one sitting, and
 * storing it would mean keeping the provider's answers about a sprint whose figures
 * have since moved on.</p>
 */
export function RiskAdvisorChat({ risk }: { risk: SprintRisk }) {
  const [turns, setTurns] = useState<RiskQuestionTurn[]>([]);
  const [draft, setDraft] = useState("");
  // Held apart from the thread: "no API key is configured" is a fact about the
  // deployment, not something the advisor said, and showing it as a chat bubble
  // invites the reader to argue with it.
  const [notice, setNotice] = useState<string | null>(null);
  const [model, setModel] = useState<string | null>(null);

  const ask = useAskRiskAdvisor(risk.sprintId);
  const threadRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const thread = threadRef.current;
    // Optional-called: smooth scrolling is a nicety, and `scrollTo` is absent in jsdom.
    // Letting it throw would take the whole panel down to keep the view pinned.
    thread?.scrollTo?.({ top: thread.scrollHeight, behavior: "smooth" });
  }, [turns.length, ask.isPending]);

  function send(question: string) {
    const trimmed = question.trim();
    if (!trimmed || ask.isPending) {
      return;
    }

    // The history sent is the thread *before* this question, which is what the server
    // replays. Appending first and sending `turns` afterwards would send the question
    // twice and let the model answer its own echo.
    const history = turns;
    setTurns([...history, { role: "user", content: trimmed }]);
    setDraft("");
    setNotice(null);

    ask.mutate(
      { question: trimmed, history },
      {
        onSuccess: (answer) => {
          if (answer.available && answer.report) {
            setTurns((current) => [
              ...current,
              { role: "assistant", content: answer.report! },
            ]);
            setModel(answer.model ?? null);
          } else {
            // The question stays on screen. It is still a reasonable thing to have
            // asked, and clearing it would look like the app lost the message.
            setNotice(answer.reason ?? "The advisor had nothing to say.");
          }
        },
      },
    );
  }

  return (
    <section className="rounded-lg border" aria-labelledby="advisor-heading">
      <div className="flex items-center gap-2 border-b px-4 py-3">
        <Bot className="size-4 text-primary" aria-hidden />
        <div className="min-w-0">
          <h2 id="advisor-heading" className="text-sm font-medium">
            Ask the AI Assistant
          </h2>
          <p className="text-xs text-muted-foreground">
            About this sprint&apos;s risk and cost, or anything in SprintIQ
            {model ? ` · ${model}` : ""}
          </p>
        </div>
      </div>

      <div
        ref={threadRef}
        className="max-h-96 space-y-3 overflow-y-auto px-4 py-3"
        role="log"
        aria-live="polite"
        aria-label="Advisor conversation"
      >
        {turns.length === 0 && !notice ? (
          <div className="space-y-2 py-1">
            <p className="text-xs text-muted-foreground">
              Ask about the figures above, or how any part of SprintIQ works. For other
              sprints, tasks or projects it will point you to the page that has them
              rather than guess.
            </p>
            <div className="flex flex-wrap gap-1.5">
              {OPENERS.map((opener) => (
                <Button
                  key={opener}
                  variant="outline"
                  size="sm"
                  className="h-auto py-1 text-xs font-normal"
                  onClick={() => send(opener)}
                >
                  {opener}
                </Button>
              ))}
            </div>
          </div>
        ) : null}

        {turns.map((turn, index) => (
          <div
            key={`${turn.role}-${index}`}
            className={cn("flex gap-2", turn.role === "user" && "justify-end")}
          >
            {turn.role === "assistant" ? (
              <Bot className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden />
            ) : null}
            <p
              className={cn(
                "max-w-[85%] rounded-lg px-3 py-2 text-xs leading-relaxed whitespace-pre-wrap",
                turn.role === "user"
                  ? "bg-primary/10 text-foreground"
                  : "bg-muted text-foreground",
              )}
            >
              {turn.content}
            </p>
            {turn.role === "user" ? (
              <User className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden />
            ) : null}
          </div>
        ))}

        {ask.isPending ? (
          <p className="flex items-center gap-2 text-xs text-muted-foreground">
            <Loader2 className="size-3.5 animate-spin" aria-hidden />
            Thinking…
          </p>
        ) : null}

        {notice ? (
          <p className="flex items-start gap-2 rounded-md border border-dashed px-3 py-2 text-xs text-muted-foreground">
            <Info className="mt-0.5 size-3.5 shrink-0" aria-hidden />
            <span>{notice}</span>
          </p>
        ) : null}
      </div>

      <div className="border-t p-3">
        <div className="flex items-end gap-2">
          <Textarea
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={(event) => {
              // Enter sends, Shift+Enter breaks the line. Composing guards an IME:
              // committing a candidate with Enter must not post a half-typed question.
              if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
                event.preventDefault();
                send(draft);
              }
            }}
            placeholder="Ask about this sprint's risk, or anything about SprintIQ…"
            rows={1}
            className="max-h-32 min-h-9 resize-none text-sm"
            aria-label="Your question for the AI Assistant"
            disabled={ask.isPending}
          />
          <Button
            size="sm"
            onClick={() => send(draft)}
            disabled={ask.isPending || draft.trim().length === 0}
          >
            <CornerDownLeft className="size-3.5" aria-hidden />
            Ask
          </Button>
        </div>
        <p className="mt-1.5 text-[11px] text-muted-foreground">
          This sends the sprint&apos;s risk and cost summary to an external provider.
        </p>
      </div>
    </section>
  );
}
