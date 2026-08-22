"use client";

import { use, useState } from "react";
import { Gauge, Info, MessageSquare, Sparkles } from "lucide-react";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState, ErrorState } from "@/components/feedback/states";
import { CostWidget } from "@/features/ai/components/cost-widget";
import { RiskCard } from "@/features/ai/components/risk-card";
import { VelocityPanel } from "@/features/ai/components/velocity-panel";
import { useProjectInsights } from "@/features/ai/hooks";
import { useProjects } from "@/features/projects/hooks";

const CAPABILITY_LABEL: Record<string, string> = {
  "ai.story_points": "Story point suggestions",
  "ai.risk": "Sprint risk assessment",
  "ai.allocation": "Assignment suggestions",
  "ai.cost": "Cost forecasting",
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
            Velocity, cost, and the three risk models — effort, requirements and how the
            team is coordinating — each with the reasoning behind it.
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
              <VelocityPanel velocity={insights.data.velocity} />
            ) : null}

            {insights.data.cost ? <CostWidget forecast={insights.data.cost} /> : null}

            {insights.data.risk?.overBudget ? (
              <RiskCard assessment={insights.data.risk.overBudget} />
            ) : null}

            {insights.data.risk?.requirementChange ? (
              <RiskCard assessment={insights.data.risk.requirementChange} />
            ) : null}

            {insights.data.risk?.communicationCollaboration ? (
              <RiskCard assessment={insights.data.risk.communicationCollaboration} />
            ) : insights.data.risk ? (
              // Named rather than omitted. The other two panels disappear when their
              // model fails; this one is absent when the sprint genuinely has nothing
              // to read, and a team that sees two risk cards where there were three
              // assumes something broke.
              <article className="rounded-lg border border-dashed p-3">
                <h3 className="flex items-center gap-1.5 text-sm font-medium">
                  <MessageSquare className="size-4 text-muted-foreground" aria-hidden />
                  Communication &amp; collaboration risk
                </h3>
                <p className="mt-2 text-xs leading-relaxed text-muted-foreground">
                  Nothing to read yet. This model works from task comments, blocked
                  tasks and reopened work — with none of those recorded, a score would
                  say the team is coordinating well when what it means is that the
                  sprint has not started.
                </p>
              </article>
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

          {!insights.data.velocity &&
          !insights.data.cost &&
          !insights.data.risk?.overBudget &&
          !insights.data.risk?.requirementChange &&
          !insights.data.risk?.communicationCollaboration ? (
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

function Figure({ label, value, hint }: { label: string; value: string; hint: string }) {
  return (
    <div>
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="mt-0.5 text-xl font-semibold tabular-nums">{value}</p>
      <p className="text-[11px] text-muted-foreground">{hint}</p>
    </div>
  );
}
