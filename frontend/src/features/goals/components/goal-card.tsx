"use client";

import { useState } from "react";
import { ChevronRight, TrendingDown, TrendingUp } from "lucide-react";
import { Card, ProgressBar } from "@/components/design/surfaces";
import { Avatar, StatusPill } from "@/components/design/item-chrome";
import {
  formatMetric,
  statusColour,
  statusLabel,
  statusTone,
  type Goal,
  type KeyResult,
} from "../api";
import { CheckinDialog } from "./checkin-dialog";

/**
 * One objective and its key results.
 *
 * <p>The key results are visible by default rather than behind a disclosure. An
 * objective without its numbers is just a slogan, and hiding them is how OKR tools
 * end up being scrolled past.</p>
 */
export function GoalCard({ goal }: { goal: Goal }) {
  const [checkingIn, setCheckingIn] = useState<KeyResult | null>(null);

  return (
    <>
      <Card>
        <div className="flex items-start gap-3 border-b border-line px-[18px] py-3.5">
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2">
              <h3 className="truncate text-lg font-semibold">{goal.title}</h3>
              <StatusPill tone={statusTone(goal.status)}>{statusLabel(goal.status)}</StatusPill>
            </div>
            <div className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-sm text-ink-3">
              {goal.period ? <span>{goal.period}</span> : null}
              <span>·</span>
              <span>{goal.level.toLowerCase()}</span>
              {goal.daysRemaining !== null && goal.daysRemaining !== undefined ? (
                <>
                  <span>·</span>
                  <span className={goal.overdue ? "text-red" : undefined}>
                    {goal.overdue
                      ? `${Math.abs(goal.daysRemaining)} days overdue`
                      : `${goal.daysRemaining} days left`}
                  </span>
                </>
              ) : null}
            </div>
          </div>

          <div className="flex flex-none items-center gap-3">
            {goal.ownerName ? (
              <Avatar name={goal.ownerName} id={goal.ownerUserId} size={26} />
            ) : null}
            <div className="w-28">
              <div className="mb-1 flex items-baseline justify-between">
                <span className="text-2xs text-ink-3">Progress</span>
                <span className="text-sm font-semibold tabular-nums">
                  {Math.round(goal.progressPct)}%
                </span>
              </div>
              <ProgressBar
                value={goal.progressPct}
                colour={statusColour(goal.status)}
                height={6}
              />
            </div>
          </div>
        </div>

        {goal.keyResults.length === 0 ? (
          <p className="px-[18px] py-5 text-center text-sm text-ink-3">
            No key results yet — an objective without a number cannot be reviewed.
          </p>
        ) : (
          goal.keyResults.map((keyResult) => (
            <KeyResultRow
              key={keyResult.id}
              keyResult={keyResult}
              onCheckIn={() => setCheckingIn(keyResult)}
            />
          ))
        )}
      </Card>

      {checkingIn ? (
        <CheckinDialog
          keyResult={checkingIn}
          open
          onOpenChange={(next) => {
            if (!next) setCheckingIn(null);
          }}
        />
      ) : null}
    </>
  );
}

function KeyResultRow({
  keyResult,
  onCheckIn,
}: {
  keyResult: KeyResult;
  onCheckIn: () => void;
}) {
  const decreasing = keyResult.direction === "DECREASE";
  const Trend = decreasing ? TrendingDown : TrendingUp;

  const current = formatMetric(keyResult.currentValue, keyResult.metricType, keyResult.unit);
  const target = formatMetric(keyResult.targetValue, keyResult.metricType, keyResult.unit);

  // Spelled out rather than left to the name-from-content algorithm, which would
  // otherwise read the row as one unpunctuated run: "Activation rate31%/60%0%". It
  // opens with the visible title so voice control still matches what is on screen.
  const label =
    `${keyResult.title}: ${current} of ${target}, ` +
    `${Math.round(keyResult.progressPct)} percent complete. ` +
    `${decreasing ? "Lower is better" : "Higher is better"}. Record a check-in.`;

  return (
    <button
      type="button"
      onClick={onCheckIn}
      aria-label={label}
      className="grid w-full grid-cols-[1fr_auto] items-center gap-3 border-b border-line px-[18px] py-3 text-left transition-colors last:border-b-0 hover:bg-surface-2 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
    >
      <div className="min-w-0">
        <div className="flex items-center gap-2">
          {/* The arrow shows which way is good. Without it, "42 → 18" against a
              target of 10 reads as a decline to anyone who did not set the goal. */}
          <Trend
            className="size-3.5 flex-none"
            style={{ color: keyResult.achieved ? "var(--green)" : "var(--ink-3)" }}
            aria-hidden
          />
          <span className="truncate text-base">{keyResult.title}</span>
        </div>

        <div className="mt-1.5 flex items-center gap-2.5">
          <ProgressBar
            value={keyResult.progressPct}
            colour={statusColour(keyResult.status)}
            height={5}
            className="max-w-xs flex-1"
          />
          <span className="font-mono text-xs text-ink-3 tabular-nums">
            {current}
            <span className="mx-1 opacity-50">/</span>
            {target}
          </span>
        </div>
      </div>

      <div className="flex flex-none items-center gap-2.5">
        <span
          className="text-md font-semibold tabular-nums"
          style={{ color: statusColour(keyResult.status) }}
        >
          {Math.round(keyResult.progressPct)}%
        </span>
        <ChevronRight className="size-4 text-ink-3" aria-hidden />
      </div>
    </button>
  );
}
