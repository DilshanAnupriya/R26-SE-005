"use client";

import { LineChart } from "@/components/charts";
import { Card, CardHeader } from "@/components/design/surfaces";
import type { DailyRiskPoint } from "@/lib/api/ai-schemas";
import { asPercent, SEVERITY_COLOUR } from "./risk-gauge";

const SERIES = [
  { key: "overallScore", label: "Overall", colour: "var(--accent, oklch(0.55 0.2 260))" },
  { key: "overBudgetScore", label: "Over-budget", colour: SEVERITY_COLOUR.CRITICAL },
  { key: "requirementChangeScore", label: "Requirement change", colour: SEVERITY_COLOUR.MEDIUM },
  {
    key: "communicationCollaborationScore",
    label: "Collaboration",
    colour: SEVERITY_COLOUR.LOW,
  },
] as const;

/** `2026-08-22` to `22 Aug`. Dense enough to fit a fortnight of ticks. */
function shortDate(iso: string): string {
  const date = new Date(`${iso}T00:00:00Z`);
  return Number.isNaN(date.getTime())
    ? iso
    : date.toLocaleDateString("en-GB", { day: "2-digit", month: "short", timeZone: "UTC" });
}

/**
 * Risk per day of the sprint.
 *
 * <p>Unscored days are passed to the chart as nulls rather than zeroes. The distinction
 * is the whole point: a zero says the sprint was safe that day, a gap says nobody
 * looked, and a chart that renders the second as the first will be read as the first
 * every time.</p>
 */
export function DailyRiskTrend({
  points,
  loading,
}: {
  points: DailyRiskPoint[];
  loading?: boolean;
}) {
  const scoredDays = points.filter((point) => point.hasData).length;

  const series = SERIES.map((definition) => ({
    label: definition.label,
    color: definition.colour,
    dashed: definition.key !== "overallScore",
    points: points.map((point) => {
      const value = point[definition.key];
      return point.hasData && value != null ? Math.round(value * 100) : null;
    }),
  }));

  return (
    <Card>
      <CardHeader
        title="Risk over the sprint"
        subtitle={
          scoredDays === 0
            ? "No assessments yet — run one to start the line."
            : `Read at 17:00 UTC each day · ${scoredDays} of ${points.length} days scored · today is live`
        }
        action={
          <div className="flex flex-wrap items-center gap-3">
            {SERIES.map((definition) => (
              <span key={definition.key} className="flex items-center gap-1.5 text-xs text-ink-3">
                <span
                  aria-hidden
                  className="inline-block size-2 rounded-full"
                  style={{ background: definition.colour }}
                />
                {definition.label}
              </span>
            ))}
          </div>
        }
      />

      <div className="p-4">
        {loading ? (
          <div className="h-[220px] animate-pulse rounded-lg bg-surface-3" />
        ) : points.length === 0 ? (
          <p className="py-10 text-center text-sm text-ink-3">
            This sprint has no date range, so there are no days to plot.
          </p>
        ) : (
          <LineChart
            labels={points.map((point) => shortDate(point.date))}
            series={series}
            height={240}
            yLabel="Risk %"
          />
        )}
      </div>

      {scoredDays > 0 ? (
        <div className="overflow-x-auto border-t border-line">
          <table className="w-full min-w-[560px] text-sm">
            <thead>
              <tr className="border-b border-line text-left text-xs text-ink-3">
                <th scope="col" className="px-4 py-2 font-medium">Date</th>
                <th scope="col" className="px-4 py-2 text-right font-medium">Overall</th>
                <th scope="col" className="px-4 py-2 text-right font-medium">Over-budget</th>
                <th scope="col" className="px-4 py-2 text-right font-medium">Req. change</th>
                <th scope="col" className="px-4 py-2 text-right font-medium">Collaboration</th>
              </tr>
            </thead>
            <tbody>
              {points.map((point) => (
                <tr
                  key={point.date}
                  className={point.hasData ? "border-b border-line last:border-0" : "border-b border-line text-ink-3 opacity-50 last:border-0"}
                >
                  <td className="px-4 py-1.5 whitespace-nowrap">
                    {shortDate(point.date)}
                    {point.current ? (
                      <span className="ml-2 rounded-full bg-accent-soft px-1.5 py-px text-[10px] font-semibold text-accent-text">
                        live
                      </span>
                    ) : null}
                  </td>
                  <Cell value={point.overallScore} emphasis />
                  <Cell value={point.overBudgetScore} />
                  <Cell value={point.requirementChangeScore} />
                  <Cell value={point.communicationCollaborationScore} />
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </Card>
  );
}

function Cell({ value, emphasis }: { value: number | null | undefined; emphasis?: boolean }) {
  const percent = asPercent(value);
  return (
    <td
      className={
        emphasis
          ? "px-4 py-1.5 text-right font-semibold tabular-nums"
          : "px-4 py-1.5 text-right tabular-nums"
      }
    >
      {percent == null ? "—" : `${percent}%`}
    </td>
  );
}
