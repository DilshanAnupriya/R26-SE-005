import Link from "next/link";
import { ArrowRight, Sparkles } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Reveal } from "./reveal";
import { BoardPreview } from "./board-preview";

export function Hero() {
  return (
    <section className="relative overflow-hidden pt-28 pb-16 sm:pt-36 sm:pb-24">
      {/* Two decorative layers: a soft primary wash behind the headline and a faint
          grid that fades out before it reaches the text, so nothing competes with it. */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 -z-20 bg-[radial-gradient(70%_55%_at_50%_-10%,var(--color-primary)/14%,transparent_65%)]"
      />
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 -z-10 [mask-image:radial-gradient(60%_45%_at_50%_0%,black,transparent)] bg-[linear-gradient(to_right,var(--color-border)_1px,transparent_1px),linear-gradient(to_bottom,var(--color-border)_1px,transparent_1px)] bg-[size:56px_56px] opacity-40"
      />

      <div className="mx-auto max-w-6xl px-4">
        <Reveal stagger className="mx-auto max-w-3xl text-center">
          <div>
            <span className="inline-flex items-center gap-1.5 rounded-full border bg-card px-3 py-1 text-xs text-muted-foreground">
              <Sparkles className="size-3 text-ai-accent" aria-hidden />
              Four models, wired into the work
            </span>
          </div>

          <h1 className="mt-5 text-4xl font-semibold tracking-tight text-balance sm:text-5xl">
            Agile delivery that tells you what is about to go wrong
          </h1>

          <p className="mx-auto mt-5 max-w-2xl text-lg text-pretty text-muted-foreground">
            SprintIQ estimates story points, predicts sprint risk, ranks who should take a
            task and forecasts cost — each one attached to the decision you are making, never
            a number floating on a dashboard.
          </p>

          <div className="mt-8 flex flex-col items-center justify-center gap-3 sm:flex-row">
            <Button asChild size="lg">
              <Link href="/register">
                Start free
                <ArrowRight className="size-4" aria-hidden />
              </Link>
            </Button>
            <Button asChild size="lg" variant="outline">
              <a href="#models">See how it works</a>
            </Button>
          </div>

          <p className="mt-4 text-xs text-muted-foreground">
            No card required · Your data stays in your workspace
          </p>
        </Reveal>

        <Reveal delay={0.15} className="mt-14">
          <BoardPreview />
        </Reveal>
      </div>
    </section>
  );
}
