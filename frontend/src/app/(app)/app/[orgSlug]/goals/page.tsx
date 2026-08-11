"use client";

import { useState } from "react";
import { Plus } from "lucide-react";
import { Card } from "@/components/design/surfaces";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";
import { GoalCard } from "@/features/goals/components/goal-card";
import { CreateGoalDialog } from "@/features/goals/components/create-goal-dialog";
import { useGoalSummary, useGoals } from "@/features/goals/hooks";
import { cn } from "@/lib/utils";
import type { GoalStatus } from "@/features/goals/api";

const FILTERS: { key: GoalStatus | "ALL"; label: string }[] = [
  { key: "ALL", label: "All" },
  { key: "ON_TRACK", label: "On track" },
  { key: "AT_RISK", label: "At risk" },
  { key: "OFF_TRACK", label: "Off track" },
  { key: "ACHIEVED", label: "Achieved" },
];

export default function GoalsPage() {
  const [period, setPeriod] = useState<string | undefined>(undefined);
  const [status, setStatus] = useState<GoalStatus | "ALL">("ALL");
  const [creating, setCreating] = useState(false);

  const summary = useGoalSummary(period);
  const goals = useGoals({ period, status: status === "ALL" ? undefined : status });

  const periods = summary.data?.availablePeriods ?? [];
  const items = goals.data?.content ?? [];

  return (
    <div className="mx-auto max-w-[1100px] animate-fade-up px-1 pb-14">
      <div className="mb-5 flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-[-0.02em]">Goals &amp; OKRs</h1>
          <p className="mt-1 text-base text-ink-3">
            Objectives measured by numbers that move, not by status colours.
          </p>
        </div>
        <Button size="sm" onClick={() => setCreating(true)}>
          <Plus className="size-4" aria-hidden />
          New goal
        </Button>
      </div>

      <div className="stagger-children mb-4 grid gap-3.5 sm:grid-cols-2 lg:grid-cols-4">
        <SummaryCard label="Objectives" value={summary.data?.total} loading={summary.isLoading} />
        <SummaryCard
          label="On track"
          value={summary.data?.onTrack}
          tone="var(--green)"
          loading={summary.isLoading}
        />
        <SummaryCard
          label="Needs attention"
          value={(summary.data?.atRisk ?? 0) + (summary.data?.offTrack ?? 0)}
          tone="var(--amber)"
          loading={summary.isLoading}
        />
        <SummaryCard
          label="Average progress"
          value={summary.data ? Math.round(summary.data.averageProgress) : undefined}
          suffix="%"
          loading={summary.isLoading}
        />
      </div>

      <div className="mb-4 flex flex-wrap items-center gap-2">
        {periods.length > 0 ? (
          <div className="flex gap-1 rounded-md border border-line bg-surface p-0.5">
            <FilterChip active={period === undefined} onClick={() => setPeriod(undefined)}>
              All cycles
            </FilterChip>
            {periods.map((entry) => (
              <FilterChip key={entry} active={period === entry} onClick={() => setPeriod(entry)}>
                {entry}
              </FilterChip>
            ))}
          </div>
        ) : null}

        <div className="flex gap-1 rounded-md border border-line bg-surface p-0.5">
          {FILTERS.map((filter) => (
            <FilterChip
              key={filter.key}
              active={status === filter.key}
              onClick={() => setStatus(filter.key)}
            >
              {filter.label}
            </FilterChip>
          ))}
        </div>
      </div>

      {goals.isLoading ? (
        <div className="space-y-3">
          {[0, 1, 2].map((row) => (
            <Skeleton key={row} className="h-36 w-full" />
          ))}
        </div>
      ) : items.length === 0 ? (
        <Card className="p-10 text-center">
          <h2 className="text-lg font-semibold">
            {status === "ALL" && period === undefined
              ? "No goals yet"
              : "Nothing matches that filter"}
          </h2>
          <p className="mx-auto mt-1.5 max-w-md text-base text-ink-2">
            {status === "ALL" && period === undefined
              ? "An objective is a sentence you are trying to make true. Give it two or three key results — numbers that move — and it becomes reviewable."
              : "Try a different status or cycle."}
          </p>
          {status === "ALL" && period === undefined ? (
            <Button className="mt-4" onClick={() => setCreating(true)}>
              <Plus className="size-4" aria-hidden />
              Create the first goal
            </Button>
          ) : null}
        </Card>
      ) : (
        <div className="stagger-children space-y-3">
          {items.map((goal) => (
            <GoalCard key={goal.id} goal={goal} />
          ))}
        </div>
      )}

      <CreateGoalDialog open={creating} onOpenChange={setCreating} />
    </div>
  );
}

function FilterChip({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={cn(
        "rounded px-2.5 py-1 text-sm font-medium transition-colors",
        active ? "bg-accent-soft text-accent-text" : "text-ink-3 hover:text-ink",
      )}
    >
      {children}
    </button>
  );
}

function SummaryCard({
  label,
  value,
  suffix,
  tone,
  loading,
}: {
  label: string;
  value?: number;
  suffix?: string;
  tone?: string;
  loading: boolean;
}) {
  return (
    <Card className="px-4 py-[15px]">
      <div className="text-sm font-medium text-ink-2">{label}</div>
      {loading ? (
        <Skeleton className="mt-2 h-8 w-14" />
      ) : (
        <div
          className="mt-1 text-3xl font-semibold tracking-[-0.02em] tabular-nums"
          // Only tinted when the count is non-zero: a green "0 on track" is a
          // reassuring colour attached to bad news.
          style={tone && (value ?? 0) > 0 ? { color: tone } : undefined}
        >
          {value ?? 0}
          {suffix ? <span className="text-xl text-ink-3">{suffix}</span> : null}
        </div>
      )}
    </Card>
  );
}
