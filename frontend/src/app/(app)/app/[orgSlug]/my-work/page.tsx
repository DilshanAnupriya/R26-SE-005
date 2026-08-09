"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { Bot } from "lucide-react";
import { useSession } from "@/components/session-provider";
import {
  groupTasks,
  priorityBreakdown,
  summarise,
  useMyTasks,
  type MyTask,
} from "@/features/my-work/hooks";
import { Card, ProgressBar } from "@/components/design/surfaces";
import { Avatar, PriorityIcon, TypeIcon } from "@/components/design/item-chrome";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";

/** My Work — everything assigned to you across every project, as the design lays it out. */
export default function MyWorkPage() {
  const { user } = useSession();
  const { orgSlug } = useParams<{ orgSlug: string }>();
  const tasks = useMyTasks();

  const rows = tasks.data?.content ?? [];
  const groups = groupTasks(rows);
  const totals = summarise(rows);
  const priorities = priorityBreakdown(rows);

  return (
    <div className="mx-auto max-w-[1240px] animate-fade-up px-1 pb-14">
      <h1 className="mb-1 text-3xl font-semibold tracking-[-0.02em]">My Work</h1>
      <p className="mb-4.5 text-base text-ink-3">
        Everything assigned to you across all projects
      </p>

      <div className="grid items-start gap-5 lg:grid-cols-[1fr_300px]">
        <div>
          <div className="mb-4 flex gap-0.5 border-b border-line">
            <span className="-mb-px border-b-2 border-accent px-3 py-2 text-base font-semibold">
              Assigned to me
            </span>
          </div>

          {tasks.isLoading ? (
            <div className="space-y-2">
              {[0, 1, 2, 3].map((row) => (
                <Skeleton key={row} className="h-11 w-full" />
              ))}
            </div>
          ) : rows.length === 0 ? (
            <Card className="p-8 text-center">
              <p className="text-base text-ink-2">Nothing is assigned to you right now.</p>
              <p className="mt-1 text-sm text-ink-3">
                Work you pick up on a board will appear here.
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
                  <span className="text-sm font-medium text-ink-3">{group.rows.length}</span>
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

        <div className="flex flex-col gap-4">
          <Card className="p-4">
            <div className="mb-4 flex items-center gap-2.5">
              <Avatar name={user?.fullName} id={user?.id} size={38} />
              <div className="min-w-0">
                <div className="truncate text-md font-semibold">{user?.fullName}</div>
                <div className="truncate text-sm text-ink-3">{user?.activeRole ?? "Member"}</div>
              </div>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <Stat value={totals.assigned} label="Open" />
              <Stat value={totals.done} label="Done" tone="var(--green)" />
              <Stat value={totals.overdue} label="Overdue" tone="var(--red)" />
              <Stat value={totals.blocked} label="Blocked" tone="var(--amber)" />
            </div>
          </Card>

          <Card className="p-4">
            <h2 className="mb-3.5 text-base font-semibold">By priority</h2>
            {priorities.map((entry) => (
              <div key={entry.key} className="mb-2.5 flex items-center gap-2.5 last:mb-0">
                <span className="w-13 text-sm text-ink-2">{entry.label}</span>
                <ProgressBar value={entry.width} colour={entry.colour} height={7} className="flex-1" />
                <span className="w-4 text-right text-sm font-semibold">{entry.count}</span>
              </div>
            ))}
          </Card>

          <div className="rounded-xl border border-line bg-[linear-gradient(135deg,var(--accent-soft),transparent)] p-4">
            <div className="mb-1.5 flex items-center gap-2">
              <Bot className="size-[18px] text-accent-text" aria-hidden />
              <span className="text-base font-semibold">Focus suggestion</span>
            </div>
            <p className="text-sm leading-relaxed text-ink-2">
              {totals.overdue > 0
                ? `You have ${totals.overdue} overdue item${totals.overdue === 1 ? "" : "s"}. Clearing ${totals.overdue === 1 ? "it" : "the oldest"} first is usually what unblocks someone else.`
                : totals.blocked > 0
                  ? `${totals.blocked} of your items ${totals.blocked === 1 ? "is" : "are"} blocked. Those need someone else before they can move.`
                  : "Nothing overdue or blocked. A good moment to pick up the highest-priority item."}
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

  return (
    <Link
      href={`/app/${orgSlug}/projects/${task.projectId}/board`}
      className="grid grid-cols-[20px_76px_1fr_auto_auto_92px] items-center gap-2.5 border-b border-line px-3.5 py-2.5 transition-colors last:border-b-0 hover:bg-surface-2"
    >
      <TypeIcon type={task.type} />
      <span className="font-mono text-sm text-ink-3">{task.itemKey}</span>
      <span
        className={cn(
          "truncate text-base font-medium",
          done && "text-ink-3 line-through",
        )}
      >
        {task.title}
      </span>
      {task.blocked ? (
        <span className="rounded-sm bg-red-soft px-1.5 py-0.5 text-2xs font-semibold text-red">
          Blocked
        </span>
      ) : (
        <span />
      )}
      <PriorityIcon priority={task.priority} />
      <span
        className="justify-self-end text-sm whitespace-nowrap"
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
      <div className="text-3xl font-semibold" style={tone && value > 0 ? { color: tone } : undefined}>
        {value}
      </div>
      <div className="text-xs text-ink-3">{label}</div>
    </div>
  );
}
