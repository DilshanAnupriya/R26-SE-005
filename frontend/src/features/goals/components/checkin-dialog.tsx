"use client";

import { useState } from "react";
import { Loader2 } from "lucide-react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { formatMetric, type KeyResult } from "../api";
import { useCheckIn, useCheckinHistory } from "../hooks";
import { cn } from "@/lib/utils";

/**
 * Recording a measurement.
 *
 * <p>Shows the history beneath the form rather than on a separate screen: the last
 * few values are the context that makes the next one a judgement rather than a
 * guess, and someone about to type a number should see what they typed last time.</p>
 */
export function CheckinDialog({
  keyResult,
  open,
  onOpenChange,
}: {
  keyResult: KeyResult;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const [value, setValue] = useState(String(keyResult.currentValue));
  const [confidence, setConfidence] = useState<number | null>(null);
  const [note, setNote] = useState("");

  const checkIn = useCheckIn();
  const history = useCheckinHistory(open ? keyResult.id : null);

  const parsed = Number(value);
  const valid = value.trim() !== "" && Number.isFinite(parsed);

  function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!valid) return;

    checkIn.mutate(
      {
        keyResultId: keyResult.id,
        value: parsed,
        confidence: confidence ?? undefined,
        note: note.trim() || undefined,
      },
      { onSuccess: () => onOpenChange(false) },
    );
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{keyResult.title}</DialogTitle>
          <DialogDescription>
            {keyResult.direction === "DECREASE" ? "Lower is better." : "Higher is better."} Target{" "}
            {formatMetric(keyResult.targetValue, keyResult.metricType, keyResult.unit)}, from a
            starting point of{" "}
            {formatMetric(keyResult.startValue, keyResult.metricType, keyResult.unit)}.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={submit} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="checkin-value">Current value</Label>
            <Input
              id="checkin-value"
              // inputMode numeric rather than type=number: type=number lets a scroll
              // wheel silently change a value the user has already committed to.
              inputMode="decimal"
              value={value}
              onChange={(event) => setValue(event.target.value)}
              autoFocus
              className="font-mono"
            />
          </div>

          <div className="space-y-1.5">
            <Label>Confidence</Label>
            <div className="flex gap-1.5">
              {[1, 2, 3, 4, 5].map((level) => (
                <button
                  key={level}
                  type="button"
                  onClick={() => setConfidence(confidence === level ? null : level)}
                  aria-pressed={confidence === level}
                  className={cn(
                    "h-8 flex-1 rounded-md border text-sm font-medium transition-colors",
                    confidence === level
                      ? "border-accent bg-accent-soft text-accent-text"
                      : "border-line text-ink-3 hover:bg-surface-3",
                  )}
                >
                  {level}
                </button>
              ))}
            </div>
            <p className="text-2xs text-ink-3">
              How sure are you of hitting the target? Optional.
            </p>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="checkin-note">What changed?</Label>
            <Textarea
              id="checkin-note"
              value={note}
              onChange={(event) => setNote(event.target.value)}
              rows={2}
              placeholder="The number says what happened; this says why."
            />
          </div>

          {history.data && history.data.length > 0 ? (
            <div className="rounded-lg border border-line bg-surface-2 p-3">
              <p className="mb-2 text-2xs font-semibold tracking-[0.05em] text-ink-3 uppercase">
                Recent check-ins
              </p>
              <ul className="space-y-1.5">
                {history.data.slice(0, 3).map((entry) => (
                  <li key={entry.id} className="flex items-baseline gap-2 text-xs">
                    <span className="font-mono font-medium tabular-nums">
                      {formatMetric(entry.value, keyResult.metricType, keyResult.unit)}
                    </span>
                    {entry.delta ? (
                      <span
                        className="font-mono tabular-nums"
                        style={{
                          // Green when it moved the right way, which depends on
                          // direction — a fall is good on a DECREASE metric.
                          color:
                            (keyResult.direction === "DECREASE" ? -entry.delta : entry.delta) > 0
                              ? "var(--green)"
                              : "var(--ink-3)",
                        }}
                      >
                        {entry.delta > 0 ? "+" : ""}
                        {entry.delta}
                      </span>
                    ) : null}
                    <span className="truncate text-ink-3">{entry.note ?? "No note"}</span>
                    <span className="ml-auto flex-none text-ink-3">
                      {new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short" }).format(
                        new Date(entry.recordedAt),
                      )}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={!valid || checkIn.isPending}>
              {checkIn.isPending ? (
                <>
                  <Loader2 className="size-4 animate-spin" aria-hidden />
                  Recording…
                </>
              ) : (
                "Record check-in"
              )}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
