"use client";

import { useState } from "react";
import { Plus } from "lucide-react";
import { Card } from "@/components/design/surfaces";
import { ProjectKey } from "@/components/design/item-chrome";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";
import { InitiativeCard } from "@/features/roadmap/components/initiative-card";
import { CreateInitiativeDialog } from "@/features/roadmap/components/create-initiative-dialog";
import { useRoadmap } from "@/features/roadmap/hooks";
import {
  quarterLabel,
  type Initiative,
  type InitiativeStatus,
  type RoadmapLane,
} from "@/features/roadmap/api";
import { cn } from "@/lib/utils";

const FILTERS: { key: InitiativeStatus | "ALL"; label: string }[] = [
  { key: "ALL", label: "All" },
  { key: "PLANNED", label: "Planned" },
  { key: "IN_PROGRESS", label: "In progress" },
  { key: "AT_RISK", label: "At risk" },
  { key: "SHIPPED", label: "Shipped" },
];

/**
 * Lane colours, keyed off the project.
 *
 * <p>Derived from the key rather than stored, matching how avatars and project chips
 * already work — one fewer column, and no chance of the roadmap and the project list
 * disagreeing about what colour PAY is.</p>
 */
const LANE_HUES = [
  "var(--accent)",
  "var(--teal)",
  "var(--violet)",
  "var(--amber)",
  "var(--blue)",
  "var(--green)",
] as const;

function laneColour(key: string): string {
  let hash = 0;
  for (let index = 0; index < key.length; index++) {
    hash = (hash * 31 + key.charCodeAt(index)) >>> 0;
  }
  return LANE_HUES[hash % LANE_HUES.length];
}

export default function RoadmapPage() {
  const [status, setStatus] = useState<InitiativeStatus | "ALL">("ALL");
  const [creating, setCreating] = useState(false);

  const roadmap = useRoadmap({ status: status === "ALL" ? undefined : status });
  const quarters = roadmap.data?.quarters ?? [];
  const lanes = roadmap.data?.lanes ?? [];

  return (
    <div className="flex h-full animate-fade-up flex-col">
      <div className="flex flex-none flex-wrap items-end justify-between gap-4 px-1 pb-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-[-0.02em]">Roadmap</h1>
          <p className="mt-1 text-base text-ink-3">
            What we intend to build, and how far through it we actually are.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <div className="flex gap-1 rounded-md border border-line bg-surface p-0.5">
            {FILTERS.map((filter) => (
              <button
                key={filter.key}
                type="button"
                onClick={() => setStatus(filter.key)}
                aria-pressed={status === filter.key}
                className={cn(
                  "rounded px-2.5 py-1 text-sm font-medium transition-colors",
                  status === filter.key
                    ? "bg-accent-soft text-accent-text"
                    : "text-ink-3 hover:text-ink",
                )}
              >
                {filter.label}
              </button>
            ))}
          </div>
          <Button size="sm" onClick={() => setCreating(true)}>
            <Plus className="size-4" aria-hidden />
            New initiative
          </Button>
        </div>
      </div>

      {roadmap.data && roadmap.data.totalInitiatives > 0 ? (
        <p className="flex-none px-1 pb-3 text-sm text-ink-3">
          {roadmap.data.totalInitiatives} initiatives
          {roadmap.data.atRisk > 0 ? (
            <>
              {" · "}
              <span className="font-semibold text-red">{roadmap.data.atRisk} at risk</span>
            </>
          ) : null}
          {roadmap.data.shipped > 0 ? ` · ${roadmap.data.shipped} shipped` : null}
        </p>
      ) : null}

      {roadmap.isLoading ? (
        <div className="space-y-3 px-1">
          {[0, 1, 2].map((row) => (
            <Skeleton key={row} className="h-24 w-full" />
          ))}
        </div>
      ) : lanes.length === 0 ? (
        <Card className="mx-1 p-10 text-center">
          <h2 className="text-lg font-semibold">
            {status === "ALL" ? "Nothing on the roadmap yet" : "Nothing matches that filter"}
          </h2>
          <p className="mx-auto mt-1.5 max-w-md text-base text-ink-2">
            {status === "ALL"
              ? "An initiative is a body of work worth naming to someone outside the team. Give it a target date and link the epics that make it up — the percentage looks after itself."
              : "Try a different status."}
          </p>
          {status === "ALL" ? (
            <Button className="mt-4" onClick={() => setCreating(true)}>
              <Plus className="size-4" aria-hidden />
              Add the first one
            </Button>
          ) : null}
        </Card>
      ) : (
        <div className="min-h-0 flex-1 overflow-auto px-1 pb-10">
          {/* min-width so the quarter columns stay readable; this container scrolls
              rather than the page, which keeps the lane labels beside their cards. */}
          <div style={{ minWidth: Math.max(760, 160 + quarters.length * 260) }}>
            <div
              className="sticky top-0 z-10 mb-3 grid gap-3.5 bg-canvas pb-1"
              style={{ gridTemplateColumns: `160px repeat(${quarters.length}, minmax(0, 1fr))` }}
            >
              <div />
              {quarters.map((quarter) => (
                <div key={quarter} className="px-1 text-sm font-semibold text-ink-2">
                  {quarterLabel(quarter)}
                </div>
              ))}
            </div>

            {lanes.map((lane) => (
              <Lane key={lane.projectId ?? "cross"} lane={lane} quarters={quarters} />
            ))}
          </div>
        </div>
      )}

      <CreateInitiativeDialog open={creating} onOpenChange={setCreating} />
    </div>
  );
}

function Lane({ lane, quarters }: { lane: RoadmapLane; quarters: string[] }) {
  const colour = laneColour(lane.projectKey);

  // Initiatives without a target date have no column to sit in. Rather than dropping
  // them — which is how work quietly disappears from a plan — they are gathered into
  // a tray at the end of the lane.
  const undated = lane.initiatives.filter((initiative) => !initiative.quarter);

  return (
    <div
      className="mb-3.5 grid gap-3.5"
      style={{ gridTemplateColumns: `160px repeat(${quarters.length}, minmax(0, 1fr))` }}
    >
      <div className="flex items-center gap-[9px] py-2.5">
        <ProjectKey projectKey={lane.projectKey} size={30} />
        <span className="min-w-0 truncate text-sm font-semibold">{lane.name}</span>
      </div>

      {quarters.map((quarter) => (
        <QuarterCell
          key={quarter}
          colour={colour}
          initiatives={lane.initiatives.filter((initiative) => initiative.quarter === quarter)}
        />
      ))}

      {undated.length > 0 ? (
        <div className="col-span-full ml-[174px] flex flex-wrap gap-2 pt-1">
          <span className="self-center text-2xs text-ink-3">No date yet:</span>
          {undated.map((initiative) => (
            <span
              key={initiative.id}
              className="rounded-md border border-dashed border-line px-2 py-1 text-2xs text-ink-2"
            >
              {initiative.title}
            </span>
          ))}
        </div>
      ) : null}
    </div>
  );
}

function QuarterCell({ initiatives, colour }: { initiatives: Initiative[]; colour: string }) {
  return (
    <div className="flex min-h-[60px] flex-col gap-2 rounded-[10px] border border-line bg-surface-2 p-2">
      {initiatives.map((initiative) => (
        <InitiativeCard key={initiative.id} initiative={initiative} laneColour={colour} />
      ))}
    </div>
  );
}
