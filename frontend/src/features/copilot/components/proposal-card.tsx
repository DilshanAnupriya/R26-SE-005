"use client";

import { Check, CircleAlert, Clock, Loader2, Wand2, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { Proposal, ProposalStatus } from "@/lib/api/copilot-schemas";
import { cn } from "@/lib/utils";

const STATUS: Record<Exclude<ProposalStatus, "PENDING">, { label: string; icon: typeof Check; tone: string }> = {
  CONFIRMED: { label: "Confirmed", icon: Check, tone: "text-emerald-600 dark:text-emerald-400" },
  REJECTED: { label: "Rejected", icon: X, tone: "text-muted-foreground" },
  FAILED: { label: "Failed", icon: CircleAlert, tone: "text-destructive" },
  EXPIRED: { label: "Expired", icon: Clock, tone: "text-muted-foreground" },
};

/**
 * A change the Copilot suggested, waiting for a person.
 *
 * <p>The headline is the server's summary, built from the real records the change will
 * touch — it is what Confirm does. The assistant's own reasoning sits underneath and is
 * labelled as such, because it is model-written and the two must never be confused.</p>
 */
export function ProposalCard({
  proposal,
  onConfirm,
  onReject,
  busy = false,
}: {
  proposal: Proposal;
  onConfirm: (id: string) => void;
  onReject: (id: string) => void;
  busy?: boolean;
}) {
  const pending = proposal.status === "PENDING";
  const outcome = proposal.status === "PENDING" ? null : STATUS[proposal.status];

  return (
    <div
      className={cn(
        "rounded-lg border px-3 py-2.5 text-sm",
        pending ? "border-dashed border-primary/50 bg-primary/5" : "bg-muted/40",
      )}
      role="group"
      aria-label={outcome ? `Proposed change, ${outcome.label}` : "Proposed change, not yet applied"}
    >
      <div className="flex items-start gap-2">
        <Wand2 className="mt-0.5 size-4 shrink-0 text-primary" aria-hidden />
        <div className="min-w-0 flex-1 space-y-1">
          <p className="font-medium leading-snug">{proposal.summary}</p>
          {proposal.reason ? (
            <p className="text-xs text-muted-foreground">
              <span className="font-medium">Assistant&apos;s reason:</span> {proposal.reason}
            </p>
          ) : null}

          {outcome ? (
            <p className={cn("flex items-center gap-1.5 text-xs", outcome.tone)}>
              <outcome.icon className="size-3.5" aria-hidden />
              <span>{outcome.label}</span>
              {proposal.resultMessage ? (
                <>
                  <span aria-hidden>·</span>
                  <span>{proposal.resultMessage}</span>
                </>
              ) : null}
            </p>
          ) : (
            <div className="flex flex-wrap items-center gap-2 pt-1">
              <Button size="sm" onClick={() => onConfirm(proposal.id)} disabled={busy}>
                {busy ? (
                  <Loader2 className="size-3.5 animate-spin" aria-hidden />
                ) : (
                  <Check className="size-3.5" aria-hidden />
                )}
                Confirm
              </Button>
              <Button size="sm" variant="ghost" onClick={() => onReject(proposal.id)} disabled={busy}>
                <X className="size-3.5" aria-hidden />
                Reject
              </Button>
              <span className="text-[11px] text-muted-foreground">Nothing changes until you confirm.</span>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
