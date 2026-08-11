import { z } from "zod";
import type { ApiClient } from "@/lib/api/client";

export const barKindSchema = z.enum(["EPIC", "STORY", "SPRINT", "MILESTONE"]);
export type BarKind = z.infer<typeof barKindSchema>;

export const dateSourceSchema = z.enum(["EXPLICIT", "SPRINT", "DUE_DATE_ONLY", "DERIVED"]);
export type DateSource = z.infer<typeof dateSourceSchema>;

export const barSchema = z.object({
  id: z.uuid().nullish(),
  itemKey: z.string().nullish(),
  title: z.string(),
  kind: barKindSchema,
  start: z.string(),
  end: z.string(),
  dateSource: dateSourceSchema,
  progressPct: z.number().nullish(),
  status: z.string().nullish(),
  assigneeId: z.uuid().nullish(),
  assigneeName: z.string().nullish(),
  blocked: z.boolean(),
  overdue: z.boolean(),
});
export type Bar = z.infer<typeof barSchema>;

export const timelineGroupSchema = z.object({
  bar: barSchema,
  rows: z.array(barSchema).default([]),
});
export type TimelineGroup = z.infer<typeof timelineGroupSchema>;

export const timelineSchema = z.object({
  from: z.string(),
  to: z.string(),
  today: z.string(),
  weeks: z.array(z.object({ start: z.string(), label: z.string() })).default([]),
  groups: z.array(timelineGroupSchema).default([]),
  milestones: z.array(barSchema).default([]),
  sprints: z.array(barSchema).default([]),
  undatedCount: z.number(),
});
export type Timeline = z.infer<typeof timelineSchema>;

export const timelineApi = {
  get: (api: ApiClient, projectId: string) =>
    api.get("/timeline", { schema: timelineSchema, query: { projectId } }),
};

/** Days between two ISO dates. Dates only — no clock, so no timezone to get wrong. */
export function daysBetween(from: string, to: string): number {
  const a = Date.UTC(...parts(from));
  const b = Date.UTC(...parts(to));
  return Math.round((b - a) / 86_400_000);
}

function parts(iso: string): [number, number, number] {
  const [year, month, day] = iso.split("-").map(Number);
  return [year, month - 1, day];
}

/**
 * Where a bar sits in the window, as percentages.
 *
 * <p>The end date is inclusive — a story due on the 20th occupies the 20th — so the
 * span is one day longer than the difference between the dates. Getting that wrong
 * draws every single-day marker as a zero-width sliver.</p>
 */
export function barGeometry(
  bar: { start: string; end: string },
  from: string,
  to: string,
): { left: string; width: string } {
  const total = Math.max(1, daysBetween(from, to) + 1);
  const offset = Math.max(0, daysBetween(from, bar.start));
  const span = Math.max(1, daysBetween(bar.start, bar.end) + 1);

  return {
    left: `${(offset / total) * 100}%`,
    // Clamped so a bar running past the window ends at its edge rather than
    // stretching the row and scrolling the whole chart sideways.
    width: `${(Math.min(span, total - offset) / total) * 100}%`,
  };
}

/** How far along the window today is, for the "now" line. */
export function todayOffset(today: string, from: string, to: string): string | null {
  const total = Math.max(1, daysBetween(from, to) + 1);
  const offset = daysBetween(from, today);
  if (offset < 0 || offset > total) return null;
  return `${(offset / total) * 100}%`;
}

/**
 * Why a bar has the dates it has, in words.
 *
 * <p>Shown on hover for anything inferred. A reader who cannot tell a date somebody
 * committed to from one the software worked out will treat both as promises, and a
 * Gantt chart that does that is how a plan turns into an argument.</p>
 */
export function dateSourceNote(bar: Bar): string | null {
  switch (bar.dateSource) {
    case "SPRINT":
      return "Dated by the sprint this is committed to";
    case "DUE_DATE_ONLY":
      return "Only a due date — shown as a marker, not a span";
    case "DERIVED":
      return "No dates of its own — inferred from the work inside it";
    default:
      return null;
  }
}

/**
 * Whether the bar's span was worked out rather than agreed.
 *
 * <p>Narrower than "has a note". A story inherits its window from the sprint it is
 * committed to, and that window is as real as any — somebody agreed to it in planning.
 * Only DERIVED is a genuine inference: an epic given the span of whatever happens to
 * be inside it today. Drawing those two the same way would either dress up a guess or
 * cast doubt on a commitment.</p>
 */
export function isInferredSpan(bar: Bar): boolean {
  return bar.dateSource === "DERIVED";
}

export function barColour(bar: Bar): string {
  if (bar.blocked) return "var(--red)";
  if (bar.overdue) return "var(--amber)";
  if (bar.kind === "MILESTONE") return "var(--violet)";
  if (bar.status === "DONE") return "var(--green)";
  return "var(--accent)";
}
