"use client";

import { use, useState } from "react";
import { Gauge, Info, Sparkles } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState, ErrorState } from "@/components/feedback/states";
import { CostWidget } from "@/features/ai/components/cost-widget";
import { RiskCard } from "@/features/ai/components/risk-card";
import { useProjectInsights } from "@/features/ai/hooks";
import { useProjects } from "@/features/projects/hooks";
import type { CommitmentVerdict } from "@/lib/api/ai-schemas";
import { cn } from "@/lib/utils";

const CAPABILITY_LABEL: Record<string, string> = {
  "ai.story_points": "Story point suggestions",
  "ai.risk": "Sprint risk assessment",
  "ai.allocation": "Assignment suggestions",
  "ai.cost": "Cost forecasting",
};

const VERDICT_COPY: Record<CommitmentVerdict, { label: string; tone: string; hint: string }> = {
  OVER_COMMITTED: {
    label: "Over-committed",
    tone: "bg-destructive/15 text-destructive",
    hint: "The commitment is well above what this team usually delivers.",
  },
  STRETCHED: {
    label: "Stretched",
    tone: "bg-amber-500/10 text-amber-700 dark:text-amber-400",
    hint: "Achievable, but there is no room for the discovery that always happens.",
  },
  BALANCED: {
    label: "Balanced",
    tone: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
    hint: "The commitment sits comfortably against recent delivery.",
  },
  UNDER_COMMITTED: {
    label: "Under-committed",
    tone: "bg-sky-500/10 text-sky-700 dark:text-sky-400",
    hint: "There is capacity for more than has been committed.",
  },
  UNKNOWN: {
    label: "Not enough history",
    tone: "bg-muted text-muted-foreground",
    hint: "A few completed sprints are needed before velocity means anything.",
  },
};

/**
 * The AI dashboard for one project.
 *
 * <p>Reads stored predictions rather than invoking the models. Opening a page must not
 * bill for four inference calls, and what was forecast is a different question from
 * what the model would say right now.</p>
 */
