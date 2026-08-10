"use client";

import { useState } from "react";
import { ChevronLeft, ChevronRight, Info } from "lucide-react";
import { Card } from "@/components/design/surfaces";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";
import { useCalendar } from "@/features/calendar/hooks";
import {
  dayNumber,
  entryColour,
  entryKindLabel,
  monthKey,
  monthLabel,
  shiftMonth,
  type CalendarDay,
  type CalendarEntry,
} from "@/features/calendar/api";
import { cn } from "@/lib/utils";

const WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

export default function CalendarPage() {
  const [month, setMonth] = useState(() => monthKey(new Date()));
  const calendar = useCalendar(month);

  return (
    <div className="flex h-full animate-fade-up flex-col">
      <div className="flex flex-none flex-wrap items-end justify-between gap-4 px-1 pb-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-[-0.02em]">Calendar</h1>
          <p className="mt-1 text-base text-ink-3">
            Everything already dated: sprint boundaries, milestones, due dates and targets.
          </p>
        </div>
        <div className="flex items-center gap-1">
          <Button
            variant="outline"
            size="icon"
            onClick={() => setMonth((current) => shiftMonth(current, -1))}
            aria-label="Previous month"
          >
            <ChevronLeft className="size-4" aria-hidden />
          </Button>
          <span className="min-w-40 text-center text-base font-semibold">
            {monthLabel(month)}
          </span>
          <Button
            variant="outline"
            size="icon"
            onClick={() => setMonth((current) => shiftMonth(current, 1))}
            aria-label="Next month"
          >
            <ChevronRight className="size-4" aria-hidden />
          </Button>
          <Button
            variant="ghost"
            size="sm"
            className="ml-1"
            onClick={() => setMonth(monthKey(new Date()))}
          >
            Today
          </Button>
        </div>
      </div>

      {calendar.isLoading ? (
        <Skeleton className="mx-1 h-[560px] w-auto" />
      ) : (
        <div className="min-h-0 flex-1 px-1 pb-6">
          <Card className="flex h-full flex-col overflow-hidden">
            <div className="grid flex-none grid-cols-7 border-b border-line">
              {WEEKDAYS.map((day) => (
                <div
                  key={day}
                  className="px-2.5 py-2 text-2xs font-semibold tracking-[0.05em] text-ink-3 uppercase"
                >
                  {day}
                </div>
              ))}
            </div>
            <div
              className="grid min-h-0 flex-1 grid-cols-7"
              style={{
                gridTemplateRows: `repeat(${Math.ceil((calendar.data?.days.length ?? 35) / 7)}, minmax(0, 1fr))`,
              }}
            >
              {(calendar.data?.days ?? []).map((day) => (
                <Day key={day.date} day={day} />
              ))}
            </div>
          </Card>

          <p className="mt-2.5 flex items-center gap-1.5 text-sm text-ink-3">
            <Info className="size-3.5 flex-none" aria-hidden />
            {/* Said out loud, because a reader who does not know this reads an empty
                week as "nothing on" rather than "nothing tracked here". */}
            Commitments the system already knows about. Meetings and time off are not
            tracked here.
            {calendar.data ? ` ${calendar.data.totalEntries} this month.` : null}
          </p>
        </div>
      )}
    </div>
  );
}

function Day({ day }: { day: CalendarDay }) {
  // Four fit before the cell needs to scroll; past that a count is more useful than
  // a list nobody can read.
  const shown = day.entries.slice(0, 4);
  const hidden = day.entries.length - shown.length;

  return (
    <div
      className={cn(
        "min-w-0 overflow-hidden border-r border-b border-line p-1.5 last:border-r-0",
        !day.inMonth && "bg-surface-2",
        day.weekend && day.inMonth && "bg-[color-mix(in_srgb,var(--surface-2)_55%,transparent)]",
      )}
    >
      <div className="mb-1 flex items-center gap-1.5">
        <span
          className={cn(
            "inline-flex size-[22px] items-center justify-center rounded-full text-2xs font-semibold tabular-nums",
            day.today && "bg-accent text-white",
            !day.today && day.inMonth && "text-ink-2",
            !day.inMonth && "text-ink-3",
          )}
        >
          {dayNumber(day.date)}
        </span>
      </div>

      <div className="space-y-1">
        {shown.map((entry) => (
          <Entry key={`${entry.kind}-${entry.id}`} entry={entry} />
        ))}
        {hidden > 0 ? (
          <div className="px-1 text-2xs font-medium text-ink-3">+{hidden} more</div>
        ) : null}
      </div>
    </div>
  );
}

function Entry({ entry }: { entry: CalendarEntry }) {
  const colour = entryColour(entry);
  const label = `${entryKindLabel(entry.kind)}: ${entry.itemKey ? `${entry.itemKey} ` : ""}${entry.title}${entry.projectKey ? ` (${entry.projectKey})` : ""}${entry.overdue ? ", overdue" : ""}`;

  return (
    <div
      className="flex items-center gap-1 truncate rounded px-1 py-0.5 text-2xs"
      style={{ background: `color-mix(in srgb, ${colour} 12%, transparent)`, color: colour }}
      title={label}
    >
      <span className="size-1.5 flex-none rounded-full" style={{ background: colour }} aria-hidden />
      <span className="truncate font-medium">{entry.title}</span>
      <span className="sr-only">{label}</span>
    </div>
  );
}
