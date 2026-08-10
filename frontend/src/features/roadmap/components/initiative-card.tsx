"use client";

import { AlertTriangle } from "lucide-react";
import { ProgressBar } from "@/components/design/surfaces";
import { Avatar } from "@/components/design/item-chrome";
import { cn } from "@/lib/utils";
import { paceLabel, statusColour, statusLabel, type Initiative } from "../api";

/**
 * One initiative, as a card in its quarter column.
 *
 * <p>Shows progress and pace side by side because they answer different questions.
 * "40% done" is a fact about the work; "22 behind pace" is the fact a stakeholder
 * actually needs, and deriving it from a bar and a date in your head is something
 * nobody does — they look at the colour and move on.</p>
 */
export function InitiativeCard({
  initiative,
  laneColour,
  onOpen,
}: {
  initiative: Initiative;
  laneColour: string;
  onOpen?: () => void;
}) {
  const pace = paceLabel(initiative);
  const behind =
    initiative.expectedProgressPct != null &&
    initiative.progressPct < initiative.expectedProgressPct - 5;

  const linked = initiative.items.filter((item) => item.exists).length;
  const missing = initiative.items.length - linked;

  return (
    <button
      type="button"
      onClick={onOpen}
      aria-label={`${initiative.title}: ${Math.round(initiative.progressPct)} percent complete, ${statusLabel(initiative.status)}${pace ? `, ${pace}` : ""}. Open initiative.`}
      className="w-full rounded-lg border border-line bg-surface p-[10px_11px] text-left shadow-card transition-[background-color,transform] duration-[--duration-press] ease-out-quart hover:bg-surface-2 active:scale-[0.99] focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
      style={{ borderLeft: `3px solid ${laneColour}` }}
    >
      <div className="mb-[7px] flex items-start gap-2">
        <span className="min-w-0 flex-1 text-sm font-semibold">{initiative.title}</span>
        {initiative.ownerName ? (
          <Avatar name={initiative.ownerName} id={initiative.ownerUserId} size={20} />
        ) : null}
      </div>

      <div className="flex items-center gap-2">
        <ProgressBar value={initiative.progressPct} colour={laneColour} height={5} className="flex-1" />
        <span className="text-2xs font-semibold text-ink-3 tabular-nums">
          {Math.round(initiative.progressPct)}%
        </span>
      </div>

      <div className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-1">
        <span
          className="text-2xs font-semibold"
          style={{ color: statusColour(initiative.status) }}
        >
          ● {statusLabel(initiative.status)}
        </span>
        {pace ? (
          <span className={cn("text-2xs", behind ? "text-red" : "text-ink-3")}>{pace}</span>
        ) : null}
        {initiative.confidence === "LOW" ? (
          <span className="text-2xs text-amber" title="Low confidence in the date">
            low confidence
          </span>
        ) : null}
      </div>

      {linked > 0 || missing > 0 ? (
        <div className="mt-1.5 flex items-center gap-1.5 text-2xs text-ink-3">
          <span>
            {linked} {linked === 1 ? "item" : "items"}
          </span>
          {missing > 0 ? (
            <>
              {/* A link pointing at a deleted epic is excluded from the maths, but
                  saying so matters — otherwise the percentage looks arbitrary. */}
              <AlertTriangle className="size-3 text-amber" aria-hidden />
              <span className="text-amber">{missing} deleted</span>
            </>
          ) : null}
        </div>
      ) : (
        <div className="mt-1.5 text-2xs text-ink-3">
          No work linked — progress stays at zero until there is
        </div>
      )}
    </button>
  );
}
