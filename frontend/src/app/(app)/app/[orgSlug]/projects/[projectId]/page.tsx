"use client";

import { use, useState } from "react";
import Link from "next/link";
import { ArrowLeft, ListTodo, Plus, Sparkles } from "lucide-react";
import { CommentThread } from "@/features/comments/components/comment-thread";
import { useProjectMembers } from "@/features/projects/hooks";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { CreateStoryDialog } from "@/features/backlog/components/create-story-dialog";
import { EstimationContextDialog } from "@/features/projects/components/estimation-context-dialog";
import { StoryPointsField } from "@/features/backlog/components/story-points-field";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState, ErrorState } from "@/components/feedback/states";
import { EstimatePanel } from "@/features/ai/components/estimate-panel";
import { useEstimateBatch } from "@/features/ai/hooks";
import { useBacklog } from "@/features/backlog/hooks";
import { useProject, useProjectOverview } from "@/features/projects/hooks";
import { useSprints, useAddSprintItems, useRemoveSprintItem } from "@/features/sprints/hooks";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Label } from "@/components/ui/label";
import type { Story, Sprint } from "@/lib/api/delivery-schemas";
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
  const members = useProjectMembers(projectId);
  const batch = useEstimateBatch();
  const sprints = useSprints(projectId);
  const addSprintItem = useAddSprintItems(projectId);
  const removeSprintItem = useRemoveSprintItem(projectId);

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [creatingStory, setCreatingStory] = useState(false);
  const [editingContext, setEditingContext] = useState(false);

  // Counts only the fields a person can supply. Velocity and completion rate are
  // measured from completed sprints, so a project with no history is not "missing"
  // them — it just has not run a sprint yet, and no form would fix that.
  const estimationGaps = [
    project.data?.domain,
    project.data?.techStack?.[0],
    project.data?.teamSize,
    project.data?.devExperienceLevel,
  ].filter((value) => value == null || value === "").length;
  const stories = backlog.data?.content ?? [];
  const selected = stories.find((story) => story.id === selectedId) ?? null;

  const sprintList = sprints.data?.content ?? [];
  const sprintMap = new Map<string, Sprint>(sprintList.map((s) => [s.id, s]));

  const unestimated = stories.filter((story) => story.storyPoints == null);

  if (project.error) {
    return <ErrorState error={project.error} onRetry={() => void project.refetch()} />;
  }

  return (
    <div className="animate-fade-up mx-auto max-w-6xl space-y-6">
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

            {/* Surfaced on the project header rather than buried in settings, because
                the gap it fixes is invisible until you read a model's explanation:
                these estimates are scored against training medians until somebody
                fills the fields in, and nothing else on screen says so. */}
            {project.data && (
              <Button
                variant={estimationGaps > 0 ? "outline" : "ghost"}
                size="sm"
                className={cn("ml-auto", estimationGaps > 0 && "border-amber-500/50")}
                onClick={() => setEditingContext(true)}
              >
                <Sparkles className="size-3.5" aria-hidden />
                Estimation context
                {estimationGaps > 0 && (
                  <Badge variant="secondary" className="ml-1 px-1.5 py-0 text-2xs">
                    {estimationGaps} missing
                  </Badge>
                )}
              </Button>
            )}
          </div>
        )}

        {/* Sub-navigation rather than more sidebar entries: these only exist inside a
            project, and a global nav that changes shape per route is disorienting. */}
        <nav aria-label="Project sections" className="mt-3 flex gap-1">
          {[
            ["", "Backlog"],
            ["/board", "Board"],
            ["/sprints", "Sprints"],
          ].map(([suffix, label]) => (
            <Link
              key={label}
              href={`/app/${orgSlug}/projects/${projectId}${suffix}`}
              aria-current={suffix === "" ? "page" : undefined}
              className={cn(
                "rounded-md px-2.5 py-1 text-sm transition-colors",
                "focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none",
                suffix === "" ? "bg-muted font-medium" : "text-muted-foreground hover:bg-muted/60",
              )}
            >
              {label}
            </Link>
          ))}
        </nav>
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
          value={overview.data?.completedStoryPoints}
          suffix={overview.data ? ` / ${overview.data.totalStoryPoints}` : undefined}
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
            <div className="flex flex-wrap items-center gap-2">
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
              <Button size="sm" onClick={() => setCreatingStory(true)}>
                <Plus className="size-3.5" aria-hidden />
                New item
              </Button>
            </div>
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
                    sprintName={story.sprintId ? sprintMap.get(story.sprintId)?.name : undefined}
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
              <StoryDetail story={selected} />

              {/* Sprint Assignment */}
              <div className="rounded-lg border p-3 space-y-1.5">
                <Label className="text-xs text-muted-foreground">Sprint</Label>
                <Select
                  value={selected.sprintId ?? "__none__"}
                  onValueChange={(val) => {
                    if (val === "__none__") {
                      if (selected.sprintId) {
                        removeSprintItem.mutate({ sprintId: selected.sprintId, storyId: selected.id });
                      }
                    } else {
                      addSprintItem.mutate({ sprintId: val, storyIds: [selected.id] });
                    }
                  }}
                  disabled={addSprintItem.isPending || removeSprintItem.isPending}
                >
                  <SelectTrigger className="w-full h-8 text-xs">
                    <SelectValue placeholder="Assign to a sprint…" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="__none__">Backlog (Unassigned)</SelectItem>
                    {sprintList.map((sp) => (
                      <SelectItem key={sp.id} value={sp.id}>
                        {sp.name} ({sp.status.toLowerCase()})
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              {/* Sizing by hand comes first: it is the team's own number, and it must
                  not depend on a model having been asked. */}
              <StoryPointsField story={selected} projectId={projectId} />

              {/* Keyed by story so no part of the panel — a suggestion, a pending
                  request, a verdict — can carry over to the next story selected. */}
              <EstimatePanel key={selected.id} story={selected} />

              <div className="rounded-lg border p-3">
                <CommentThread
                  entityType="STORY"
                  entityId={selected.id}
                  people={(members.data ?? [])
                    .filter((member) => member.fullName)
                    .map((member) => ({
                      id: member.userId,
                      fullName: member.fullName!,
                      avatarUrl: member.avatarUrl,
                    }))}
                />
              </div>
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

      {project.data && (
        <EstimationContextDialog
          project={project.data}
          open={editingContext}
          onOpenChange={setEditingContext}
        />
      )}

      <CreateStoryDialog
        projectId={projectId}
        open={creatingStory}
        onOpenChange={setCreatingStory}
        // Selecting it straight away puts the new item in the sizing panel, which is
        // almost always the next thing you want to do with a freshly written story.
        onCreated={setSelectedId}
      />
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
    <div className="rounded-lg border border-line bg-surface p-3.5">
      <p className="text-xs text-ink-2">{label}</p>
      {loading ? (
        <Skeleton className="mt-1.5 h-8 w-16" />
      ) : (
        // The number is the whole point of a metric tile, so it carries the weight and
        // the qualifier steps back rather than sharing it.
        <p className="mt-1 text-3xl font-semibold tabular-nums tracking-tight text-ink">
          {value ?? "—"}
          {suffix ? (
            <span className="ml-0.5 text-base font-normal tabular-nums text-ink-2">
              {suffix}
            </span>
          ) : null}
        </p>
      )}
    </div>
  );
}

function StoryRow({
  story,
  sprintName,
  selected,
  onSelect,
}: {
  story: Story;
  sprintName?: string;
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
        "group grid w-full grid-cols-[3.75rem_minmax(0,1fr)_auto_2.5rem] items-center gap-3",
        "px-3 py-2.5 text-left transition-colors duration-150",
        "focus-visible:ring-2 focus-visible:ring-accent focus-visible:outline-none focus-visible:-outline-offset-2",
        selected ? "bg-accent-soft" : "hover:bg-surface-3",
      )}
    >
      {/* Mono, so a column of keys aligns and stops competing with the titles it sits
          beside. It is a reference number, not content — the row's job is the title
          and the estimate. */}
      <span
        className={cn(
          "truncate font-mono text-2xs tabular-nums transition-colors",
          selected ? "text-accent-text" : "text-ink-2",
        )}
      >
        {story.itemKey}
      </span>

      <span
        className={cn(
          "min-w-0 truncate text-base transition-colors",
          selected ? "font-medium text-ink" : "text-ink-2 group-hover:text-ink",
        )}
      >
        {story.title}
      </span>

      <span className="flex shrink-0 items-center gap-1.5">
        {sprintName ? (
          <span className="rounded bg-surface-3 px-1.5 py-0.5 text-2xs text-ink-2">
            {sprintName}
          </span>
        ) : null}
        {/* Blocked keeps its full weight. It is the one thing in the row that should
            interrupt a scan. */}
        {story.blocked ? (
          <span className="rounded bg-red-soft px-1.5 py-0.5 text-2xs font-medium text-red">
            blocked
          </span>
        ) : null}
      </span>

      {/* The estimate is what a backlog is scanned for, so it gets the row's strongest
          weight and a fixed column to line up in. Suggested and accepted values share
          that column deliberately: the eye reads down one line of numbers, and the
          dashed outline — not a different position — is what marks a value as the
          model's rather than the team's. */}
      {showsSuggestion ? (
        <span className="justify-self-end rounded border border-dashed border-accent px-1.5 py-0.5 text-sm font-medium tabular-nums text-accent-text">
          {suggested}
          <span className="sr-only"> suggested points, not yet accepted</span>
        </span>
      ) : (
        <span
          className={cn(
            "justify-self-end text-md font-semibold tabular-nums",
            story.storyPoints == null ? "font-normal text-ink-3" : "text-ink",
          )}
        >
          {story.storyPoints ?? "—"}
        </span>
      )}
    </button>
  );
}


