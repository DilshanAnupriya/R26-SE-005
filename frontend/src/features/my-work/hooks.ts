"use client";
import { backendOrigin } from "@/lib/env";

import { useQuery } from "@tanstack/react-query";
import { z } from "zod";
import { useSession } from "@/components/session-provider";
import { getTokenStore } from "@/lib/api/token-store";
import { getActiveOrganization } from "@/lib/api/org-context";

async function authFetch(url: string, options: RequestInit = {}) {
  const tokenStore = getTokenStore();
  const orgId = getActiveOrganization();
  
  const getHeaders = (token: string | null) => {
    const headers = new Headers(options.headers || {});
    if (token && !headers.has("Authorization")) {
      headers.set("Authorization", `Bearer ${token}`);
    }
    if (orgId && !headers.has("X-Organization-Id")) {
      headers.set("X-Organization-Id", orgId);
    }
    return headers;
  };

  const token = tokenStore.get();
  let res = await fetch(url, { ...options, headers: getHeaders(token) });
  
  if (res.status === 401) {
    try {
      const newToken = await tokenStore.refresh();
      res = await fetch(url, { ...options, headers: getHeaders(newToken) });
    } catch (err) {
      console.error("Token refresh failed:", err);
    }
  }
  
  return res;
}

export interface MyTask {
  id: string;
  projectId?: string;
  itemKey: string;
  title: string;
  type: string;
  status: string;
  priority: string;
  assigneeId?: string | null;
  assigneeName?: string | null;
  dueDate?: string | null;
  estimateMinutes?: number | null;
  spentMinutes?: number | null;
  blocked: boolean;
  storyPoints?: number | null;
  req_frontend?: number;
  req_backend?: number;
  req_db?: number;
  task_complexity?: number;
}

export interface TeamDeveloper {
  id: number;
  userId?: string;
  devId: string;
  name: string;
  email?: string;
  role: string;
  specialization: string;
  avatar: string;
  experienceLevel: number;
  workload: number;
  availability: number;
}

export function useTeamDevelopers() {
  const { status } = useSession();

  return useQuery<TeamDeveloper[]>({
    queryKey: ["my-work", "team-developers"],
    queryFn: async () => {
      const [devsRes, usersRes] = await Promise.all([
        authFetch(`${backendOrigin}/api/developers`).then((r) => r.json()).catch(() => []),
        authFetch(`${backendOrigin}/api/v1/users`).then((r) => r.json()).catch(() => ({ content: [] }))
      ]);

      const usersList = Array.isArray(usersRes?.content) ? usersRes.content : [];

      if (!Array.isArray(devsRes)) return [];

      return devsRes.map((d: any) => {
        const profile = d.profile;
        const fullName = profile
          ? `${profile.firstName ?? ""} ${profile.lastName ?? ""}`.trim()
          : d.dev_id;
        const email = profile?.email || "";

        const matchedUser = usersList.find((u: any) => 
          (email && u.email?.toLowerCase() === email.toLowerCase()) ||
          u.fullName?.toLowerCase() === fullName.toLowerCase()
        );

        let role = "Developer";
        if (d.experience_level >= 4) role = "Senior Engineer";
        else if (d.experience_level >= 2) role = "Mid-level Engineer";
        else role = "Junior Engineer";

        return {
          id: d.id,
          userId: matchedUser?.id,
          devId: d.dev_id,
          name: fullName || d.dev_id,
          email,
          role,
          specialization: profile?.specialization || "Full-Stack Engineer",
          avatar: `https://ui-avatars.com/api/?name=${encodeURIComponent(fullName || d.dev_id)}&background=random`,
          experienceLevel: d.experience_level || 3,
          workload: d.current_workload || 0,
          availability: d.availability ?? 100,
        };
      });
    },
    enabled: status === "authenticated",
  });
}

