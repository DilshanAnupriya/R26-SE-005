"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { AlertTriangle, CalendarDays, LayoutGrid, CheckCircle2 } from "lucide-react";
import { useSession } from "@/components/session-provider";
import { useProjects } from "@/features/projects/hooks";
import { Card, CardHeader, ProgressBar } from "@/components/design/surfaces";
import { Burndown } from "@/components/design/charts";
import { Avatar, ProjectKey, StatusPill } from "@/components/design/item-chrome";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";

/**
 * Home, built to the design's dashboard.
 *
 * <p>Wired to the project endpoints Phase 2 shipped. The panels whose data has no
 * backend yet — the burndown series, team activity — say so rather than showing
 * invented figures; a plausible number on a dashboard gets quoted in a standup.</p>
 */
export default function DashboardPage() {
  const { user } = useSession();
  const { orgSlug } = useParams<{ orgSlug: string }>();
  const projects = useProjects();

  const firstName = user?.fullName?.split(/\s+/)[0] ?? "there";
  const items = projects.data?.content ?? [];

  const dateLabel = new Intl.DateTimeFormat("en-GB", {
    weekday: "long",
    day: "numeric",
    month: "long",
  }).format(new Date());

  return (
    <div className="mx-auto max-w-[1360px] animate-fade-up px-1 pb-14">
      <div className="mb-5 flex items-end justify-between gap-4">
        <div>
          <div className="mb-0.5 text-sm font-medium text-ink-3">{dateLabel}</div>
          <h1 className="text-3xl font-semibold tracking-[-0.02em]">
            {greeting()}, {firstName}
          </h1>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" asChild>
            <Link href={`/app/${orgSlug}/reports`}>This week</Link>
          </Button>
          <Button size="sm" asChild>
            <Link href={`/app/${orgSlug}/board`}>Open active sprint</Link>
          </Button>
        </div>
      </div>

      <div className="mb-4 grid gap-3.5 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="Active projects"
          value={projects.isLoading ? null : String(items.filter((p) => p.status === "ACTIVE").length)}
          delta={`${items.length} total`}
          icon={LayoutGrid}
          tint="var(--violet)"
          tintSoft="var(--violet-soft)"
        />
        <StatCard
          label="At risk"
          value={projects.isLoading ? null : String(items.filter((p) => p.health !== "ON_TRACK").length)}
          delta="needs attention"
          deltaTone="var(--red)"
          icon={AlertTriangle}
          tint="var(--red)"
          tintSoft="var(--red-soft)"
        />
        <StatCard
          label="On track"
          value={projects.isLoading ? null : String(items.filter((p) => p.health === "ON_TRACK").length)}
          delta="healthy"
          deltaTone="var(--green)"
          icon={CheckCircle2}
          tint="var(--green)"
          tintSoft="var(--green-soft)"
        />
        <StatCard
          label="Due this month"
          value={projects.isLoading ? null : String(dueThisMonth(items))}
          delta="by target date"
          icon={CalendarDays}
          tint="var(--blue)"
          tintSoft="var(--blue-soft)"
        />
      </div>

      <div className="mb-4 grid gap-4 lg:grid-cols-[1.6fr_1fr]">
        <Card>
          <CardHeader
            title="Sprint progress"
            subtitle="Burndown across the active sprint"
            action={<StatusPill tone="green">On track</StatusPill>}
          />
          <div className="px-[18px] py-4">
            <div className="flex flex-col gap-6 sm:flex-row">
              <div className="w-full flex-none sm:w-[210px]">
                <div className="mb-1.5 flex justify-between text-sm text-ink-2">
                  <span>Story points</span>
                  <span className="font-semibold text-ink">—</span>
                </div>
                <ProgressBar value={0} className="mb-3.5" />
                <div className="grid grid-cols-2 gap-2.5">
                  <Metric value="—" label="Completed" />
                  <Metric value="—" label="Remaining" />
                  <Metric value="—" label="In progress" tone="var(--amber)" />
                  <Metric value="—" label="Blocked" tone="var(--red)" />
                </div>
              </div>
              <div className="min-w-0 flex-1">
                <div className="mb-1.5 text-sm text-ink-2">Burndown</div>
                <Burndown ideal={[]} actual={[]} height={140} />
                <p className="mt-2 text-xs text-ink-3">
                  Start a sprint to see the burn-down. Snapshots are recorded daily.
                </p>
              </div>
            </div>
          </div>
        </Card>

        <Card className="flex flex-col">
          <CardHeader title="Team activity" />
          <div className="flex-1 px-2 py-1.5">
            <p className="px-2 py-6 text-center text-sm text-ink-3">
              Activity appears here as your team moves work.
            </p>
          </div>
        </Card>
      </div>

      <div className="grid gap-4 lg:grid-cols-[1.6fr_1fr]">
        <Card>
          <CardHeader
            title="Active projects"
            action={
              <Link
                href={`/app/${orgSlug}/projects`}
                className="text-sm font-medium text-accent-text hover:underline"
              >
                View all
              </Link>
            }
          />
          <div>
            {projects.isLoading ? (
              <div className="space-y-2 p-4">
                {[0, 1, 2].map((row) => (
                  <Skeleton key={row} className="h-10 w-full" />
                ))}
              </div>
            ) : items.length === 0 ? (
              <p className="px-[18px] py-8 text-center text-sm text-ink-3">
                No projects yet. Create one to get started.
              </p>
            ) : (
              items.slice(0, 5).map((project) => (
                <Link
                  key={project.id}
                  href={`/app/${orgSlug}/projects/${project.id}`}
                  className="grid grid-cols-[1.7fr_90px_1fr_92px] items-center gap-3.5 border-b border-line px-[18px] py-3 transition-colors last:border-b-0 hover:bg-surface-2"
                >
                  <div className="flex min-w-0 items-center gap-2.5">
                    <ProjectKey projectKey={project.projectKey} />
                    <div className="min-w-0">
                      <div className="truncate text-base font-semibold">{project.name}</div>
                      <div className="text-xs text-ink-3">{project.status}</div>
                    </div>
                  </div>
                  <StatusPill tone={healthTone(project.health)}>{healthLabel(project.health)}</StatusPill>
                  <div className="flex items-center gap-2.5">
                    <ProgressBar
                      value={Number(project.progressPct ?? 0)}
                      height={6}
                      className="flex-1"
                      colour={healthColour(project.health)}
                    />
                    <span className="w-8 text-sm font-semibold">
                      {Math.round(Number(project.progressPct ?? 0))}%
                    </span>
                  </div>
                  <span className="justify-self-end text-sm text-ink-2">
                    {project.targetDate ? formatDue(project.targetDate) : "—"}
                  </span>
                </Link>
              ))
            )}
          </div>
        </Card>

        <Card>
          <CardHeader title="Upcoming deadlines" />
          <div className="px-2 py-1.5">
            {items.filter((p) => p.targetDate).length === 0 ? (
              <p className="px-2 py-6 text-center text-sm text-ink-3">
                No target dates set.
              </p>
            ) : (
              items
                .filter((p) => p.targetDate)
                .sort((a, b) => String(a.targetDate).localeCompare(String(b.targetDate)))
                .slice(0, 5)
                .map((project) => {
                  const date = new Date(project.targetDate as string);
                  return (
                    <div
                      key={project.id}
                      className="flex items-center gap-2.5 rounded-[9px] px-2 py-2 transition-colors hover:bg-surface-2"
                    >
                      <div className="w-10 flex-none text-center">
                        <div className="text-2xs font-semibold text-ink-3 uppercase">
                          {new Intl.DateTimeFormat("en-GB", { month: "short" }).format(date)}
                        </div>
                        <div className="text-xl leading-none font-semibold">{date.getDate()}</div>
                      </div>
                      <div className="min-w-0 flex-1">
                        <div className="truncate text-sm font-medium">{project.name}</div>
                        <div className="text-xs text-ink-3">Target date</div>
                      </div>
                      <Avatar name={project.name} id={project.id} size={22} />
                    </div>
                  );
                })
            )}
          </div>
        </Card>
      </div>
    </div>
  );
}

