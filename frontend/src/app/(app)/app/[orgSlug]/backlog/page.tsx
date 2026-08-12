"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useProjects } from "@/features/projects/hooks";
import { workableProjects } from "@/features/projects/api";
import { Card } from "@/components/design/surfaces";
import { ProjectKey, StatusPill } from "@/components/design/item-chrome";
import { Skeleton } from "@/components/ui/skeleton";

/**
 * Boards and backlogs belong to a project — the backend scopes sprints, workflow
 * states and ranking to one. This top-level entry therefore picks a project rather
 * than guessing, which would silently show one team's work to another.
 */
export default function Page() {
  const { orgSlug } = useParams<{ orgSlug: string }>();
  // Everything except finished projects. Filtering the query on ACTIVE hid every
  // project the moment it was created, because a new project starts as PLANNING.
  const projects = useProjects();
  const items = workableProjects(projects.data?.content ?? []);

  return (
    <div className="mx-auto max-w-[900px] animate-fade-up px-1 pb-14">
      <h1 className="text-2xl font-semibold tracking-[-0.02em]">Backlog & Sprints</h1>
      <p className="mt-1 mb-5 text-base text-ink-3">Choose a project to open the backlog and sprints for.</p>

      {projects.isLoading ? (
        <div className="space-y-2">
          {[0, 1, 2].map((row) => (
            <Skeleton key={row} className="h-14 w-full" />
          ))}
        </div>
      ) : items.length === 0 ? (
        <Card className="p-8 text-center">
          <p className="text-base text-ink-2">No projects yet.</p>
          <Link href={`/app/${orgSlug}/projects`} className="mt-2 inline-block text-sm font-medium text-accent-text hover:underline">
            Go to projects
          </Link>
        </Card>
      ) : (
        <Card>
          {items.map((project) => (
            <Link
              key={project.id}
              href={`/app/${orgSlug}/projects/${project.id}/sprints`}
              className="flex items-center gap-3 border-b border-line px-4 py-3 transition-colors last:border-b-0 hover:bg-surface-2"
            >
              <ProjectKey projectKey={project.projectKey} />
              <div className="min-w-0 flex-1">
                <div className="truncate text-base font-semibold">{project.name}</div>
                <div className="text-sm text-ink-3">{project.status}</div>
              </div>
              <StatusPill tone={project.health === "ON_TRACK" ? "green" : project.health === "AT_RISK" ? "amber" : "red"}>
                {project.health === "ON_TRACK" ? "On track" : project.health === "AT_RISK" ? "At risk" : "Off track"}
              </StatusPill>
            </Link>
          ))}
        </Card>
      )}
    </div>
  );
}
