"use client";

import { useState } from "react";
import { Info } from "lucide-react";
import { Card } from "@/components/design/surfaces";
import { Avatar } from "@/components/design/item-chrome";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useProjects } from "@/features/projects/hooks";
import { useTimeline } from "@/features/timeline/hooks";
import {
  barColour,
  barGeometry,
  dateSourceNote,
  isInferredSpan,
  todayOffset,
  type Bar,
  type Timeline,
} from "@/features/timeline/api";
import { cn } from "@/lib/utils";

const LABEL_WIDTH = 240;

export default function TimelinePage() {
  const projects = useProjects();
  const [chosen, setChosen] = useState<string | undefined>();

  // Derived, not synced through an effect. The first project is a reasonable default —
  // without one the screen is a picker with nothing behind it, which reads as broken
  // rather than as a choice — but copying it into state means two sources of truth
  // and a render where they disagree.
  const projectId = chosen ?? projects.data?.content[0]?.id;
  const timeline = useTimeline(projectId);

  return (
    <div className="flex h-full animate-fade-up flex-col">
      <div className="flex flex-none flex-wrap items-end justify-between gap-4 px-1 pb-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-[-0.02em]">Timeline</h1>
          <p className="mt-1 text-base text-ink-3">
            Delivery against dates, assembled from the epics, sprints and milestones that
            already have them.
          </p>
        </div>
        <Select value={projectId ?? ""} onValueChange={setChosen}>
          <SelectTrigger className="w-64" aria-label="Project">
            <SelectValue placeholder="Choose a project" />
          </SelectTrigger>
          <SelectContent>
            {(projects.data?.content ?? []).map((project) => (
              <SelectItem key={project.id} value={project.id}>
                {project.projectKey} · {project.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {timeline.isLoading || projects.isLoading ? (
        <div className="space-y-3 px-1">
          {[0, 1, 2].map((row) => (
            <Skeleton key={row} className="h-16 w-full" />
          ))}
        </div>
      ) : !timeline.data || timeline.data.groups.length === 0 ? (
        <Card className="mx-1 p-10 text-center">
          <h2 className="text-lg font-semibold">Nothing on this project has dates yet</h2>
          <p className="mx-auto mt-1.5 max-w-md text-base text-ink-2">
            A bar needs a start and an end. Give an epic a target date, put stories into a
            sprint, or set a milestone — the timeline draws itself from those.
          </p>
          {timeline.data && timeline.data.undatedCount > 0 ? (
            <p className="mt-3 text-sm text-ink-3">
              {timeline.data.undatedCount} undated{" "}
              {timeline.data.undatedCount === 1 ? "item" : "items"} excluded.
            </p>
          ) : null}
        </Card>
      ) : (
        <Chart timeline={timeline.data} />
      )}
    </div>
  );
}

function Chart({ timeline }: { timeline: Timeline }) {
  const nowLeft = todayOffset(timeline.today, timeline.from, timeline.to);
  const trackWidth = Math.max(640, timeline.weeks.length * 86);

  return (
    <div className="min-h-0 flex-1 overflow-auto px-1 pb-10">
      <div style={{ minWidth: LABEL_WIDTH + trackWidth }}>
        <div
          className="sticky top-0 z-10 flex bg-canvas pb-2"
          style={{ paddingLeft: LABEL_WIDTH }}
        >
          {timeline.weeks.map((week) => (
            <div
              key={week.start}
              className="flex-1 border-l border-line pl-1.5 text-2xs font-semibold text-ink-3"
            >
              {week.label}
            </div>
          ))}
        </div>

        <div className="relative">
          {/* The now-line spans every row, so it is drawn once over the whole chart
              rather than repeated per row where the segments would never quite align. */}
          {nowLeft ? (
            <div
              className="pointer-events-none absolute top-0 bottom-0 z-[5] w-px bg-red"
              style={{ left: `calc(${LABEL_WIDTH}px + (100% - ${LABEL_WIDTH}px) * ${parseFloat(nowLeft) / 100})` }}
              aria-hidden
            >
              <span className="absolute -top-1 -left-[3px] size-[7px] rounded-full bg-red" />
            </div>
          ) : null}

          {timeline.groups.map((group, index) => (
            <div key={group.bar.id ?? `orphans-${index}`} className="mb-4">
              <Row bar={group.bar} timeline={timeline} isGroup />
              {group.rows.map((row) => (
                <Row key={row.id} bar={row} timeline={timeline} />
              ))}
            </div>
          ))}

          {timeline.milestones.length > 0 ? (
            <div className="mt-2 border-t border-line pt-3">
              {timeline.milestones.map((milestone) => (
                <Row key={milestone.id} bar={milestone} timeline={timeline} />
              ))}
            </div>
          ) : null}
        </div>

        {timeline.undatedCount > 0 ? (
          <p className="mt-4 flex items-center gap-1.5 text-sm text-ink-3">
            <Info className="size-3.5" aria-hidden />
            {timeline.undatedCount} {timeline.undatedCount === 1 ? "item has" : "items have"} no
            dates and {timeline.undatedCount === 1 ? "is" : "are"} not shown — inventing dates
            for them would make the chart look complete and be wrong.
          </p>
        ) : null}
      </div>
    </div>
  );
}

function Row({
  bar,
  timeline,
  isGroup = false,
}: {
  bar: Bar;
  timeline: Timeline;
  isGroup?: boolean;
}) {
  const geometry = barGeometry(bar, timeline.from, timeline.to);
  const colour = barColour(bar);
  const note = dateSourceNote(bar);
  const inferred = isInferredSpan(bar);
  const isMarker = bar.kind === "MILESTONE" || bar.start === bar.end;

  const description = [
    bar.itemKey,
    bar.title,
    `${bar.start} to ${bar.end}`,
    note,
    bar.blocked ? "Blocked" : null,
    bar.overdue ? "Overdue" : null,
  ]
    .filter(Boolean)
    .join(". ");

  return (
    <div className="flex items-center" style={{ height: isGroup ? 34 : 30 }}>
      <div
        className="flex min-w-0 flex-none items-center gap-2 pr-3"
        style={{ width: LABEL_WIDTH }}
      >
        {bar.assigneeName ? (
          <Avatar name={bar.assigneeName} id={bar.assigneeId} size={20} />
        ) : null}
        <span
          className={cn(
            "min-w-0 truncate",
            isGroup ? "text-sm font-semibold" : "text-sm text-ink-2",
          )}
        >
          {bar.itemKey ? (
            <span className="mr-1.5 font-mono text-2xs text-ink-3">{bar.itemKey}</span>
          ) : null}
          {bar.title}
        </span>
      </div>

      <div className="relative h-full flex-1">
        <div
          className={cn(
            "absolute top-1/2 -translate-y-1/2",
            // Only a genuinely inferred span is drawn as an outline. A guessed date
            // that looks identical to a committed one gets quoted back as a promise —
            // and a committed one drawn as a guess gets ignored.
            inferred ? "border border-dashed" : "",
          )}
          style={{
            left: geometry.left,
            width: isMarker ? undefined : geometry.width,
            height: isGroup ? 12 : 9,
            borderRadius: 20,
            background: inferred ? `color-mix(in srgb, ${colour} 18%, transparent)` : colour,
            borderColor: colour,
            ...(isMarker
              ? { width: 12, height: 12, borderRadius: 3, transform: "translate(-6px, -50%) rotate(45deg)", background: colour }
              : {}),
          }}
          title={description}
        >
          {!isMarker && bar.progressPct != null && bar.progressPct > 0 && !inferred ? (
            <div
              className="h-full rounded-full bg-[color-mix(in_srgb,black_22%,transparent)]"
              style={{ width: `${Math.min(100, bar.progressPct)}%` }}
              aria-hidden
            />
          ) : null}
        </div>
        <span className="sr-only">{description}</span>
      </div>
    </div>
  );
}
