"use client";

import { use, useState } from "react";
import { Users } from "lucide-react";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState, ErrorState } from "@/components/feedback/states";
import { useProjects } from "@/features/projects/hooks";
import { useWorkloadBalanceReport } from "@/features/reports/hooks";
import { cn } from "@/lib/utils";

function initials(name?: string | null) {
  if (!name) return "?";
  return name.split(/\s+/).slice(0, 2).map((part) => part[0]?.toUpperCase() ?? "").join("");
}

/**
 * Team workload.
 *
 * <p>Load is shown as a bar against 100%, with over-allocation called out rather than
 * clipped. A bar that stops at full when someone is at 140% hides exactly the person the
 * page exists to find.</p>
 */
export default function TeamPage({ params }: { params: Promise<{ orgSlug: string }> }) {
  use(params);
  const projects = useProjects();
  const [chosenProjectId, setChosenProjectId] = useState<string | null>(null);
  const projectId = chosenProjectId ?? projects.data?.content[0]?.id ?? null;

  const workload = useWorkloadBalanceReport(projectId ?? undefined);

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Team</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            Who is carrying what, and where the work has piled up.
          </p>
        </div>

        {projects.data && projects.data.content.length > 1 ? (
          <label className="text-xs">
            <span className="sr-only">Project</span>
            <select
              className="h-8 rounded-md border bg-background px-2 text-sm focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
              value={projectId ?? ""}
              onChange={(event) => setChosenProjectId(event.target.value)}
            >
              {projects.data.content.map((project) => (
                <option key={project.id} value={project.id}>
                  {project.name}
                </option>
              ))}
            </select>
          </label>
        ) : null}
      </div>

      {projects.isPending || (projectId && workload.isPending) ? (
        <Skeleton className="h-64 w-full" />
      ) : workload.error ? (
        <ErrorState error={workload.error} onRetry={() => void workload.refetch()} />
      ) : workload.data ? (
        <>
          <div className="grid gap-3 sm:grid-cols-3">
            <Stat label="Average load" value={`${workload.data.averageLoadPct}%`} />
            <Stat
              label="Imbalance"
              value={`${workload.data.imbalancePct}%`}
              hint="Gap between the busiest and the quietest"
            />
            <Stat
              label="Unassigned"
              value={workload.data.unassignedItems}
              hint="Open items nobody owns"
            />
          </div>

          {workload.data.rows.length === 0 ? (
            <EmptyState
              icon={Users}
              title="Nobody has work assigned yet"
              description="Assign some tasks and this page will show how the load is spread."
            />
          ) : (
            <ul className="divide-y rounded-lg border">
              {workload.data.rows.map((row) => (
                <li key={row.userId} className="p-3">
                  <div className="flex items-center gap-3">
                    <Avatar className="size-8 shrink-0">
                      <AvatarImage src={row.avatarUrl ?? undefined} alt="" />
                      <AvatarFallback className="text-xs">{initials(row.fullName)}</AvatarFallback>
                    </Avatar>

                    <div className="min-w-0 flex-1">
                      <p className="flex flex-wrap items-center gap-2">
                        <span className="truncate text-sm font-medium">{row.fullName}</span>
                        {row.overAllocated ? (
                          <Badge className="border-0 bg-amber-500/10 text-amber-700 dark:text-amber-400">
                            over capacity
                          </Badge>
                        ) : null}
                        {row.blockedItems > 0 ? (
                          <Badge variant="destructive">{row.blockedItems} blocked</Badge>
                        ) : null}
                      </p>
                      <p className="text-[11px] text-muted-foreground tabular-nums">
                        {row.openItems} open · {Math.round(row.estimatedMinutes / 60)}h estimated
                      </p>
                    </div>

                    <span className="w-12 shrink-0 text-right text-sm font-medium tabular-nums">
                      {Math.round(row.loadPct)}%
                    </span>
                  </div>

                  <div
                    className="mt-2 h-1.5 overflow-hidden rounded-full bg-muted"
                    role="meter"
                    aria-valuenow={Math.round(row.loadPct)}
                    aria-valuemin={0}
                    aria-valuemax={Math.max(100, Math.round(row.loadPct))}
                    aria-label={`${row.fullName} is at ${Math.round(row.loadPct)} percent of capacity`}
                  >
                    <div
                      className={cn(
                        "h-full rounded-full",
                        row.loadPct > 100
                          ? "bg-amber-500"
                          : row.loadPct > 80
                            ? "bg-primary"
                            : "bg-emerald-500",
                      )}
                      // Over 100% still renders full — the badge carries the overflow, and
                      // a bar wider than its track is a layout bug rather than a signal.
                      style={{ width: `${Math.min(100, row.loadPct)}%` }}
                    />
                  </div>
                </li>
              ))}
            </ul>
          )}
        </>
      ) : (
        <EmptyState title="No projects yet" description="Workload appears once there is work." />
      )}
    </div>
  );
}

function Stat({ label, value, hint }: { label: string; value: string | number; hint?: string }) {
  return (
    <div className="rounded-lg border p-3">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="mt-0.5 text-xl font-semibold tabular-nums">{value}</p>
      {hint ? <p className="text-[11px] text-muted-foreground">{hint}</p> : null}
    </div>
  );
}