export function useMyTasks(targetDeveloper?: TeamDeveloper | null) {
  const { api, status, user } = useSession();
  
  const targetUserId = targetDeveloper?.userId || user?.id;
  const targetDevId = targetDeveloper?.id;
  const targetEmail = targetDeveloper?.email || user?.email;
  const targetName = targetDeveloper?.name || user?.fullName;

  return useQuery<MyTask[]>({
    queryKey: ["my-work", "tasks", targetUserId, targetDevId, targetEmail],
    queryFn: async () => {
      const results: MyTask[] = [];
      const seenKeys = new Set<string>();

      // 1. Fetch core tasks from /api/v1/tasks
      try {
        if (targetUserId) {
          const tasksRes = await authFetch(`${backendOrigin}/api/v1/tasks?assigneeId=${targetUserId}&size=100&sort=dueDate,asc`)
            .then((r) => r.json())
            .catch(() => ({ content: [] }));
          const rows = tasksRes?.content || [];
          for (const t of rows) {
            if (!seenKeys.has(t.itemKey)) {
              seenKeys.add(t.itemKey);
              results.push({
                id: t.id,
                projectId: t.projectId,
                itemKey: t.itemKey,
                title: t.title,
                type: t.type || "TASK",
                status: normaliseStatus(t.status),
                priority: t.priority || "MEDIUM",
                assigneeId: t.assigneeId,
                assigneeName: t.assigneeName || targetName,
                dueDate: t.dueDate,
                estimateMinutes: t.estimateMinutes,
                spentMinutes: t.spentMinutes,
                blocked: t.blocked || false,
                storyPoints: t.estimateMinutes ? Math.round(t.estimateMinutes / 60) : undefined,
              });
            }
          }
        }
      } catch (err) {
        console.warn("Could not fetch core tasks:", err);
      }

      // 2. Fetch assigned stories from /api/v1/stories/my
      try {
        const storiesUrl = targetUserId 
          ? `${backendOrigin}/api/v1/stories/my?assigneeId=${targetUserId}`
          : `${backendOrigin}/api/v1/stories/my`;
        const storiesRes = await authFetch(storiesUrl).then((r) => r.json()).catch(() => []);
        if (Array.isArray(storiesRes)) {
          for (const s of storiesRes) {
            if (!seenKeys.has(s.itemKey)) {
              seenKeys.add(s.itemKey);
              results.push({
                id: s.id,
                projectId: s.projectId,
                itemKey: s.itemKey,
                title: s.title,
                type: s.type || "STORY",
                status: normaliseStatus(s.status),
                priority: s.priority || "HIGH",
                assigneeId: s.assigneeId,
                assigneeName: s.assigneeName || targetName,
                dueDate: s.dueDate,
                estimateMinutes: s.storyPoints ? s.storyPoints * 60 : undefined,
                spentMinutes: 0,
                blocked: s.blocked || false,
                storyPoints: s.storyPoints,
              });
            }
          }
        }
      } catch (err) {
        console.warn("Could not fetch user stories:", err);
      }

      // 3. Fetch optimizer tasks from /api/tasks
      try {
        const optTasks = await authFetch(`${backendOrigin}/api/tasks`).then((r) => r.json()).catch(() => []);
        if (Array.isArray(optTasks)) {
          for (const ot of optTasks) {
            const devObj = ot.developer;
            const devMatches = (targetDevId && devObj?.id === targetDevId) ||
              (targetEmail && devObj?.profile?.email?.toLowerCase() === targetEmail.toLowerCase()) ||
              (targetName && `${devObj?.profile?.firstName ?? ''} ${devObj?.profile?.lastName ?? ''}`.trim().toLowerCase() === targetName.toLowerCase());

            if (devMatches) {
              const itemKeyMatch = ot.task_type?.match(/^[A-Z]+-\d+/);
              const key = itemKeyMatch ? itemKeyMatch[0] : `OPT-${ot.id}`;
              
              if (!seenKeys.has(key)) {
                seenKeys.add(key);
                results.push({
                  id: String(ot.id),
                  itemKey: key,
                  title: ot.task_type || `Task #${ot.id}`,
                  type: "STORY",
                  status: normaliseStatus(ot.status),
                  priority: ot.task_complexity >= 4 ? "HIGH" : ot.task_complexity >= 3 ? "MEDIUM" : "LOW",
                  assigneeId: targetUserId,
                  assigneeName: targetName,
                  storyPoints: ot.story_points,
                  blocked: false,
                  req_frontend: ot.req_frontend,
                  req_backend: ot.req_backend,
                  req_db: ot.req_db,
                  task_complexity: ot.task_complexity,
                });
              } else {
                // Enrich existing item with optimizer metadata
                const existing = results.find((r) => r.itemKey === key);
                if (existing) {
                  existing.req_frontend = ot.req_frontend;
                  existing.req_backend = ot.req_backend;
                  existing.req_db = ot.req_db;
                  existing.task_complexity = ot.task_complexity;
                  if (!existing.storyPoints) existing.storyPoints = ot.story_points;
                }
              }
            }
          }
        }
      } catch (err) {
        console.warn("Could not fetch optimizer tasks:", err);
      }

      return results;
    },
    enabled: status === "authenticated",
  });
}

function normaliseStatus(status: string | undefined): string {
  if (!status) return "TODO";
  const s = status.toUpperCase();
  if (s === "BACKLOG" || s === "OPEN" || s === "TO_DO") return "TODO";
  if (s === "IN_PROGRESS" || s === "DEVELOPMENT") return "IN_PROGRESS";
  if (s === "IN_REVIEW" || s === "CODE_REVIEW" || s === "REVIEW") return "IN_REVIEW";
  if (s === "BLOCKED") return "BLOCKED";
  if (s === "DONE" || s === "COMPLETED" || s === "ACCEPTED" || s === "RESOLVED") return "DONE";
  return s;
}

export const MY_WORK_GROUPS = [
  { key: "IN_PROGRESS", label: "In progress", colour: "var(--blue)" },
  { key: "TODO", label: "To do / Backlog", colour: "var(--ink-3)" },
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

export function summarise(tasks: MyTask[]) {
  const today = new Date();
  today.setHours(0, 0, 0, 0);

  const totalPoints = tasks.reduce((acc, t) => acc + (t.storyPoints || 0), 0);
  const donePoints = tasks
    .filter((t) => t.status === "DONE")
    .reduce((acc, t) => acc + (t.storyPoints || 0), 0);
  const openPoints = totalPoints - donePoints;

  return {
    assigned: tasks.filter((task) => task.status !== "DONE" && task.status !== "CANCELLED").length,
    done: tasks.filter((task) => task.status === "DONE").length,
    totalPoints,
    openPoints,
    donePoints,
    overdue: tasks.filter(
      (task) =>
        task.dueDate !== null &&
        task.dueDate !== undefined &&
        new Date(task.dueDate) < today &&
        task.status !== "DONE" &&
        task.status !== "CANCELLED"
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
