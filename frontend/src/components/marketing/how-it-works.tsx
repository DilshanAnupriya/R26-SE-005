import { CheckCircle2, ClipboardList, PlayCircle, RotateCcw } from "lucide-react";
import { Reveal } from "./reveal";

const STEPS = [
  {
    icon: ClipboardList,
    title: "Plan",
    body: "Rank the backlog by dragging. Estimates arrive as you write each story, and the capacity meter tells you when the sprint is already full.",
  },
  {
    icon: PlayCircle,
    title: "Execute",
    body: "Board and sprint board stay in step. Commitment freezes the moment the sprint starts, so anything added afterwards is visible as scope creep instead of quietly absorbed.",
  },
  {
    icon: CheckCircle2,
    title: "Track",
    body: "Burndown from recorded daily snapshots, not recomputed from timestamps — so a scope change still shows up weeks later.",
  },
  {
    icon: RotateCcw,
    title: "Retrospect",
    body: "What went well, what did not, and action items that carry forward with an owner — so the next retro can show what actually got followed through.",
  },
] as const;

export function HowItWorks() {
  return (
    <section id="how" className="scroll-mt-20 border-t bg-muted/25 py-20 sm:py-28">
      <div className="mx-auto max-w-6xl px-4">
        <Reveal stagger className="max-w-2xl">
          <h2 className="text-3xl font-semibold tracking-tight text-balance">
            The whole loop, in one place
          </h2>
          <p className="mt-4 text-pretty text-muted-foreground">
            Plan, execute, track, retrospect. No exporting to a spreadsheet halfway through.
          </p>
        </Reveal>

        <Reveal stagger as="ul" className="mt-12 grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
          {STEPS.map((step, index) => {
            const Icon = step.icon;
            return (
              <li key={step.title} className="relative">
                {/* Connector between steps on wide screens, hidden from assistive tech
                    because the ordered content already conveys the sequence. */}
                {index < STEPS.length - 1 ? (
                  <span
                    aria-hidden
                    className="absolute top-4 left-[calc(2rem+0.5rem)] hidden h-px w-[calc(100%-2rem)] bg-border lg:block"
                  />
                ) : null}

                <span className="relative flex size-8 items-center justify-center rounded-full border bg-background">
                  <Icon className="size-3.5 text-primary" aria-hidden />
                </span>
                <h3 className="mt-4 font-medium">{step.title}</h3>
                <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{step.body}</p>
              </li>
            );
          })}
        </Reveal>
      </div>
    </section>
  );
}
