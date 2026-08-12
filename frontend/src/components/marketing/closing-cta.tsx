import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Reveal } from "./reveal";

export function ClosingCta() {
  return (
    <section className="relative overflow-hidden border-t py-20 sm:py-28">
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 -z-10 bg-[radial-gradient(60%_70%_at_50%_100%,var(--color-primary)/14%,transparent_70%)]"
      />

      <Reveal stagger className="mx-auto max-w-2xl px-4 text-center">
        <h2 className="text-3xl font-semibold tracking-tight text-balance sm:text-4xl">
          Find out what your next sprint is going to do
        </h2>
        <p className="mt-4 text-pretty text-muted-foreground">
          Set up a workspace in under a minute. Free while your team is small, and your
          backlog is yours to export whenever you like.
        </p>
        <div className="mt-8 flex flex-col items-center justify-center gap-3 sm:flex-row">
          <Button asChild size="lg">
            <Link href="/register">
              Start free
              <ArrowRight className="size-4" aria-hidden />
            </Link>
          </Button>
          <Button asChild size="lg" variant="ghost">
            <Link href="/login">Sign in</Link>
          </Button>
        </div>
      </Reveal>
    </section>
  );
}
