import {
  ArrowDown,
  ArrowUp,
  Bug,
  ChevronsUp,
  CircleDot,
  Component,
  Minus,
  Zap,
} from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * The small repeated pieces the design uses on every work item: type icon, priority
 * icon, label pill, avatar, project key.
 *
 * <p>They live together because they are always used together — a board card, a
 * backlog row and a My Work row each draw four of the five. Splitting them across
 * five files would mean five imports at every call site for no gain.</p>
 */

const TYPE_STYLES = {
  STORY: { icon: CircleDot, colour: "var(--green)", label: "Story" },
  TASK: { icon: Component, colour: "var(--blue)", label: "Task" },
  BUG: { icon: Bug, colour: "var(--red)", label: "Bug" },
  SPIKE: { icon: Zap, colour: "var(--violet)", label: "Spike" },
  CHORE: { icon: Component, colour: "var(--ink-3)", label: "Chore" },
  TECH_DEBT: { icon: Component, colour: "var(--amber)", label: "Tech debt" },
  EPIC: { icon: Zap, colour: "var(--amber)", label: "Epic" },
} as const;

export type ItemType = keyof typeof TYPE_STYLES;

export function TypeIcon({ type, className }: { type: string; className?: string }) {
  const style = TYPE_STYLES[type as ItemType] ?? TYPE_STYLES.TASK;
  const Icon = style.icon;
  return (
    <>
      <Icon
        className={cn("size-4 shrink-0", className)}
        style={{ color: style.colour }}
        aria-hidden
      />
      {/* The icon carries meaning, so it needs a text equivalent. */}
      <span className="sr-only">{style.label}</span>
    </>
  );
}

const PRIORITY_STYLES = {
  CRITICAL: { icon: ChevronsUp, colour: "var(--red)", label: "Critical" },
  HIGH: { icon: ArrowUp, colour: "var(--amber)", label: "High" },
  MEDIUM: { icon: Minus, colour: "var(--blue)", label: "Medium" },
  LOW: { icon: ArrowDown, colour: "var(--ink-3)", label: "Low" },
  LOWEST: { icon: ArrowDown, colour: "var(--ink-3)", label: "Lowest" },
} as const;

export function PriorityIcon({ priority, className }: { priority: string; className?: string }) {
  const style = PRIORITY_STYLES[priority as keyof typeof PRIORITY_STYLES] ?? PRIORITY_STYLES.MEDIUM;
  const Icon = style.icon;
  return (
    <>
      <Icon className={cn("size-4 shrink-0", className)} style={{ color: style.colour }} aria-hidden />
      <span className="sr-only">{style.label} priority</span>
    </>
  );
}

/** Label pill. Colour comes from the label itself, so it stays recognisable. */
export function LabelPill({ text, colour }: { text: string; colour?: string | null }) {
  const base = colour ?? "var(--ink-3)";
  return (
    <span
      className="rounded-sm px-1.5 py-0.5 text-2xs font-semibold"
      // color-mix rather than a fixed soft colour: an arbitrary label colour has no
      // matching tint in the palette, so the background is derived from it.
      style={{ color: base, background: `color-mix(in srgb, ${base} 14%, transparent)` }}
    >
      {text}
    </span>
  );
}

/**
 * Avatar with deterministic colour.
 *
 * <p>The hue is derived from the identifier rather than stored, so the same person
 * is the same colour on every screen and in every session without a round trip. Six
 * hues from the palette, chosen so adjacent initials rarely collide.</p>
 */
const AVATAR_HUES = [
  "var(--accent)",
  "var(--teal)",
  "var(--violet)",
  "var(--amber)",
  "var(--blue)",
  "var(--green)",
] as const;

export function Avatar({
  name,
  id,
  size = 26,
  className,
}: {
  name?: string | null;
  id?: string | null;
  size?: number;
  className?: string;
}) {
  const label = name?.trim() || "Unassigned";
  const initials = label
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((word) => word[0]?.toUpperCase() ?? "")
    .join("");

  const seed = id ?? label;
  let hash = 0;
  for (let index = 0; index < seed.length; index++) {
    hash = (hash * 31 + seed.charCodeAt(index)) >>> 0;
  }
  const colour = AVATAR_HUES[hash % AVATAR_HUES.length];

  return (
    <span
      className={cn(
        "inline-flex flex-none items-center justify-center rounded-full font-semibold text-white",
        className,
      )}
      style={{ width: size, height: size, background: colour, fontSize: Math.round(size * 0.4) }}
      title={label}
    >
      {initials || "?"}
      <span className="sr-only">{label}</span>
    </span>
  );
}

/** Monospace project key chip (PAY, CRM), tinted per project. */
export function ProjectKey({
  projectKey,
  size = 30,
  className,
}: {
  projectKey: string;
  size?: number;
  className?: string;
}) {
  let hash = 0;
  for (let index = 0; index < projectKey.length; index++) {
    hash = (hash * 31 + projectKey.charCodeAt(index)) >>> 0;
  }
  const colour = AVATAR_HUES[hash % AVATAR_HUES.length];

  return (
    <span
      className={cn(
        "inline-flex flex-none items-center justify-center rounded-md font-mono font-bold",
        className,
      )}
      style={{
        width: size,
        height: size,
        color: colour,
        background: `color-mix(in srgb, ${colour} 14%, transparent)`,
        fontSize: Math.round(size * 0.4),
      }}
    >
      {projectKey.slice(0, 3)}
    </span>
  );
}

/** Status pill — "On track", "At risk", and the board's column dots. */
export function StatusPill({
  children,
  tone = "neutral",
}: {
  children: React.ReactNode;
  tone?: "neutral" | "green" | "amber" | "red" | "accent";
}) {
  const tones = {
    neutral: "text-ink-2 bg-surface-3",
    green: "text-green bg-green-soft",
    amber: "text-amber bg-amber-soft",
    red: "text-red bg-red-soft",
    accent: "text-accent-text bg-accent-soft",
  } as const;

  return (
    <span className={cn("rounded-full px-2.5 py-1 text-xs font-semibold", tones[tone])}>
      {children}
    </span>
  );
}
