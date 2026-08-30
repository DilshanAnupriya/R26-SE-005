"use client";

import { use, useMemo } from "react";
import Link from "next/link";
import { ArrowLeft, ListTodo } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState, ErrorState } from "@/components/feedback/states";
import { useSprintRisks } from "@/features/ai/hooks";
import { useProject } from "@/features/projects/hooks";
import { useSprints } from "@/features/sprints/hooks";
import { SprintRiskCard } from "@/features/risk-analysis/components/sprint-risk-card";
import { TONE_META, sprintRiskTone, type RiskTone } from "@/features/risk-analysis/sprint-risk-bands";

/**
 * Every sprint in one project, banded by overall risk.
 *
 * <p>A whole-project view rather than the per-sprint panels on the sprints page, and it
 * answers a different question: not "how bad is this sprint" but "which of these should
 * I be looking at". That is why the cards carry one number each — the overall — and the
 * three models behind it stay on the sprint page where there is room to explain them.</p>
 */
export default function ProjectSprintRiskPage({
  params,
}: {
  params: Promise<{ orgSlug: string; projectId: string }>;
}) {
  const { orgSlug, projectId } = use(params);
  const project = useProject(projectId);
  const sprints = useSprints(projectId);

  const items = useMemo(() => sprints.data?.content ?? [], [sprints.data]);
  // Stable identity per sprint list: `useQueries` re-subscribes on a new array every
  // render otherwise, and a board of ten sprints would refetch on every keystroke
  // anywhere above it.
  const sprintIds = useMemo(() => items.map((sprint) => sprint.id), [items]);
  const risks = useSprintRisks(sprintIds);

  const tallies = useMemo(() => {
    const counts: Record<RiskTone, number> = {
      unscored: 0,
      low: 0,
      medium: 0,
      "near-high": 0,
      high: 0,
    };
    items.forEach((_sprint, index) => {
      const query = risks[index];
      counts[query?.isLoading ? "unscored" : sprintRiskTone(query?.data?.overall)] += 1;
    });
    return counts;
  }, [items, risks]);

  if (sprints.isError) {
    return (
      <div className="mx-auto max-w-[1360px] px-1 pb-14">
        <ErrorState error={sprints.error} onRetry={() => void sprints.refetch()} />
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-[1360px] animate-fade-up px-1 pb-14">
      <div className="mb-5 flex flex-wrap items-end justify-between gap-4">
        <div className="min-w-0">
          <Link
            href={`/app/${orgSlug}/dashboard`}
            className="mb-1 inline-flex items-center gap-1.5 text-sm font-medium text-ink-3 transition-colors hover:text-ink"
          >
            <ArrowLeft className="size-3.5" />
            Dashboard
          </Link>
          <h1 className="truncate text-3xl font-semibold tracking-[-0.02em]">
            {project.data?.name ?? <Skeleton className="inline-block h-7 w-56 align-middle" />}
          </h1>
          <p className="mt-0.5 text-sm text-ink-3">
            Sprint risk across this project
            {items.length ? ` · ${items.length} ${items.length === 1 ? "sprint" : "sprints"}` : null}
          </p>
        </div>
        <Button variant="outline" size="sm" asChild>
          <Link href={`/app/${orgSlug}/projects/${projectId}/sprints`}>
            <ListTodo className="size-3.5" />
            Open sprints
          </Link>
        </Button>
      </div>

      {items.length > 0 ? <RiskLegend tallies={tallies} /> : null}

      {sprints.isLoading ? (
        <div className="grid grid-cols-2 gap-3.5 md:grid-cols-3 lg:grid-cols-5">
          {[0, 1, 2, 3, 4].map((slot) => (
            <Skeleton key={slot} className="h-44 w-full rounded-xl" />
          ))}
        </div>
      ) : items.length === 0 ? (
        <EmptyState
          title="This project has no sprints yet"
          description="Risk is scored per sprint, so there is nothing to band until one exists."
          action={
            <Button size="sm" asChild>
              <Link href={`/app/${orgSlug}/projects/${projectId}/sprints`}>Plan a sprint</Link>
            </Button>
          }
        />
      ) : (
        // Five to a row on a wide screen, as the board is meant to be read; two on a
        // phone, because five 70px cards is a row of unreadable slivers.
        <div className="stagger-children grid grid-cols-2 gap-3.5 md:grid-cols-3 lg:grid-cols-5">
          {items.map((sprint, index) => (
            <SprintRiskCard
              key={sprint.id}
              sprint={sprint}
              risk={risks[index]?.data}
              isLoading={risks[index]?.isLoading ?? true}
              // The risk analysis page, opened on this sprint. It is scoped by two
              // pickers rather than by route, so the selection travels as a query.
              href={`/app/${orgSlug}/risk?projectId=${projectId}&sprintId=${sprint.id}`}
            />
          ))}
        </div>
      )}
    </div>
  );
}

/**
 * What the colours mean, with a count against each.
 *
 * <p>Bands nobody is in are dropped rather than shown at zero. A legend is a key to
 * what is on the screen; listing four states for a board showing one turns it into a
 * quiz about the states that are missing.</p>
 */
function RiskLegend({ tallies }: { tallies: Record<RiskTone, number> }) {
  const order: RiskTone[] = ["near-high", "high", "medium", "low", "unscored"];
  const present = order.filter((tone) => tallies[tone] > 0);
  if (present.length === 0) return null;

  return (
    <div className="mb-4 flex flex-wrap items-center gap-x-4 gap-y-2">
      {present.map((tone) => (
        <span key={tone} className="inline-flex items-center gap-1.5 text-xs text-ink-2">
          <span
            aria-hidden
            className="size-2 rounded-full"
            style={{ background: TONE_META[tone].accent }}
          />
          <span className="font-medium">{TONE_META[tone].label}</span>
          <span className="text-ink-3">{tallies[tone]}</span>
        </span>
      ))}
    </div>
  );
}
