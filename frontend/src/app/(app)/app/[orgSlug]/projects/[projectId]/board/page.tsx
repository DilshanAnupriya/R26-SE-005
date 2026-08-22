"use client";

import { use, useState, useEffect } from "react";
import Link from "next/link";
import { ArrowLeft, Plus, Radio } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState, ErrorState } from "@/components/feedback/states";
import { useProjectRealtime, useRealtime } from "@/components/realtime-provider";
import { KanbanBoard } from "@/features/board/components/kanban-board";
import { useAssignTask, useMoveTask, useTaskBoard } from "@/features/board/hooks";
import { useProject } from "@/features/projects/hooks";
import { useSprints } from "@/features/sprints/hooks";
import { useMembers } from "@/features/organizations/hooks";
import { CreateStoryDialog } from "@/features/backlog/components/create-story-dialog";

export default function BoardPage({
  params,
}: {
  params: Promise<{ orgSlug: string; projectId: string }>;
}) {
  const { orgSlug, projectId } = use(params);
  const [sprintFilter, setSprintFilter] = useState<string>("");
  const [createOpen, setCreateOpen] = useState(false);

  const project = useProject(projectId);
  const sprints = useSprints(projectId);
  const members = useMembers();

  useEffect(() => {
    if (!sprintFilter && sprints.data?.content) {
      const active = sprints.data.content.find((s) => s.status === "ACTIVE");
      if (active) {
        setSprintFilter(active.id);
      }
    }
  }, [sprints.data, sprintFilter]);

  const board = useTaskBoard(projectId, sprintFilter || undefined);
  const move = useMoveTask(projectId);
  const assign = useAssignTask(projectId);
  const { connected } = useRealtime();

  // Someone else's drag or assignment updates this board without a refresh.
  useProjectRealtime(projectId);

  const memberList = members.data?.content ?? (Array.isArray(members.data) ? members.data : []);

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

          <Button size="sm" onClick={() => setCreateOpen(true)} className="gap-1.5 h-8">
            <Plus className="size-3.5" />
            <span>Create Item</span>
          </Button>
        </div>
      </div>

      <p className="text-xs text-muted-foreground">
        Drag a card to move between columns, or click the Assignee button on any card to assign or reassign developers.
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
          disabled={move.isPending || assign.isPending}
          onMove={(taskId, state) => move.mutate({ taskId, state })}
          onAssign={(taskId, assigneeId) => assign.mutate({ taskId, assigneeId })}
          members={memberList}
        />
      ) : (
        <EmptyState
          title="No board columns"
          description="This project has no workflow states configured yet."
        />
      )}

      <CreateStoryDialog
        projectId={projectId}
        open={createOpen}
        onOpenChange={setCreateOpen}
        defaultSprintId={sprintFilter || undefined}
        onCreated={() => {
          void board.refetch();
        }}
      />
    </div>
  );
}
