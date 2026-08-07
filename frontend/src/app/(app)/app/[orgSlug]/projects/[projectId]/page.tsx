"use client";

import { use, useState } from "react";
import Link from "next/link";
import { ArrowLeft, ListTodo, Sparkles } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState, ErrorState } from "@/components/feedback/states";
import { EstimatePanel } from "@/features/ai/components/estimate-panel";
import { useEstimateBatch } from "@/features/ai/hooks";
import { useBacklog } from "@/features/backlog/hooks";
import { useProject, useProjectOverview } from "@/features/projects/hooks";
import type { Story } from "@/lib/api/delivery-schemas";
import { cn } from "@/lib/utils";

/**
 * Project overview and backlog.
 *
 * <p>The estimate panel opens beside the selected story rather than in a modal: sizing
 * is a comparison task, and hiding the rest of the backlog behind an overlay removes the
 * context that makes "is this bigger than that one" answerable.</p>
 */
export default function ProjectPage({
  params,
}: {
  params: Promise<{ orgSlug: string; projectId: string }>;
}) {
  const { orgSlug, projectId } = use(params);
  const project = useProject(projectId);
  const overview = useProjectOverview(projectId);
  const backlog = useBacklog(projectId, { size: 50 });
  const batch = useEstimateBatch();

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const stories = backlog.data?.content ?? [];
  const selected = stories.find((story) => story.id === selectedId) ?? null;

  const unestimated = stories.filter((story) => story.storyPoints == null);

  if (project.error) {
    return <ErrorState error={project.error} onRetry={() => void project.refetch()} />;
  }

  return (
    <div className="mx-auto max-w-6xl space-y-6">
      <div>
        <Link
          href={`/app/${orgSlug}/projects`}
          className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
        >
          <ArrowLeft className="size-3" aria-hidden />
          All projects
        </Link>

        {project.isPending ? (
          <Skeleton className="mt-2 h-8 w-64" />
        ) : (
          <div className="mt-1 flex flex-wrap items-center gap-2">
            <h1 className="text-2xl font-semibold tracking-tight">{project.data?.name}</h1>
            <Badge variant="outline">{project.data?.projectKey}</Badge>
            <Badge variant="secondary">{project.data?.status.toLowerCase()}</Badge>
          </div>
        )}
      </div>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Metric label="Stories" value={overview.data?.storyCount} loading={overview.isPending} />
        <Metric
          label="Completed"
          value={overview.data?.completedStoryCount}
          loading={overview.isPending}
        />
        <Metric
          label="Points delivered"
          value={overview.data?.completedPoints}
          suffix={overview.data ? ` / ${overview.data.totalPoints}` : undefined}
          loading={overview.isPending}
        />
        <Metric label="Blocked" value={overview.data?.blockedCount} loading={overview.isPending} />
      </div>

      <div className="grid gap-4 lg:grid-cols-[1fr_20rem]">
        <section aria-labelledby="backlog-heading">
          <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
            <h2 id="backlog-heading" className="text-sm font-medium">
              Backlog
            </h2>
            {unestimated.length > 0 ? (
              <Button
                size="sm"
                variant="secondary"
                disabled={batch.isPending}
                onClick={() => batch.mutate(unestimated.slice(0, 50).map((story) => story.id))}
              >
                <Sparkles className="size-3.5" aria-hidden />
                {batch.isPending
                  ? "Estimating…"
                  : `Suggest estimates for ${unestimated.length} unsized`}
              </Button>
            ) : null}
          </div>

          {backlog.isPending ? (
            <div className="space-y-2">
              {Array.from({ length: 6 }).map((_, index) => (
                <Skeleton key={index} className="h-12 rounded-md" />
              ))}
            </div>
          ) : backlog.error ? (
            <ErrorState error={backlog.error} onRetry={() => void backlog.refetch()} />
          ) : stories.length === 0 ? (
            <EmptyState
              icon={ListTodo}
              title="The backlog is empty"
              description="Add a story to start planning."
            />
          ) : (
            <ul className="divide-y rounded-lg border">
              {stories.map((story) => (
                <li key={story.id}>
                  <StoryRow
                    story={story}
                    selected={story.id === selectedId}
                    onSelect={() => setSelectedId(story.id === selectedId ? null : story.id)}
                  />
                </li>
              ))}
            </ul>
          )}
        </section>

        <aside className="space-y-3" aria-label="Story detail">
          {selected ? (
            <>
              <div className="rounded-lg border p-3">
                <p className="text-xs text-muted-foreground">{selected.itemKey}</p>
                <p className="mt-0.5 text-sm font-medium">{selected.title}</p>
                {selected.description ? (
                  <p className="mt-2 text-xs text-muted-foreground">{selected.description}</p>
                ) : null}
              </div>
              <EstimatePanel story={selected} />
            </>
          ) : (
            <div className="rounded-lg border border-dashed p-4 text-center">
              <p className="text-xs text-muted-foreground">
                Select a story to size it, with the model&rsquo;s reasoning alongside.
              </p>
            </div>
          )}
        </aside>
      </div>
    </div>
  );
}

function Metric({
  label,
  value,
  suffix,
  loading,
}: {
  label: string;
  value?: number | null;
  suffix?: string;
  loading?: boolean;
}) {
  return (
    <div className="rounded-lg border p-3">
      <p className="text-xs text-muted-foreground">{label}</p>
      {loading ? (
        <Skeleton className="mt-1 h-7 w-16" />
      ) : (
        <p className="mt-0.5 text-xl font-semibold tabular-nums">
          {value ?? "—"}
          {suffix ? <span className="text-sm text-muted-foreground">{suffix}</span> : null}
        </p>
      )}
    </div>
  );
}

function StoryRow({
  story,
  selected,
  onSelect,
}: {
  story: Story;
  selected: boolean;
  onSelect: () => void;
}) {
  const suggested = story.aiEstimate?.points;
  const showsSuggestion = story.storyPoints == null && suggested != null;

  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={selected}
      className={cn(
        "flex w-full items-center gap-3 px-3 py-2 text-left transition-colors",
        "focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none focus-visible:-outline-offset-2",
        selected ? "bg-muted" : "hover:bg-muted/50",
      )}
    >
      <span className="w-16 shrink-0 text-xs text-muted-foreground tabular-nums">
        {story.itemKey}
      </span>
      <span className="min-w-0 flex-1 truncate text-sm">{story.title}</span>

      {story.blocked ? (
        <Badge variant="destructive" className="shrink-0">
          blocked
        </Badge>
      ) : null}

      {/* A suggested estimate is visibly a suggestion, never mistaken for the team's. */}
      {showsSuggestion ? (
        <Badge
          variant="outline"
          className="shrink-0 gap-1 border-dashed text-muted-foreground tabular-nums"
        >
          <Sparkles className="size-3" aria-hidden />
          {suggested}
          <span className="sr-only">suggested points, not yet accepted</span>
        </Badge>
      ) : (
        <span className="w-8 shrink-0 text-right text-sm tabular-nums">
          {story.storyPoints ?? "—"}
        </span>
      )}
    </button>
  );
}