export default function AiInsightsPage({ params }: { params: Promise<{ orgSlug: string }> }) {
  use(params);
  const projects = useProjects();

  // Only the user's explicit choice is state. The default — the first project — is
  // derived during render, because storing it in an effect means one render showing
  // nothing selected followed by a cascading re-render that selects something.
  const [chosenProjectId, setChosenProjectId] = useState<string | null>(null);
  const projectId = chosenProjectId ?? projects.data?.content[0]?.id ?? null;

  const insights = useProjectInsights(projectId ?? undefined);

  return (
    <div className="animate-fade-up mx-auto max-w-6xl space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="flex items-center gap-2 text-2xl font-semibold tracking-tight">
            <Sparkles className="size-5 text-primary" aria-hidden />
            AI insights
          </h1>
          <p className="mt-1 text-sm text-muted-foreground">
            Velocity, risk and cost for the current sprint — each with the reasoning behind it.
          </p>
        </div>

        {projects.data && projects.data.content.length > 1 ? (
          <label className="text-xs">
            <span className="sr-only">Project</span>
            <select
              className="h-8 rounded-md border bg-background px-2 text-sm focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
              value={projectId ?? ""}
              onChange={(event) => setChosenProjectId(event.target.value)}
            >
              {projects.data.content.map((project) => (
                <option key={project.id} value={project.id}>
                  {project.name}
                </option>
              ))}
            </select>
          </label>
        ) : null}
      </div>

      {projects.isPending || (projectId && insights.isPending) ? (
        <div className="grid gap-4 lg:grid-cols-2">
          {Array.from({ length: 4 }).map((_, index) => (
            <Skeleton key={index} className="h-40 rounded-lg" />
          ))}
        </div>
      ) : projects.data?.content.length === 0 ? (
        <EmptyState
          title="No projects yet"
          description="AI insights appear once a project has a sprint to reason about."
        />
      ) : insights.error ? (
        <ErrorState error={insights.error} onRetry={() => void insights.refetch()} />
      ) : insights.data ? (
        <>
          {insights.data.disabledCapabilities.length > 0 ? (
            <p className="flex items-start gap-2 rounded-md border border-dashed px-3 py-2 text-xs text-muted-foreground">
              <Info className="mt-0.5 size-3.5 shrink-0" aria-hidden />
              <span>
                Switched off for this workspace:{" "}
                {insights.data.disabledCapabilities
                  .map((flag) => CAPABILITY_LABEL[flag] ?? flag)
                  .join(", ")}
                . An administrator can turn these back on in workspace settings.
              </span>
            </p>
          ) : null}

          <div className="grid gap-4 lg:grid-cols-2">
            {insights.data.velocity ? (
              <VelocityCard velocity={insights.data.velocity} />
            ) : null}

            {insights.data.cost ? <CostWidget forecast={insights.data.cost} /> : null}

            {insights.data.risk?.overBudget ? (
              <RiskCard assessment={insights.data.risk.overBudget} />
            ) : null}

            {insights.data.risk?.requirementChange ? (
              <RiskCard assessment={insights.data.risk.requirementChange} />
            ) : null}
          </div>

          {insights.data.estimationCoverage ? (
            <section className="rounded-lg border p-4" aria-labelledby="coverage-heading">
              <h2 id="coverage-heading" className="text-sm font-medium">
                Estimation coverage
              </h2>
              <p className="mt-1 text-xs text-muted-foreground">
                How much of the backlog carries an estimate, and how much of that came
                through the model.
              </p>
              <div className="mt-3 grid gap-3 sm:grid-cols-3">
                <Figure
                  label="Estimated"
                  value={`${insights.data.estimationCoverage.coveragePct}%`}
                  hint={`${insights.data.estimationCoverage.estimated} of ${insights.data.estimationCoverage.totalStories} stories`}
                />
                <Figure
                  label="AI-assisted"
                  value={`${insights.data.estimationCoverage.aiAcceptanceRate}%`}
                  hint={`${insights.data.estimationCoverage.aiAssisted} estimates came from a suggestion`}
                />
                <Figure
                  label="Unestimated"
                  value={String(
                    insights.data.estimationCoverage.totalStories -
                      insights.data.estimationCoverage.estimated,
                  )}
                  hint="Stories that cannot be planned against"
                />
              </div>
            </section>
          ) : null}

          {!insights.data.velocity && !insights.data.cost && !insights.data.risk?.overBudget ? (
            <EmptyState
              icon={Gauge}
              title="Nothing to report yet"
              description="Start a sprint and the models will have something to reason about."
            />
          ) : null}
        </>
      ) : null}
    </div>
  );
}

function VelocityCard({
  velocity,
}: {
  velocity: NonNullable<ReturnType<typeof useProjectInsights>["data"]>["velocity"];
}) {
  if (!velocity) return null;
  const verdict = VERDICT_COPY[velocity.commitmentVerdict];

  return (
    <article className="rounded-lg border p-3" aria-labelledby="velocity-heading">
      <h3 id="velocity-heading" className="flex items-center gap-1.5 text-sm font-medium">
        <Gauge className="size-4 text-primary" aria-hidden />
        Predicted velocity
      </h3>

      <div className="mt-2 flex items-baseline gap-2">
        <span className="text-2xl font-semibold tabular-nums">
          {velocity.predictedVelocity ?? "—"}
        </span>
        <span className="text-xs text-muted-foreground">points</span>
        {velocity.rollingAverage != null ? (
          <span className="text-xs text-muted-foreground tabular-nums">
            rolling average {velocity.rollingAverage}
          </span>
        ) : null}
      </div>

      <div className="mt-2 flex flex-wrap items-center gap-2">
        <Badge className={cn("border-0", verdict.tone)}>{verdict.label}</Badge>
        {velocity.committedPoints != null ? (
          <span className="text-xs text-muted-foreground tabular-nums">
            {velocity.committedPoints} points committed
          </span>
        ) : null}
      </div>

      <p className="mt-2 text-xs leading-relaxed text-muted-foreground">{verdict.hint}</p>

      {velocity.prediction?.explanation ? (
        <p className="mt-2 text-xs leading-relaxed text-muted-foreground">
          {velocity.prediction.explanation}
        </p>
      ) : null}
    </article>
  );
}

function Figure({ label, value, hint }: { label: string; value: string; hint: string }) {
  return (
    <div>
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="mt-0.5 text-xl font-semibold tabular-nums">{value}</p>
      <p className="text-[11px] text-muted-foreground">{hint}</p>
    </div>
  );
}
