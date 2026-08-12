"use client";

import { use, useState } from "react";
import { Download, Info } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { EmptyState, ErrorState } from "@/components/feedback/states";
import { BarChart, StackedAreaChart, chartPalette } from "@/components/charts";
import { useProjects } from "@/features/projects/hooks";
import {
  useCfdReport,
  useCycleTimeReport,
  useLeadTimeReport,
  useProductivityReport,
  useRequestExport,
  useVelocityReport,
} from "@/features/reports/hooks";
import type { DurationDistribution } from "@/features/reports/api";

/**
 * The reports page.
 *
 * <p>Each tab loads its own report only when opened. Fetching all six on mount would run
 * six aggregations to show one, and the reports are the most expensive queries in the
 * product.</p>
 */
export default function ReportsPage({ params }: { params: Promise<{ orgSlug: string }> }) {
  use(params);
  const projects = useProjects();
  const [chosenProjectId, setChosenProjectId] = useState<string | null>(null);
  const projectId = chosenProjectId ?? projects.data?.content[0]?.id ?? null;

  const [tab, setTab] = useState("velocity");
  const requestExport = useRequestExport();

  if (projects.isPending) {
    return <Skeleton className="h-96 w-full" />;
  }

  if (!projectId) {
    return (
      <EmptyState
        title="No projects yet"
        description="Reports appear once a project has some delivery history."
      />
    );
  }

  return (
    <div className="animate-fade-up mx-auto max-w-5xl space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Reports</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            Delivery analytics, with the distributions rather than just the averages.
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          {projects.data && projects.data.content.length > 1 ? (
            <label className="text-xs">
              <span className="sr-only">Project</span>
              <select
                className="h-8 rounded-md border bg-background px-2 text-sm focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
                value={projectId}
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

          <Button
            size="sm"
            variant="secondary"
            disabled={requestExport.isPending}
            onClick={() => requestExport.mutate({ report: tab, projectId })}
          >
            <Download className="size-3.5" aria-hidden />
            {requestExport.isPending ? "Preparing…" : "Export CSV"}
          </Button>
        </div>
      </div>

      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="velocity">Velocity</TabsTrigger>
          <TabsTrigger value="cycle-time">Cycle time</TabsTrigger>
          <TabsTrigger value="lead-time">Lead time</TabsTrigger>
          <TabsTrigger value="cfd">Flow</TabsTrigger>
          <TabsTrigger value="productivity">People</TabsTrigger>
        </TabsList>

        <TabsContent value="velocity" className="mt-4">
          <VelocityPanel projectId={projectId} />
        </TabsContent>
        <TabsContent value="cycle-time" className="mt-4">
          <DurationPanel projectId={projectId} kind="cycle" />
        </TabsContent>
        <TabsContent value="lead-time" className="mt-4">
          <DurationPanel projectId={projectId} kind="lead" />
        </TabsContent>
        <TabsContent value="cfd" className="mt-4">
          <FlowPanel projectId={projectId} />
        </TabsContent>
        <TabsContent value="productivity" className="mt-4">
          <PeoplePanel projectId={projectId} />
        </TabsContent>
      </Tabs>
    </div>
  );
}

function VelocityPanel({ projectId }: { projectId: string }) {
  const report = useVelocityReport(projectId);

  if (report.isPending) return <Skeleton className="h-72 w-full" />;
  if (report.error) return <ErrorState error={report.error} onRetry={() => void report.refetch()} />;
  if (!report.data) return null;

  return (
    <section className="space-y-4">
      <div className="stagger-children grid gap-3 sm:grid-cols-3">
        <Stat label="Average velocity" value={report.data.averageVelocity} suffix=" pts" />
        <Stat
          label="Consistency"
          value={`± ${report.data.velocityStdDev}`}
          hint="Standard deviation — how much the average is worth relying on"
        />
        <Stat
          label="Average completion"
          value={`${Math.round(report.data.averageCompletionRate)}%`}
          hint="Of what was committed"
        />
      </div>

      <div className="rounded-lg border p-4">
        <BarChart
          labels={report.data.sprints.map((point) => point.sprintName ?? `#${point.sequenceNo}`)}
          series={[
            {
              label: "Committed",
              color: chartPalette.muted,
              values: report.data.sprints.map((point) => point.committedPoints),
            },
            {
              label: "Delivered",
              color: chartPalette.primary,
              values: report.data.sprints.map((point) => point.completedPoints),
            },
          ]}
        />
      </div>
    </section>
  );
}

function DurationPanel({ projectId, kind }: { projectId: string; kind: "cycle" | "lead" }) {
  const cycle = useCycleTimeReport(kind === "cycle" ? projectId : undefined);
  const lead = useLeadTimeReport(kind === "lead" ? projectId : undefined);
  const report = kind === "cycle" ? cycle : lead;

  if (report.isPending) return <Skeleton className="h-72 w-full" />;
  if (report.error) return <ErrorState error={report.error} onRetry={() => void report.refetch()} />;
  if (!report.data) return null;

  const data: DurationDistribution = report.data;

  if (data.sampleSize === 0) {
    return (
      <EmptyState
        title="Nothing finished yet"
        description={
          kind === "cycle"
            ? "Cycle time needs items that have moved from started to done."
            : "Lead time needs items that have been completed."
        }
      />
    );
  }

  return (
    <section className="space-y-4">
      <p className="flex items-start gap-1.5 text-xs text-muted-foreground">
        <Info className="mt-0.5 size-3.5 shrink-0" aria-hidden />
        {kind === "cycle"
          ? "Started to done — how long work takes once it is being worked on."
          : "Created to done — how long a request waits, which is what a stakeholder feels."}
      </p>

      <div className="stagger-children grid gap-3 sm:grid-cols-4">
        <Stat label="Median" value={data.median} suffix=" d" />
        <Stat label="85th percentile" value={data.p85} suffix=" d" />
        <Stat
          label="95th percentile"
          value={data.p95}
          suffix=" d"
          hint="The tail is what people get asked about"
        />
        <Stat label="Longest" value={data.longest} suffix=" d" />
      </div>

      <div className="rounded-lg border">
        <table className="w-full text-sm">
          <caption className="sr-only">Slowest items by {kind} time</caption>
          <thead>
            <tr className="border-b text-left text-xs text-muted-foreground">
              <th scope="col" className="p-2 font-medium">Item</th>
              <th scope="col" className="p-2 font-medium">Title</th>
              <th scope="col" className="p-2 text-right font-medium">Days</th>
            </tr>
          </thead>
          <tbody>
            {data.samples.slice(0, 10).map((sample) => (
              <tr key={sample.itemId} className="border-b last:border-0">
                <td className="p-2 text-xs text-muted-foreground tabular-nums">{sample.itemKey}</td>
                <td className="p-2">{sample.title}</td>
                <td className="p-2 text-right tabular-nums">{sample.days}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function FlowPanel({ projectId }: { projectId: string }) {
  const report = useCfdReport(projectId);

  if (report.isPending) return <Skeleton className="h-72 w-full" />;
  if (report.error) return <ErrorState error={report.error} onRetry={() => void report.refetch()} />;
  if (!report.data) return null;

  return (
    <section className="space-y-3">
      <p className="flex items-start gap-1.5 text-xs text-muted-foreground">
        <Info className="mt-0.5 size-3.5 shrink-0" aria-hidden />
        Three bands, reconstructed from item timestamps. Per-column history would need
        transition snapshots the schema deliberately does not keep.
      </p>
      <div className="rounded-lg border p-4">
        <StackedAreaChart
          labels={report.data.dates}
          bands={report.data.bands.map((band) => ({ label: band.state, values: band.counts }))}
        />
      </div>
    </section>
  );
}

function PeoplePanel({ projectId }: { projectId: string }) {
  const report = useProductivityReport(projectId);

  if (report.isPending) return <Skeleton className="h-72 w-full" />;
  if (report.error) return <ErrorState error={report.error} onRetry={() => void report.refetch()} />;
  if (!report.data) return null;

  return (
    <section className="space-y-3">
      {/* The backend's own caveat, rendered verbatim rather than paraphrased. */}
      {report.data.note ? (
        <p className="flex items-start gap-1.5 rounded-md border border-dashed px-3 py-2 text-xs text-muted-foreground">
          <Info className="mt-0.5 size-3.5 shrink-0" aria-hidden />
          {report.data.note}
        </p>
      ) : null}

      {report.data.rows.length === 0 ? (
        <EmptyState title="Nothing completed yet" />
      ) : (
        <div className="rounded-lg border">
          <table className="w-full text-sm">
            <caption className="sr-only">Delivery figures per person</caption>
            <thead>
              <tr className="border-b text-left text-xs text-muted-foreground">
                <th scope="col" className="p-2 font-medium">Person</th>
                <th scope="col" className="p-2 text-right font-medium">Items</th>
                <th scope="col" className="p-2 text-right font-medium">Points</th>
                <th scope="col" className="p-2 text-right font-medium">Hours</th>
                <th scope="col" className="p-2 text-right font-medium">Avg cycle</th>
                <th scope="col" className="p-2 text-right font-medium">Reopened</th>
              </tr>
            </thead>
            <tbody>
              {report.data.rows.map((row) => (
                <tr key={row.userId} className="border-b last:border-0">
                  <td className="p-2">{row.fullName}</td>
                  <td className="p-2 text-right tabular-nums">{row.itemsCompleted}</td>
                  <td className="p-2 text-right tabular-nums">{row.pointsCompleted}</td>
                  <td className="p-2 text-right tabular-nums">
                    {Math.round(row.minutesLogged / 60)}
                  </td>
                  <td className="p-2 text-right tabular-nums">
                    {row.averageCycleTimeDays ?? "—"}
                  </td>
                  <td className="p-2 text-right tabular-nums">
                    {row.reopenedCount > 0 ? (
                      <Badge variant="secondary">{row.reopenedCount}</Badge>
                    ) : (
                      "—"
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function Stat({
  label,
  value,
  suffix,
  hint,
}: {
  label: string;
  value: string | number;
  suffix?: string;
  hint?: string;
}) {
  return (
    <div className="rounded-lg border p-3">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="mt-0.5 text-xl font-semibold tabular-nums">
        {value}
        {suffix ? <span className="text-sm text-muted-foreground">{suffix}</span> : null}
      </p>
      {hint ? <p className="mt-0.5 text-[11px] text-muted-foreground">{hint}</p> : null}
    </div>
  );
}
