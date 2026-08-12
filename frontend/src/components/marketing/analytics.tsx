import { Reveal } from "./reveal";

/** Committed vs delivered across six sprints. Plausible, not flattering. */
const VELOCITY = [
  { sprint: "S2", committed: 34, completed: 28 },
  { sprint: "S3", committed: 32, completed: 31 },
  { sprint: "S4", committed: 38, completed: 26 },
  { sprint: "S5", committed: 34, completed: 33 },
  { sprint: "S6", committed: 36, completed: 35 },
  { sprint: "S7", committed: 34, completed: 21 },
] as const;

const MAX = 40;

const REPORTS = [
  "Velocity, committed against delivered",
  "Burndown and burnup with the scope-change line",
  "Cumulative flow",
  "Cycle time and lead time with a control chart",
  "Spillover and scope volatility",
  "Workload balance across the team",
  "Defect and reopen rate",
  "Cost forecast trend",
] as const;

export function Analytics() {
  return (
    <section id="analytics" className="scroll-mt-20 border-t py-20 sm:py-28">
      <div className="mx-auto grid max-w-6xl gap-12 px-4 lg:grid-cols-2 lg:items-center">
        <Reveal stagger>
          <h2 className="text-3xl font-semibold tracking-tight text-balance">
            Reports that answer the awkward questions
          </h2>
          <p className="mt-4 text-pretty text-muted-foreground">
            Not how busy everyone looked — whether the commitment held, where the scope went,
            and which of it was predictable in advance.
          </p>

          <ul className="mt-6 grid gap-2 sm:grid-cols-2">
            {REPORTS.map((report) => (
              <li key={report} className="flex items-start gap-2 text-sm text-muted-foreground">
                <span className="mt-1.5 size-1 shrink-0 rounded-full bg-primary" aria-hidden />
                {report}
              </li>
            ))}
          </ul>
        </Reveal>

        <Reveal delay={0.1}>
          <figure className="rounded-xl border bg-card p-5">
            <figcaption className="flex items-baseline justify-between">
              <span className="text-sm font-medium">Velocity</span>
              <span className="text-2xs text-muted-foreground">Last 6 sprints</span>
            </figcaption>

            <div className="mt-5 flex h-44 items-end gap-3" role="img"
              aria-label="Velocity across six sprints. Committed points stay near 34 while completed points range from 21 to 35, dropping sharply in the most recent sprint.">
              {VELOCITY.map((entry) => (
                <div key={entry.sprint} className="flex flex-1 flex-col items-center gap-1.5">
                  <div className="flex h-full w-full items-end justify-center gap-1">
                    <div
                      className="w-1/3 rounded-t-sm bg-muted"
                      style={{ height: `${(entry.committed / MAX) * 100}%` }}
                    />
                    <div
                      className="w-1/3 rounded-t-sm bg-primary"
                      style={{ height: `${(entry.completed / MAX) * 100}%` }}
                    />
                  </div>
                  <span className="text-2xs text-muted-foreground">{entry.sprint}</span>
                </div>
              ))}
            </div>

            <div className="mt-4 flex gap-4 border-t pt-3 text-2xs text-muted-foreground">
              <span className="flex items-center gap-1.5">
                <span className="size-2 rounded-sm bg-muted" aria-hidden /> Committed
              </span>
              <span className="flex items-center gap-1.5">
                <span className="size-2 rounded-sm bg-primary" aria-hidden /> Completed
              </span>
            </div>
          </figure>
        </Reveal>
      </div>
    </section>
  );
}
