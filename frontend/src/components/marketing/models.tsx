import { AlertTriangle, CircleDollarSign, Gauge, Users } from "lucide-react";
import { Reveal } from "./reveal";

/**
 * The four models, each shown against the decision it informs.
 *
 * <p>That pairing is the whole positioning from `docs/00-product-spec.md`: a
 * prediction on a dashboard is trivia, the same prediction on the screen where
 * someone is about to commit a sprint is a decision aid. So every card names the
 * moment it appears rather than the algorithm behind it.</p>
 */
const MODELS = [
  {
    id: "M1",
    icon: Gauge,
    title: "Story point estimation",
    where: "Appears in the story editor, as you write it",
    body: "Predicts points from the story text and your team's own history, with an uncertainty range and the drivers behind it. Accept it and the estimate is set; override it and the difference is what trains the next one.",
    accent: "text-ai-accent",
  },
  {
    id: "M2",
    icon: AlertTriangle,
    title: "Sprint risk",
    where: "Appears on the sprint dashboard, mid-flight",
    body: "Over-budget and requirement-change probability, computed from scope churn, blocked items and how much the requirements behind the sprint have already moved. Creates a mitigation task rather than a notification you dismiss.",
    accent: "text-risk-high",
  },
  {
    id: "M3",
    icon: Users,
    title: "Resource allocation",
    where: "Appears in the assignment picker",
    body: "Ranks who should take a task on predicted performance, consistency and current load — not on who is least busy. Shows why each person ranked where they did.",
    accent: "text-status-progress",
  },
  {
    id: "M4",
    icon: CircleDollarSign,
    title: "Cost forecasting",
    where: "Appears in sprint planning, and again mid-sprint",
    body: "Forecasts what a sprint will cost before it starts and re-forecasts once real effort, absences and overtime are in. Planning only — SprintIQ never bills, invoices or moves money.",
    accent: "text-priority-medium",
  },
] as const;

export function Models() {
  return (
    <section id="models" className="scroll-mt-20 border-t py-20 sm:py-28">
      <div className="mx-auto max-w-6xl px-4">
        <Reveal stagger className="max-w-2xl">
          <h2 className="text-3xl font-semibold tracking-tight text-balance">
            Four models, each attached to a decision
          </h2>
          <p className="mt-4 text-pretty text-muted-foreground">
            Most tools bolt AI on as a panel you visit. SprintIQ puts each prediction on the
            screen where the choice is actually made, with the reasoning visible and an
            override that counts for something.
          </p>
        </Reveal>

        <Reveal stagger as="ul" className="mt-12 grid gap-4 sm:grid-cols-2">
          {MODELS.map((model) => {
            const Icon = model.icon;
            return (
              <li
                key={model.id}
                className="group rounded-xl border bg-card p-6 transition-colors duration-200 hover:border-primary/40"
              >
                <div className="flex items-center gap-3">
                  <span className="flex size-9 items-center justify-center rounded-lg border bg-background">
                    <Icon className={`size-4 ${model.accent}`} aria-hidden />
                  </span>
                  <div>
                    <p className="text-2xs font-mono text-muted-foreground">{model.id}</p>
                    <h3 className="font-medium">{model.title}</h3>
                  </div>
                </div>

                <p className="mt-4 text-sm leading-relaxed text-muted-foreground">{model.body}</p>

                <p className="mt-4 border-t pt-3 text-2xs text-muted-foreground">{model.where}</p>
              </li>
            );
          })}
        </Reveal>
      </div>
    </section>
  );
}
