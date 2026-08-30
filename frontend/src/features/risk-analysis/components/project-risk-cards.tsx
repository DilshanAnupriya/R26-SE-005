"use client";

import Link from "next/link";
import { AlertTriangle, ArrowRight, ShieldAlert, ShieldCheck } from "lucide-react";
import { ProjectKey } from "@/components/design/item-chrome";
import { ProgressBar } from "@/components/design/surfaces";
import { Skeleton } from "@/components/ui/skeleton";
import type { Project } from "@/lib/api/delivery-schemas";
import { cn } from "@/lib/utils";

/**
 * How a project's own health is presented on the card.
 *
 * <p>Separate from the sprint risk bands and deliberately so: this is the project
 * health the API already publishes, not a roll-up of the sprint scores behind it.
 * Colouring a project card from figures it never fetched would be inventing a number,
 * and the sprint board one click away is where those figures actually live.</p>
 */
const HEALTH = {
  OFF_TRACK: { label: "Off track", accent: "var(--red)", soft: "var(--red-soft)", icon: ShieldAlert },
  AT_RISK: { label: "At risk", accent: "var(--amber)", soft: "var(--amber-soft)", icon: AlertTriangle },
  ON_TRACK: { label: "On track", accent: "var(--green)", soft: "var(--green-soft)", icon: ShieldCheck },
} as const;

function healthOf(project: Project) {
  return HEALTH[project.health as keyof typeof HEALTH] ?? HEALTH.ON_TRACK;
}

/**
 * The active projects, as a row of four.
 *
 * <p>Four to a line whatever the count: the grid keeps four tracks even for a single
 * project, so a card is always a quarter of the row. Stretching two cards across the
 * full width would make the same project look twice as important on a Tuesday as it did
 * on a Monday, purely because a third one finished.</p>
 */
export function ProjectRiskCards({
  projects,
  orgSlug,
  isLoading,
}: {
  projects: Project[];
  orgSlug: string;
  isLoading: boolean;
}) {
  if (isLoading) {
    return (
      <div className="grid grid-cols-2 gap-3.5 lg:grid-cols-4">
        {[0, 1, 2, 3].map((slot) => (
          <Skeleton key={slot} className="h-[132px] w-full rounded-xl" />
        ))}
      </div>
    );
  }

  if (projects.length === 0) {
    return (
      <p className="rounded-xl border border-dashed border-line px-4 py-8 text-center text-sm text-ink-3">
        No active projects. Create one to track its sprint risk.
      </p>
    );
  }

  return (
    <div className="stagger-children grid grid-cols-2 gap-3.5 lg:grid-cols-4">
      {projects.map((project) => {
        const health = healthOf(project);
        const Icon = health.icon;
        const progress = Math.round(Number(project.progressPct ?? 0));

        return (
          <Link
            key={project.id}
            href={`/app/${orgSlug}/projects/${project.id}/sprint-risk`}
            style={{ "--tone": health.accent, "--tone-soft": health.soft } as React.CSSProperties}
            className={cn(
              "group relative flex flex-col overflow-hidden rounded-xl border border-line bg-surface p-4 shadow-card",
              "transition-all duration-200 hover:-translate-y-0.5 hover:border-[var(--tone)] hover:bg-surface-2",
              "focus-visible:ring-2 focus-visible:ring-[var(--tone)] focus-visible:outline-none",
            )}
          >
            <span aria-hidden className="absolute inset-x-0 top-0 h-[3px] bg-[var(--tone)]" />

            <div className="mb-2.5 flex items-start justify-between gap-2">
              <ProjectKey projectKey={project.projectKey} size={28} />
              {/* The icon is decorative — the state is named in the text beside it. */}
              <span className="inline-flex items-center gap-1 rounded-full bg-[var(--tone-soft)] px-2 py-0.5 text-2xs font-semibold text-[var(--tone)]">
                <Icon aria-hidden className="size-3" />
                {health.label}
              </span>
            </div>

            <h3 className="truncate text-base font-semibold text-ink" title={project.name}>
              {project.name}
            </h3>

            <div className="mt-2.5 flex items-center gap-2">
              <ProgressBar value={progress} height={5} className="flex-1" colour={health.accent} />
              <span className="w-8 text-right text-xs font-semibold text-ink-2">{progress}%</span>
            </div>

            <span className="mt-2.5 inline-flex items-center gap-1 text-xs font-medium text-ink-3 transition-colors group-hover:text-[var(--tone)]">
              Sprint risk
              <ArrowRight className="size-3 transition-transform group-hover:translate-x-0.5" />
            </span>
          </Link>
        );
      })}
    </div>
  );
}
