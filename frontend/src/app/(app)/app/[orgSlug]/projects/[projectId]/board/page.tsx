"use client";

import { use, useState } from "react";
import Link from "next/link";
import { ArrowLeft, Radio } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState, ErrorState } from "@/components/feedback/states";
import { useProjectRealtime, useRealtime } from "@/components/realtime-provider";
import { KanbanBoard } from "@/features/board/components/kanban-board";
import { useMoveTask, useTaskBoard } from "@/features/board/hooks";
import { useProject } from "@/features/projects/hooks";
import { useSprints } from "@/features/sprints/hooks";

export default function BoardPage({
  params,
}: {
  params: Promise<{ orgSlug: string; projectId: string }>;
}) {
  const { orgSlug, projectId } = use(params);
  const [sprintFilter, setSprintFilter] = useState<string>("");

  const project = useProject(projectId);
  const sprints = useSprints(projectId);
  const board = useTaskBoard(projectId, sprintFilter || undefined);
  const move = useMoveTask(projectId);
  const { connected } = useRealtime();

  // Someone else's drag updates this board without a refresh.
  useProjectRealtime(projectId);

  return (
    <div className="animate-fade-up space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <Link
            href={`/app/${orgSlug}/projects/${projectId}`}
            className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
          >
            <ArrowLeft className="size-3" aria-hidden />
            {project.data?.name ?? "Project"}
          </Link>
          <h1 className="mt-1 text-2xl font-semibold tracking-tight">Board</h1>
        </div>

        <div className="flex items-center gap-3">
          {/* Live is worth stating: a board that has silently stopped updating looks
              exactly like a board where nothing is happening. */}
          <Badge variant={connected ? "secondary" : "outline"} className="gap-1.5">
            <Radio
              className={connected ? "size-3 text-emerald-600" : "size-3 text-muted-foreground"}
              aria-hidden
            />
            {connected ? "Live" : "Offline"}
          </Badge>

          <label className="text-xs">
            <span className="sr-only">Filter by sprint</span>
            <select
              className="h-8 rounded-md border bg-background px-2 text-sm focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
              value={sprintFilter}
              onChange={(event) => setSprintFilter(event.target.value)}
            >
              <option value="">All work</option>
              {(sprints.data?.content ?? []).map((sprint) => (
                <option key={sprint.id} value={sprint.id}>
                  {sprint.name}
                </option>
              ))}
            </select>
          </label>
        </div>
      </div>

      <p className="text-xs text-muted-foreground">
        Drag a card, or focus its handle and press Enter to pick it up — arrows move it
        between columns, Enter drops it.
      </p>

      {board.isPending ? (
        <div className="flex gap-3">
          {Array.from({ length: 4 }).map((_, index) => (
            <Skeleton key={index} className="h-96 w-72 shrink-0 rounded-lg" />
          ))}
        </div>
      ) : board.error ? (
        <ErrorState error={board.error} onRetry={() => void board.refetch()} />
      ) : board.data && board.data.columns.length > 0 ? (
        <KanbanBoard
          board={board.data}
          disabled={move.isPending}
          onMove={(taskId, state) => move.mutate({ taskId, state })}
        />
      ) : (
        <EmptyState
          title="No board columns"
          description="This project has no workflow states configured yet."
        />
      )}
    </div>
  );
}
