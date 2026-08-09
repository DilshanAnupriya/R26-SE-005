"use client";

import { useQuery } from "@tanstack/react-query";
import { z } from "zod";
import { useSession } from "@/components/session-provider";
import { pageResponseSchema } from "@/lib/api/schemas";

/**
 * Everything assigned to the signed-in user, across every project.
 *
 * <p>Deliberately not built on the board API, which is scoped to one project. My
 * Work's whole premise is that it spans them — someone on three projects should not
 * have to visit three boards to see what is on their plate.</p>
 */
const myTaskSchema = z.object({
  id: z.uuid(),
  projectId: z.uuid(),
  itemKey: z.string(),
  title: z.string(),
  type: z.string(),
  status: z.string(),
  priority: z.string(),
  assigneeId: z.uuid().nullish(),
  assigneeName: z.string().nullish(),
  dueDate: z.string().nullish(),
  estimateMinutes: z.number().nullish(),
  spentMinutes: z.number().nullish(),
  blocked: z.boolean().default(false),
});

export type MyTask = z.infer<typeof myTaskSchema>;

const myTasksSchema = pageResponseSchema(myTaskSchema);

export function useMyTasks() {
  const { api, status, user } = useSession();
  const assigneeId = user?.id;

  return useQuery({
    queryKey: ["my-work", "tasks", assigneeId],
    queryFn: () =>
      api.get("/tasks", {
        schema: myTasksSchema,
        // topLevelOnly: subtasks belong under their parent, and listing both makes
        // the same work appear twice in a list whose job is "what is on my plate".
        query: { assigneeId, topLevelOnly: true, size: 100, sort: "dueDate,asc" },
      }),
    enabled: status === "authenticated" && !!assigneeId,
  });
}

/** The design's three groups, in the order it shows them. */
export const MY_WORK_GROUPS = [
  { key: "IN_PROGRESS", label: "In progress", colour: "var(--blue)" },
  { key: "TODO", label: "To do", colour: "var(--ink-3)" },
  { key: "IN_REVIEW", label: "In review", colour: "var(--violet)" },
  { key: "BLOCKED", label: "Blocked", colour: "var(--red)" },
  { key: "DONE", label: "Done", colour: "var(--green)" },
] as const;

export function groupTasks(tasks: MyTask[]) {
  return MY_WORK_GROUPS.map((group) => ({
    ...group,
    rows: tasks.filter((task) => task.status === group.key),
  })).filter((group) => group.rows.length > 0);
}

/** Counts for the profile card. */
export function summarise(tasks: MyTask[]) {
  const today = new Date();
  today.setHours(0, 0, 0, 0);

  return {
    assigned: tasks.filter((task) => task.status !== "DONE" && task.status !== "CANCELLED").length,
    done: tasks.filter((task) => task.status === "DONE").length,
    // Overdue means past its date AND still open — a finished task that was late is
    // history, not something to act on.
    overdue: tasks.filter(
      (task) =>
        task.dueDate !== null &&
        task.dueDate !== undefined &&
        new Date(task.dueDate) < today &&
        task.status !== "DONE" &&
        task.status !== "CANCELLED",
    ).length,
    blocked: tasks.filter((task) => task.blocked).length,
  };
}

export function priorityBreakdown(tasks: MyTask[]) {
  const open = tasks.filter((task) => task.status !== "DONE" && task.status !== "CANCELLED");
  const order = [
    { key: "CRITICAL", label: "Critical", colour: "var(--red)" },
    { key: "HIGH", label: "High", colour: "var(--amber)" },
    { key: "MEDIUM", label: "Medium", colour: "var(--blue)" },
    { key: "LOW", label: "Low", colour: "var(--ink-3)" },
  ] as const;

  const max = Math.max(1, ...order.map((p) => open.filter((t) => t.priority === p.key).length));

  return order.map((entry) => {
    const count = open.filter((task) => task.priority === entry.key).length;
    return { ...entry, count, width: (count / max) * 100 };
  });
}
