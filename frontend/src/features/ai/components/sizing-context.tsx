"use client";

import { TriangleAlert } from "lucide-react";
import type { SizingContext } from "@/lib/api/ai-schemas";
import {
  DOMAIN_LABELS,
  EXPERIENCE_LEVELS,
  TECH_STACK_LABELS,
  type TechStack,
} from "@/features/projects/model-vocabulary";
import { cn } from "@/lib/utils";

/**
 * What the model knew about the team, beside what it had to assume.
 *
 * <p>Four project-level inputs — team size, experience, domain, tech stack — have been
 * going to the story-point model since it shipped, and none of them were ever shown.
 * The panel listed four drivers, all of them about the story's own words, and a reader
 * reasonably concluded the team doing the work was not part of the estimate at all.</p>
 *
 * <p>It is, it simply cannot appear as a driver. Drivers are a per-story SHAP breakdown
 * and these four are identical for every story in the project, so they never rank above
 * the things that actually distinguish one story from the next. Team size moves the raw
 * estimate by about a third of a point across the range this product allows — real, and
 * permanently invisible in a ranked list.</p>
 *
 * <p>Which inputs are marked missing comes from the model service's own account of the
 * row it scored, not from which of these fields happen to be null. The two disagree in
 * the case worth catching: a project whose tech stack is set to something outside the
 * ten the model was trained on has a filled-in field that scores as an empty one.</p>
 */

/** Model-side column names, in the words the estimation-context form uses. */
const CONTEXT_LABELS: Record<string, string> = {
  team_size: "team size",
  dev_experience_level: "team experience",
  domain: "domain",
  tech_stack: "tech stack",
  num_components: "components touched",
  external_apis: "external APIs",
  sprint_duration_days: "sprint length",
  similar_task_count: "comparable past work",
  team_velocity_avg: "team velocity",
  recent_completion_rate: "recent completion rate",
  task_age_days: "backlog age",
  task_type: "task type",
};

/**
 * The four somebody can actually go and fill in.
 *
 * <p>The rest are measured — velocity and completion rate come from closed sprints,
 * comparable past work from the backlog — so telling a reader to "supply" them would
 * be pointing at a form that does not exist. Separating the two is what keeps the
 * notice actionable rather than a list of grievances.</p>
 */
const SETTABLE = new Set(["team_size", "dev_experience_level", "domain", "tech_stack"]);

function label(column: string): string {
  return CONTEXT_LABELS[column] ?? column.replace(/_/g, " ");
}

/** "a, b and c" — an Oxford-comma-free list, because these are read mid-sentence. */
function sentenceList(items: string[]): string {
  if (items.length <= 1) return items[0] ?? "";
  return `${items.slice(0, -1).join(", ")} and ${items[items.length - 1]}`;
}

function experienceLabel(value: string): string {
  return (
    EXPERIENCE_LEVELS.find((level) => level.value === value.toLowerCase())?.label ?? value
  );
}

function stackLabel(value: string): string {
  return TECH_STACK_LABELS[value.toLowerCase() as TechStack] ?? value;
}

function domainName(value: string): string {
  return DOMAIN_LABELS[value.toLowerCase()] ?? value;
}

export function SizingContextLine({ context }: { context: SizingContext }) {
  const missing = new Set(context.defaulted);

  // Each of the four gets a chip whether or not it was supplied. Rendering only the
  // ones that were set is what made this invisible in the first place: a project with
  // nothing filled in would show an empty row and read as "no context needed".
  const chips: { key: string; text: string }[] = [
    {
      key: "team_size",
      text: context.teamSize != null ? `team of ${context.teamSize}` : "team size",
    },
    {
      key: "dev_experience_level",
      text: context.devExperienceLevel
        ? experienceLabel(context.devExperienceLevel)
        : "team experience",
    },
    { key: "domain", text: context.domain ? domainName(context.domain) : "domain" },
    { key: "tech_stack", text: context.techStack ? stackLabel(context.techStack) : "tech stack" },
  ];

  const unset = context.defaulted.filter((column) => SETTABLE.has(column));
  const derived = context.defaulted.filter((column) => !SETTABLE.has(column));
  const unread = context.textTermsRecognised === 0;

  return (
    <div className="rounded-md bg-surface-2 px-2.5 py-2">
      <div className="flex flex-wrap items-center gap-x-1.5 gap-y-1">
        <span className="text-2xs text-ink-3">Sized against</span>
        {chips.map((chip) => {
          const defaulted = missing.has(chip.key);
          return (
            <span
              key={chip.key}
              className={cn(
                "rounded px-1.5 py-0.5 text-2xs",
                defaulted
                  ? // Not struck through: the value is not wrong, it was never given.
                    // A dashed outline reads as an empty slot, which is what it is.
                    "border border-dashed border-line text-ink-3"
                  : "bg-surface-3 font-medium text-ink-2",
              )}
              title={defaulted ? `${label(chip.key)} — not supplied, scored as typical` : undefined}
            >
              {defaulted ? `${label(chip.key)}: typical` : chip.text}
            </span>
          );
        })}
      </div>

      {unset.length > 0 ? (
        <p className="mt-1.5 flex items-start gap-1.5 text-2xs leading-relaxed text-amber">
          <TriangleAlert className="mt-0.5 size-3 shrink-0" aria-hidden />
          <span>
            {sentenceList(unset.map(label))}{" "}
            {unset.length === 1 ? "is" : "are"} not set on this project, so the model used
            the median of its training data instead of this team.{" "}
            <span className="text-ink-3">
              Fill them in under Estimation context to size against your own team.
            </span>
          </span>
        </p>
      ) : null}

      {/* Said separately and more quietly. Nothing here points at a form: velocity and
          completion rate are measured from closed sprints, so a project that has run
          none simply has no reading yet. Worth saying anyway — the reader should know
          the number leans on averages — but not worth an amber warning about work
          nobody can do. The wording avoids "not supplied", which would be wrong for a
          quantity that is measured rather than typed. */}
      {derived.length > 0 ? (
        <p className="mt-1.5 text-2xs leading-relaxed text-ink-3">
          The model had no reading for {sentenceList(derived.map(label))}, so it used
          typical values there.
        </p>
      ) : null}

      {unread ? (
        <p className="mt-1.5 text-2xs leading-relaxed text-ink-3">
          None of this story&rsquo;s wording matched the model&rsquo;s vocabulary, so the
          estimate rests on the structure above rather than on what the story says.
        </p>
      ) : null}
    </div>
  );
}
