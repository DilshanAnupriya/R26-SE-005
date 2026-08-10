import { z } from "zod";
import type { ApiClient } from "@/lib/api/client";

export const entryKindSchema = z.enum([
  "SPRINT_START",
  "SPRINT_END",
  "MILESTONE",
  "STORY_DUE",
  "INITIATIVE_TARGET",
  "GOAL_TARGET",
]);
export type EntryKind = z.infer<typeof entryKindSchema>;

export const calendarEntrySchema = z.object({
  id: z.uuid(),
  kind: entryKindSchema,
  date: z.string(),
  title: z.string(),
  itemKey: z.string().nullish(),
  projectId: z.uuid().nullish(),
  projectKey: z.string().nullish(),
  assigneeId: z.uuid().nullish(),
  assigneeName: z.string().nullish(),
  status: z.string().nullish(),
  overdue: z.boolean(),
});
export type CalendarEntry = z.infer<typeof calendarEntrySchema>;

export const calendarDaySchema = z.object({
  date: z.string(),
  inMonth: z.boolean(),
  today: z.boolean(),
  weekend: z.boolean(),
  entries: z.array(calendarEntrySchema).default([]),
});
export type CalendarDay = z.infer<typeof calendarDaySchema>;

export const calendarSchema = z.object({
  month: z.string(),
  from: z.string(),
  to: z.string(),
  days: z.array(calendarDaySchema).default([]),
  totalEntries: z.number(),
});
export type Calendar = z.infer<typeof calendarSchema>;

export const calendarApi = {
  month: (api: ApiClient, month: string, projectId?: string) =>
    api.get("/calendar", { schema: calendarSchema, query: { month, projectId } }),
};

const KIND_STYLE: Record<EntryKind, { colour: string; label: string }> = {
  SPRINT_START: { colour: "var(--accent)", label: "Sprint starts" },
  SPRINT_END: { colour: "var(--accent)", label: "Sprint ends" },
  MILESTONE: { colour: "var(--violet)", label: "Milestone" },
  STORY_DUE: { colour: "var(--blue)", label: "Story due" },
  INITIATIVE_TARGET: { colour: "var(--teal)", label: "Initiative target" },
  GOAL_TARGET: { colour: "var(--green)", label: "Goal target" },
};

export function entryColour(entry: CalendarEntry): string {
  // Overdue outranks kind. What colour a thing normally is matters less than the fact
  // that its date has passed and it is still open.
  return entry.overdue ? "var(--red)" : KIND_STYLE[entry.kind].colour;
}

export function entryKindLabel(kind: EntryKind): string {
  return KIND_STYLE[kind].label;
}

/** The month key the API wants — any date in the month works, so use the first. */
export function monthKey(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-01`;
}

export function monthLabel(iso: string): string {
  const [year, month] = iso.split("-").map(Number);
  return new Intl.DateTimeFormat("en-GB", { month: "long", year: "numeric" }).format(
    new Date(year, month - 1, 1),
  );
}

/** Shifts a month key by whole months without tripping over month lengths. */
export function shiftMonth(iso: string, by: number): string {
  const [year, month] = iso.split("-").map(Number);
  // Day 1 throughout: new Date(2026, 0, 31) shifted by one month gives 2 March,
  // which is how a calendar quietly skips February.
  return monthKey(new Date(year, month - 1 + by, 1));
}

export function dayNumber(iso: string): number {
  return Number(iso.split("-")[2]);
}
