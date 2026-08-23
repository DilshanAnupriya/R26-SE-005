"use client";

import { useMemo, useState } from "react";
import { ArrowLeft, ArrowRight, Check, ShieldCheck, Wrench } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { useAssessSprintRisk, useRiskAdjustments } from "../hooks";
import type { RiskAdjustment } from "@/lib/api/ai-schemas";

/**
 * What to actually do about each kind of finding.
 *
 * <p>Keyed on the ledger's own {@code code}, so the guidance and the thing being
 * measured cannot drift apart. Every entry names the action that makes the penalty
 * withdraw itself — these are not generic tips, they are the literal condition the
 * watch re-checks. "Leave a comment" is here because a comment is exactly what
 * {@code TASK_SILENT} looks for.</p>
 */
const GUIDANCE: Record<string, { title: string; action: string; where: string }> = {
  TASK_SILENT: {
    title: "A task nobody has discussed",
    action:
      "Leave a comment on the task — even a one-line status. The penalty is withdrawn "
      + "on the first comment, whoever writes it.",
    where: "Open the task and use the comment box.",
  },
  COMMENT_UNANSWERED: {
    title: "A question left hanging",
    action:
      "Reply to the last comment. Anyone other than the original author counts, and the "
      + "penalty clears as soon as a newer comment exists.",
    where: "Open the task and reply on the thread.",
  },
  TASK_NOT_STARTED_HALF: {
    title: "Work not started at half its estimate",
    action:
      "Either move the task to In Progress, or take it out of the sprint. Both are "
      + "honest; leaving it in TODO while the clock runs is the thing being penalised.",
    where: "Drag the card on the board, or change its status.",
  },
  TASK_NOT_DONE_OVERDUE: {
    title: "Work past its estimate",
    action:
      "Finish it, or re-estimate it. This one charges over-budget risk because the "
      + "sprint's plan no longer matches its reality.",
    where: "Update the task's status or its estimate.",
  },
};

/** Findings the wizard can promise to resolve, worst first. */
function fixable(adjustments: RiskAdjustment[]): RiskAdjustment[] {
  return adjustments
    .filter((entry) => entry.resolvable && entry.delta > 0 && GUIDANCE[entry.code ?? ""])
    .sort((a, b) => b.delta - a.delta);
}

/**
 * Walks a team through the specific things pushing a sprint's risk up.
 *
 * <p>Every other AI surface in this product stops at "here is a number and why". This
 * one continues to "here is what to do about it", and it can only do that honestly
 * because each finding comes from the adjustment ledger: each names one task, and each
 * is withdrawn automatically once its condition stops holding. So the last step is not
 * a claim — it re-runs the assessment and shows the score that resulted.</p>
 *
 * <p>Findings the ledger cannot withdraw are deliberately excluded. Offering to "fix" a
 * logged event that will never clear would teach a user that the wizard's promises are
 * decorative, which would cost more trust than the feature earns.</p>
 */
export function GuidedFixWizard({
  sprintId,
  open,
  onOpenChange,
}: {
  sprintId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const adjustments = useRiskAdjustments(open ? sprintId : undefined);
  const reassess = useAssessSprintRisk();

  const [index, setIndex] = useState(0);
  const [done, setDone] = useState(false);

  const findings = useMemo(
    () => fixable(adjustments.data ?? []),
    [adjustments.data],
  );

  const total = findings.length;
  const finding = findings[index];
  const guidance = finding ? GUIDANCE[finding.code ?? ""] : undefined;
  const isLast = index >= total - 1;

  // The whole point of the last step: don't assert the number moved, re-measure it.
  async function finish() {
    await reassess.mutateAsync(sprintId);
    await adjustments.refetch();
    setDone(true);
  }

  function reset(next: boolean) {
    if (!next) {
      setIndex(0);
      setDone(false);
    }
    onOpenChange(next);
  }

  return (
    <Dialog open={open} onOpenChange={reset}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Wrench className="size-4 text-primary" aria-hidden />
            Fix what is pushing this sprint&rsquo;s risk up
          </DialogTitle>
          <DialogDescription>
            Each of these names one task and withdraws itself once the task recovers, so
            the score moves on its own — nothing here is marked done by hand.
          </DialogDescription>
        </DialogHeader>

        {adjustments.isPending ? (
          <p className="py-8 text-center text-sm text-muted-foreground">Loading findings…</p>
        ) : total === 0 ? (
          <div className="py-8 text-center">
            <ShieldCheck className="mx-auto size-8 text-emerald-600" aria-hidden />
            <p className="mt-2 text-sm font-medium">Nothing to fix</p>
            <p className="mt-1 text-xs text-muted-foreground">
              No standing penalties against this sprint. Its score is the models&rsquo;
              own reading, with nothing added by the board.
            </p>
          </div>
        ) : done ? (
          <div className="py-8 text-center">
            <Check className="mx-auto size-8 text-emerald-600" aria-hidden />
            <p className="mt-2 text-sm font-medium">Re-assessed</p>
            <p className="mt-1 text-xs text-muted-foreground">
              {findings.length === 0
                ? "Every penalty has cleared."
                : `${findings.length} finding${findings.length === 1 ? "" : "s"} still standing — `
                  + "the fixes may not have been made yet."}
            </p>
          </div>
        ) : (
          <div className="space-y-3">
            {/* Progress: which of the findings this is, and what it costs. */}
            <div className="flex items-center justify-between gap-2">
              <span className="text-xs text-muted-foreground">
                Finding {index + 1} of {total}
              </span>
              <Badge variant="outline" className="tabular-nums">
                +{finding.delta} risk
              </Badge>
            </div>

            <div className="h-1 overflow-hidden rounded-full bg-muted" role="presentation">
              <div
                className="h-full rounded-full bg-primary transition-[width]"
                style={{ width: `${((index + 1) / total) * 100}%` }}
              />
            </div>

            <div className="rounded-lg border p-3">
              <p className="text-sm font-medium">{guidance?.title}</p>
              <p className="mt-1 text-xs text-muted-foreground">{finding.reason}</p>

              <p className="mt-3 text-xs font-medium">What clears it</p>
              <p className="mt-0.5 text-xs leading-relaxed text-muted-foreground">
                {guidance?.action}
              </p>

              <p className="mt-2 text-2xs text-muted-foreground">{guidance?.where}</p>
            </div>

            <p className="text-2xs text-muted-foreground">
              Counts toward{" "}
              {finding.riskType === "OVER_BUDGET"
                ? "over-budget risk"
                : finding.riskType === "REQUIREMENT_CHANGE"
                  ? "requirement change risk"
                  : "collaboration risk"}
              .
            </p>
          </div>
        )}

        <DialogFooter>
          {total > 0 && !done ? (
            <>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                disabled={index === 0}
                onClick={() => setIndex((i) => Math.max(0, i - 1))}
              >
                <ArrowLeft className="size-3.5" aria-hidden />
                Back
              </Button>
              {isLast ? (
                <Button
                  type="button"
                  size="sm"
                  disabled={reassess.isPending}
                  onClick={() => void finish()}
                >
                  {reassess.isPending ? "Re-assessing…" : "Re-assess the sprint"}
                </Button>
              ) : (
                <Button
                  type="button"
                  size="sm"
                  onClick={() => setIndex((i) => Math.min(total - 1, i + 1))}
                >
                  Next
                  <ArrowRight className="size-3.5" aria-hidden />
                </Button>
              )}
            </>
          ) : (
            <Button type="button" size="sm" variant="secondary" onClick={() => reset(false)}>
              Close
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