/**
 * What a story actually says, beside the estimate that was made from it.
 *
 * <p>Every field here was already on the story payload and none of it was rendered —
 * the panel showed a key, a title and a description, then jumped straight to a number.
 * Acceptance criteria and labels are not decoration in this product: they are two of
 * the three things {@code num_components} is summed from, so a reader looking at an
 * estimate they disagree with could not see the evidence it was built on.</p>
 */
function StoryDetail({ story }: { story: Story }) {
  const criteria = (story.acceptanceCriteria ?? "")
    .split("\n")
    .map((line) => line.replace(/^[-*\u2022]\s*/, "").trim())
    .filter(Boolean);

  return (
    <div className="rounded-lg border border-line bg-surface">
      <div className="border-b border-line px-3 py-2.5">
        <div className="flex items-center gap-2">
          <span className="font-mono text-2xs text-ink-2">{story.itemKey}</span>
          <span className="rounded bg-surface-3 px-1.5 py-0.5 text-2xs text-ink-2">
            {story.type.toLowerCase().replace("_", " ")}
          </span>
          {story.priority !== "MEDIUM" ? (
            <span className="rounded bg-surface-3 px-1.5 py-0.5 text-2xs text-ink-2">
              {story.priority.toLowerCase()}
            </span>
          ) : null}
        </div>
        <h2 className="mt-1 text-md font-medium text-ink">{story.title}</h2>
        {story.description ? (
          <p className="mt-1.5 text-xs leading-relaxed text-ink-2">{story.description}</p>
        ) : (
          <p className="mt-1.5 text-xs text-ink-3">No description.</p>
        )}
      </div>

      {/* Criteria as a real list rather than a wall of newline-separated text — the
          count is what the estimator reads, so the reader should be able to count them
          at a glance too. */}
      <div className="border-b border-line px-3 py-2.5">
        <p className="text-2xs font-medium text-ink-2">
          Acceptance criteria
          {criteria.length > 0 ? (
            <span className="ml-1 tabular-nums text-ink-3">({criteria.length})</span>
          ) : null}
        </p>
        {criteria.length > 0 ? (
          <ul className="mt-1.5 space-y-1">
            {criteria.map((line) => (
              <li key={line} className="flex gap-1.5 text-xs leading-relaxed text-ink-2">
                <span aria-hidden className="mt-1.5 size-1 shrink-0 rounded-full bg-ink-3" />
                <span className="min-w-0">{line}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="mt-1 text-xs text-ink-3">
            None yet — the estimator reads these, so a story without them is sized on
            structure alone.
          </p>
        )}
      </div>

      <dl className="grid grid-cols-2 gap-x-3 gap-y-2 px-3 py-2.5 text-xs">
        <div>
          <dt className="text-2xs text-ink-3">Status</dt>
          <dd className="text-ink-2">{story.status.toLowerCase().replace("_", " ")}</dd>
        </div>
        <div>
          <dt className="text-2xs text-ink-3">Assignee</dt>
          <dd className="truncate text-ink-2">{story.assigneeName ?? "Unassigned"}</dd>
        </div>
        {story.dueDate ? (
          <div>
            <dt className="text-2xs text-ink-3">Due</dt>
            <dd className="tabular-nums text-ink-2">{story.dueDate}</dd>
          </div>
        ) : null}
        {story.reopenedCount > 0 ? (
          <div>
            <dt className="text-2xs text-ink-3">Reopened</dt>
            <dd className="tabular-nums text-amber">{story.reopenedCount}×</dd>
          </div>
        ) : null}
      </dl>

      {story.labels.length > 0 ? (
        <div className="flex flex-wrap gap-1 border-t border-line px-3 py-2.5">
          {story.labels.map((label) => (
            <span
              key={label.id}
              className="rounded bg-surface-3 px-1.5 py-0.5 text-2xs text-ink-2"
            >
              {label.name}
            </span>
          ))}
        </div>
      ) : null}

      {story.blocked ? (
        <p className="border-t border-line bg-red-soft px-3 py-2 text-xs text-red">
          Blocked{story.blockedReason ? ` — ${story.blockedReason}` : ""}
        </p>
      ) : null}
    </div>
  );
}
