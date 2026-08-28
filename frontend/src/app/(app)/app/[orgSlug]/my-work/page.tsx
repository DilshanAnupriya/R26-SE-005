"use client";

import { useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { 
  Bot, 
  User, 
  Users, 
  Sparkles, 
  CheckCircle2, 
  Layers, 
  Layout, 
  Server, 
  Database,
  ArrowRight
} from "lucide-react";
import { useSession } from "@/components/session-provider";
import {
  groupTasks,
  priorityBreakdown,
  summarise,
  useMyTasks,
  useTeamDevelopers,
  type MyTask,
  type TeamDeveloper,
} from "@/features/my-work/hooks";
import { Card, ProgressBar } from "@/components/design/surfaces";
import { Avatar, PriorityIcon, TypeIcon } from "@/components/design/item-chrome";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";

/** My Work — everything assigned to you or a specific developer across all projects. */
export default function MyWorkPage() {
  const { user } = useSession();
  const { orgSlug } = useParams<{ orgSlug: string }>();
  
  const { data: teamDevs = [], isLoading: devsLoading } = useTeamDevelopers();
  const [selectedDevId, setSelectedDevId] = useState<string>("me");

  const selectedDeveloper: TeamDeveloper | null = selectedDevId === "me"
    ? null
    : teamDevs.find((d) => String(d.id) === selectedDevId || d.devId === selectedDevId) || null;

  const tasks = useMyTasks(selectedDeveloper);

  const rows = tasks.data ?? [];
  const groups = groupTasks(rows);
  const totals = summarise(rows);
  const priorities = priorityBreakdown(rows);

  const activeDevName = selectedDeveloper ? selectedDeveloper.name : user?.fullName || "Developer";
  const activeDevRole = selectedDeveloper ? selectedDeveloper.role : user?.activeRole ?? "Member";
  const activeDevSpecialization = selectedDeveloper ? selectedDeveloper.specialization : "Full-Stack Engineer";

  return (
    <div className="mx-auto max-w-[1240px] animate-fade-up px-1 pb-14">
      {/* Header with Developer Switcher */}
      <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="mb-1 text-3xl font-semibold tracking-[-0.02em]">My Work</h1>
          <p className="text-base text-ink-3">
            Tasks, user stories, and sprint assignments across all projects.
          </p>
        </div>

        {/* Developer Selector Filter */}
        <div className="flex items-center gap-2.5 rounded-xl border border-line bg-surface-1 p-1.5 shadow-sm">
          <Users className="ml-2 size-4 text-ink-3" />
          <span className="text-xs font-semibold text-ink-3">Developer:</span>
          <select
            value={selectedDevId}
            onChange={(e) => setSelectedDevId(e.target.value)}
            className="rounded-lg border-0 bg-surface-2 px-3 py-1.5 text-xs font-semibold text-foreground focus:ring-1 focus:ring-accent focus:outline-none"
          >
            <option value="me">👤 Me ({user?.fullName || "Logged-in User"})</option>
            <optgroup label="Team Developers">
              {teamDevs.map((d) => (
                <option key={d.id} value={String(d.id)}>
                  {d.name} ({d.role} • {d.devId})
                </option>
              ))}
            </optgroup>
          </select>
        </div>
      </div>

      <div className="grid items-start gap-5 lg:grid-cols-[1fr_320px]">
        {/* Main Work Column */}
        <div>
          <div className="mb-4 flex items-center justify-between border-b border-line pb-2">
            <div className="flex items-center gap-2">
              <span className="text-base font-semibold text-foreground">
                {selectedDeveloper ? `Tasks assigned to ${selectedDeveloper.name}` : "Assigned to me"}
              </span>
              <span className="rounded-full bg-accent-soft px-2 py-0.5 text-xs font-bold text-accent-text">
                {rows.length} total
              </span>
            </div>
            {totals.totalPoints > 0 && (
              <span className="text-xs font-bold text-ink-3">
                {totals.openPoints} / {totals.totalPoints} SPs Open
              </span>
            )}
          </div>

          {tasks.isLoading ? (
            <div className="space-y-2">
              {[0, 1, 2, 3].map((row) => (
                <Skeleton key={row} className="h-12 w-full" />
              ))}
            </div>
          ) : rows.length === 0 ? (
            <Card className="p-10 text-center">
              <p className="text-base font-medium text-ink-2">
                No tasks or stories currently assigned to {activeDevName}.
              </p>
              <p className="mt-1 text-sm text-ink-3">
                Assigned backlog stories, sprint tasks, and AI allocations will appear here.
              </p>
            </Card>
          ) : (
            groups.map((group) => (
              <section key={group.key} className="mb-5.5">
                <div className="mb-2.5 flex items-center gap-2">
                  <span
                    className="size-2 rounded-sm"
                    style={{ background: group.colour }}
                    aria-hidden
                  />
                  <h2 className="text-base font-semibold">{group.label}</h2>
                  <span className="text-sm font-medium text-ink-3">({group.rows.length})</span>
                </div>

                <Card>
                  {group.rows.map((task) => (
                    <TaskRow key={task.id} task={task} orgSlug={orgSlug} />
                  ))}
                </Card>
              </section>
            ))
          )}
        </div>

        {/* Right Sidebar: Profile & Metrics */}
        <div className="flex flex-col gap-4">
          <Card className="p-5">
            <div className="mb-4 flex items-center gap-3">
              <Avatar 
                name={activeDevName} 
                id={selectedDeveloper?.userId || user?.id} 
                size={42} 
              />
              <div className="min-w-0">
                <div className="truncate text-base font-bold">{activeDevName}</div>
                <div className="truncate text-xs text-ink-3">
                  {activeDevRole} • {activeDevSpecialization}
                </div>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-3 border-t border-line/60 pt-4">
              <Stat value={totals.assigned} label="Open Tasks" />
              <Stat value={totals.openPoints} label="Open SPs" tone="var(--blue)" />
              <Stat value={totals.done} label="Completed Tasks" tone="var(--green)" />
              <Stat value={totals.blocked} label="Blocked" tone="var(--amber)" />
            </div>
          </Card>

          <Card className="p-5">
            <h2 className="mb-3.5 text-sm font-bold uppercase tracking-wider text-ink-3">
              By Priority
            </h2>
            {priorities.map((entry) => (
              <div key={entry.key} className="mb-2.5 flex items-center gap-2.5 last:mb-0">
                <span className="w-14 text-xs font-semibold text-ink-2">{entry.label}</span>
                <ProgressBar value={entry.width} colour={entry.colour} height={7} className="flex-1" />
                <span className="w-4 text-right text-xs font-bold">{entry.count}</span>
              </div>
            ))}
          </Card>

          <div className="rounded-2xl border border-line bg-[linear-gradient(135deg,var(--accent-soft),transparent)] p-5 shadow-sm">
            <div className="mb-2 flex items-center gap-2">
              <Bot className="size-5 text-accent-text" aria-hidden />
              <span className="text-sm font-bold text-foreground">AI Workload Insight</span>
            </div>
            <p className="text-xs leading-relaxed text-ink-2">
              {totals.assigned === 0
                ? `${activeDevName} has no pending tasks assigned. Ready to take on new sprint items!`
                : totals.blocked > 0
                ? `${activeDevName} has ${totals.blocked} blocked item(s). Resolving dependencies will accelerate sprint delivery.`
                : `${activeDevName} has ${totals.assigned} active item(s) (${totals.openPoints} SPs). Highest priority tasks should be focused on first.`}
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}

function TaskRow({ task, orgSlug }: { task: MyTask; orgSlug: string }) {
  const done = task.status === "DONE";
  const overdue = isOverdue(task);

  const targetHref = task.projectId 
    ? `/app/${orgSlug}/projects/${task.projectId}/board`
    : `/app/${orgSlug}/board`;

  return (
    <Link
      href={targetHref}
      className="grid grid-cols-[20px_76px_1fr_auto_auto_auto_80px] items-center gap-3 border-b border-line px-4 py-3 transition-colors last:border-b-0 hover:bg-surface-2"
    >
      <TypeIcon type={task.type} />
      
      {/* Item Key */}
      <span className="font-mono text-xs font-bold text-ink-3">
        {task.itemKey}
      </span>

      {/* Title */}
      <span
        className={cn(
          "truncate text-sm font-medium",
          done && "text-ink-3 line-through",
        )}
      >
        {task.title}
      </span>

      {/* Story Points Badge */}
      {task.storyPoints !== undefined && task.storyPoints !== null && (
        <span className="rounded-md bg-surface-3 px-2 py-0.5 text-2xs font-bold text-foreground border border-line">
          {task.storyPoints} SPs
        </span>
      )}

      {/* Tech Breakdown if available */}
      {task.req_frontend !== undefined && (
        <span className="hidden sm:inline-flex items-center gap-1 text-[10px] text-ink-3 bg-surface-2 px-1.5 py-0.5 rounded border border-line/60">
          FE:{task.req_frontend} BE:{task.req_backend} DB:{task.req_db}
        </span>
      )}

      {/* Blocked Badge */}
      {task.blocked ? (
        <span className="rounded-sm bg-red-soft px-1.5 py-0.5 text-2xs font-semibold text-red">
          Blocked
        </span>
      ) : (
        <span />
      )}

      {/* Priority */}
      <PriorityIcon priority={task.priority} />

      {/* Due Date */}
      <span
        className="justify-self-end text-xs whitespace-nowrap"
        style={{ color: overdue ? "var(--red)" : "var(--ink-2)" }}
      >
        {task.dueDate ? formatDue(task.dueDate) : "—"}
      </span>
    </Link>
  );
}

function isOverdue(task: MyTask): boolean {
  if (!task.dueDate || task.status === "DONE" || task.status === "CANCELLED") return false;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return new Date(task.dueDate) < today;
}

function formatDue(iso: string): string {
  return new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short" }).format(new Date(iso));
}

function Stat({ value, label, tone }: { value: number; label: string; tone?: string }) {
  return (
    <div>
      <div className="text-2xl font-bold" style={tone && value > 0 ? { color: tone } : undefined}>
        {value}
      </div>
      <div className="text-xs text-ink-3">{label}</div>
    </div>
  );
}