function greeting(): string {
  const hour = new Date().getHours();
  if (hour < 12) return "Good morning";
  if (hour < 18) return "Good afternoon";
  return "Good evening";
}

function dueThisMonth(items: { targetDate?: string | null }[]): number {
  const now = new Date();
  return items.filter((item) => {
    if (!item.targetDate) return false;
    const date = new Date(item.targetDate);
    return date.getMonth() === now.getMonth() && date.getFullYear() === now.getFullYear();
  }).length;
}

function healthTone(health?: string) {
  if (health === "OFF_TRACK") return "red" as const;
  if (health === "AT_RISK") return "amber" as const;
  return "green" as const;
}

function healthLabel(health?: string) {
  if (health === "OFF_TRACK") return "Off track";
  if (health === "AT_RISK") return "At risk";
  return "On track";
}

function healthColour(health?: string) {
  if (health === "OFF_TRACK") return "var(--red)";
  if (health === "AT_RISK") return "var(--amber)";
  return "var(--green)";
}

function formatDue(iso: string): string {
  return new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short" }).format(new Date(iso));
}

function Metric({ value, label, tone }: { value: string; label: string; tone?: string }) {
  return (
    <div>
      <div className="text-2xl font-semibold" style={tone ? { color: tone } : undefined}>
        {value}
      </div>
      <div className="text-xs text-ink-3">{label}</div>
    </div>
  );
}

function StatCard({
  label,
  value,
  delta,
  deltaTone,
  icon: Icon,
  tint,
  tintSoft,
}: {
  label: string;
  value: string | null;
  delta: string;
  deltaTone?: string;
  icon: typeof LayoutGrid;
  tint: string;
  tintSoft: string;
}) {
  return (
    <Card className="px-4 py-[15px]">
      <div className="mb-2.5 flex items-center justify-between">
        <span className="text-sm font-medium text-ink-2">{label}</span>
        <span
          className="flex size-[30px] items-center justify-center rounded-md"
          style={{ background: tintSoft, color: tint }}
        >
          <Icon className="size-4" strokeWidth={1.9} aria-hidden />
        </span>
      </div>
      <div className="flex items-baseline gap-2">
        {value === null ? (
          <Skeleton className="h-7 w-12" />
        ) : (
          <span className="text-4xl font-semibold tracking-[-0.02em]">{value}</span>
        )}
        <span className="text-sm font-semibold" style={{ color: deltaTone ?? "var(--ink-3)" }}>
          {delta}
        </span>
      </div>
    </Card>
  );
}
