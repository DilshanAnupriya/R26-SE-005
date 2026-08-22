"use client";

import { useState } from "react";
import { GitCompare, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Card, CardHeader, ProgressBar } from "@/components/design/surfaces";
import { EmptyState, ErrorState } from "@/components/feedback/states";
import type { Sprint } from "@/lib/api/delivery-schemas";
import { useSprintRiskComparison } from "../hooks";
import { asPercent, RiskGauge, SEVERITY_COLOUR, severityFor, SeverityBadge } from "./risk-gauge";

const MAX_COLUMNS = 5;

function formatRange(start?: string | null, end?: string | null): string {
  if (!start || !end) return "No dates set";
  const options: Intl.DateTimeFormatOptions = { day: "2-digit", month: "short", timeZone: "UTC" };
  return `${new Date(`${start}T00:00:00Z`).toLocaleDateString("en-GB", options)} → ${new Date(`${end}T00:00:00Z`).toLocaleDateString("en-GB", options)}`;
}

/**
 * Two to five sprints, side by side.
 *
 * <p>The comparison this screen exists for is "did the thing we changed help", and that
 * question needs the delivery numbers next to the risk ones — a sprint whose risk fell
 * because half its scope was cut has not improved. So committed and completed points sit
 * in the same table as the scores rather than a click away.</p>
 */
