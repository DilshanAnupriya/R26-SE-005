"use client";

import { use, useState } from "react";
import Link from "next/link";
import { FolderKanban, Plus } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState, ErrorState } from "@/components/feedback/states";
import { Button } from "@/components/ui/button";
import { useProjects } from "@/features/projects/hooks";
import { CreateProjectDialog } from "@/features/projects/components/create-project-dialog";
import type { Project } from "@/lib/api/delivery-schemas";
import { cn } from "@/lib/utils";

const HEALTH_TONE = {
  ON_TRACK: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
  AT_RISK: "bg-amber-500/10 text-amber-700 dark:text-amber-400",
  OFF_TRACK: "bg-destructive/15 text-destructive",
} as const;

export default function ProjectsPage({ params }: { params: Promise<{ orgSlug: string }> }) {
  const { orgSlug } = use(params);
  const { data, isPending, error, refetch } = useProjects();
  const [creating, setCreating] = useState(false);

  return (
    <div className="mx-auto max-w-6xl space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Projects</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            Every project in this organization, with its explained health.
          </p>
        </div>
        <Button size="sm" onClick={() => setCreating(true)}>
          <Plus className="size-4" aria-hidden />
          New project
        </Button>
      </div>

      {isPending ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 6 }).map((_, index) => (
            <Skeleton key={index} className="h-32 rounded-lg" />
          ))}
        </div>
      ) : error ? (
        <ErrorState error={error} onRetry={() => void refetch()} />
      ) : data && data.content.length > 0 ? (
        <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {data.content.map((project) => (
            <li key={project.id}>
              <ProjectCard project={project} orgSlug={orgSlug} />
            </li>
          ))}
        </ul>
      ) : (
        <EmptyState
          icon={FolderKanban}
          title="No projects yet"
          description="A project holds the backlog, the sprints and the board. Create one to get started."
          action={
            <Button onClick={() => setCreating(true)}>
              <Plus className="size-4" aria-hidden />
              Create a project
            </Button>
          }
        />
      )}

      <CreateProjectDialog open={creating} onOpenChange={setCreating} />
    </div>
  );
}

function ProjectCard({ project, orgSlug }: { project: Project; orgSlug: string }) {
  const progress = Math.max(0, Math.min(100, Math.round(project.progressPct)));

  return (
    <Link
      href={`/app/${orgSlug}/projects/${project.id}`}
      className="block h-full rounded-lg border p-4 transition-colors hover:bg-muted/40 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="truncate font-medium">{project.name}</p>
          <p className="text-xs text-muted-foreground">{project.projectKey}</p>
        </div>
        <Badge className={cn("border-0 shrink-0", HEALTH_TONE[project.health])}>
          {project.health.replace("_", " ").toLowerCase()}
        </Badge>
      </div>

      {project.description ? (
        <p className="mt-2 line-clamp-2 text-xs text-muted-foreground">{project.description}</p>
      ) : null}

      <div className="mt-4">
        <div
          className="h-1.5 overflow-hidden rounded-full bg-muted"
          role="meter"
          aria-valuenow={progress}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-label={`${project.name} progress: ${progress} percent`}
        >
          <div className="h-full rounded-full bg-primary" style={{ width: `${progress}%` }} />
        </div>
        <div className="mt-1.5 flex items-center justify-between text-[11px] text-muted-foreground">
          <span className="tabular-nums">{progress}% complete</span>
          <span>
            {project.memberCount} {project.memberCount === 1 ? "member" : "members"}
          </span>
        </div>
      </div>
    </Link>
  );
}