export function SprintCompare({ sprints }: { sprints: Sprint[] }) {
  const [selected, setSelected] = useState<string[]>([]);
  const comparison = useSprintRiskComparison(selected);

  const available = sprints.filter((sprint) => !selected.includes(sprint.id));
  const rows = comparison.data ?? [];

  function add(sprintId: string) {
    setSelected((current) =>
      current.includes(sprintId) || current.length >= MAX_COLUMNS
        ? current
        : [...current, sprintId],
    );
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader
          title="Choose sprints"
          subtitle={`Two to ${MAX_COLUMNS}. A sprint that was never assessed still appears — that it was never looked at is worth seeing.`}
        />
        <div className="space-y-3 p-4">
          <Select value="" onValueChange={add} disabled={available.length === 0 || selected.length >= MAX_COLUMNS}>
            <SelectTrigger className="w-full sm:max-w-sm">
              <SelectValue
                placeholder={
                  selected.length >= MAX_COLUMNS
                    ? `That is the maximum of ${MAX_COLUMNS}`
                    : "Add a sprint to the comparison"
                }
              />
            </SelectTrigger>
            <SelectContent>
              {available.map((sprint) => (
                <SelectItem key={sprint.id} value={sprint.id}>
                  {sprint.name} · {sprint.status}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>

          {selected.length > 0 ? (
            <div className="flex flex-wrap gap-2">
              {selected.map((sprintId) => {
                const sprint = sprints.find((entry) => entry.id === sprintId);
                return (
                  <span
                    key={sprintId}
                    className="inline-flex items-center gap-1.5 rounded-full border border-line bg-surface-2 py-1 pr-1 pl-3 text-sm"
                  >
                    {sprint?.name ?? "Sprint"}
                    <button
                      type="button"
                      onClick={() => setSelected((current) => current.filter((id) => id !== sprintId))}
                      className="rounded-full p-1 text-ink-3 transition-colors hover:bg-surface-3 hover:text-ink-1 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
                    >
                      <X className="size-3" aria-hidden />
                      <span className="sr-only">Remove {sprint?.name ?? "sprint"}</span>
                    </button>
                  </span>
                );
              })}
              <Button variant="ghost" size="sm" onClick={() => setSelected([])}>
                Clear
              </Button>
            </div>
          ) : null}
        </div>
      </Card>

      {selected.length < 2 ? (
        <EmptyState
          title="Pick two sprints"
          description="A comparison needs at least two. Five is the most that stays readable side by side."
        />
      ) : comparison.error ? (
        <ErrorState error={comparison.error} onRetry={() => void comparison.refetch()} />
      ) : comparison.isPending ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {selected.map((sprintId) => (
            <div key={sprintId} className="h-64 animate-pulse rounded-xl bg-surface-3" />
          ))}
        </div>
      ) : (
        <>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {rows.map((row) => {
              const scored = row.overallScore != null;
              return (
                <Card key={row.sprintId}>
                  <CardHeader
                    title={row.sprintName ?? "Sprint"}
                    subtitle={formatRange(row.startDate, row.endDate)}
                    action={<SeverityBadge severity={row.overallSeverity} scored={scored} />}
                  />
                  <div className="flex flex-col items-center gap-4 p-4">
                    <RiskGauge score={row.overallScore} size={116} label="Overall" />

                    <div className="w-full space-y-2">
                      <Bar label="Over-budget" score={row.overBudgetScore} />
                      <Bar label="Requirement change" score={row.requirementChangeScore} />
                      <Bar label="Collaboration" score={row.communicationCollaborationScore} />
                    </div>

                    <p className="text-center text-xs text-ink-3">
                      {scored
                        ? `${row.completedPoints ?? 0} of ${row.committedPoints ?? 0} points · ${row.blockedCount ?? 0} blocked · ${row.reopenedCount ?? 0} reopened`
                        : "Never assessed"}
                    </p>
                  </div>
                </Card>
              );
            })}
          </div>

          <Card>
            <CardHeader title="Side by side" />
            <div className="overflow-x-auto">
              <table className="w-full min-w-[680px] text-sm">
                <thead>
                  <tr className="border-b border-line text-left text-xs text-ink-3">
                    <th scope="col" className="px-4 py-2 font-medium">Sprint</th>
                    <th scope="col" className="px-4 py-2 text-right font-medium">Overall</th>
                    <th scope="col" className="px-4 py-2 font-medium">Level</th>
                    <th scope="col" className="px-4 py-2 text-right font-medium">Over-budget</th>
                    <th scope="col" className="px-4 py-2 text-right font-medium">Req. change</th>
                    <th scope="col" className="px-4 py-2 text-right font-medium">Collaboration</th>
                    <th scope="col" className="px-4 py-2 text-right font-medium">Committed</th>
                    <th scope="col" className="px-4 py-2 text-right font-medium">Completed</th>
                    <th scope="col" className="px-4 py-2 text-right font-medium">Board delta</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.sprintId} className="border-b border-line last:border-0">
                      <td className="px-4 py-2 font-medium">{row.sprintName ?? "Sprint"}</td>
                      <td className="px-4 py-2 text-right font-semibold tabular-nums">
                        {asPercent(row.overallScore) == null ? "—" : `${asPercent(row.overallScore)}%`}
                      </td>
                      <td className="px-4 py-2">
                        <SeverityBadge
                          severity={row.overallSeverity}
                          scored={row.overallScore != null}
                        />
                      </td>
                      <Percent value={row.overBudgetScore} />
                      <Percent value={row.requirementChangeScore} />
                      <Percent value={row.communicationCollaborationScore} />
                      <td className="px-4 py-2 text-right tabular-nums">{row.committedPoints ?? "—"}</td>
                      <td className="px-4 py-2 text-right tabular-nums">{row.completedPoints ?? "—"}</td>
                      <td className="px-4 py-2 text-right tabular-nums">
                        {row.adjustmentDelta === 0
                          ? "—"
                          : `${row.adjustmentDelta > 0 ? "+" : ""}${row.adjustmentDelta}`}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="border-t border-line px-4 py-2 text-xs text-ink-3">
              <GitCompare className="mr-1 inline size-3" aria-hidden />
              Board delta is the risk points accumulated since each sprint was last assessed —
              blockers, rework and logged scope changes.
            </p>
          </Card>
        </>
      )}
    </div>
  );
}

function Bar({ label, score }: { label: string; score: number | null | undefined }) {
  const percent = asPercent(score);
  return (
    <div>
      <div className="flex items-baseline justify-between text-xs text-ink-3">
        <span>{label}</span>
        <span className="tabular-nums">{percent == null ? "—" : `${percent}%`}</span>
      </div>
      <ProgressBar
        value={percent ?? 0}
        colour={percent == null ? "var(--surface-3)" : SEVERITY_COLOUR[severityFor(score)]}
        height={6}
        className="mt-1"
      />
    </div>
  );
}

function Percent({ value }: { value: number | null | undefined }) {
  const percent = asPercent(value);
  return <td className="px-4 py-2 text-right tabular-nums">{percent == null ? "—" : `${percent}%`}</td>;
}
