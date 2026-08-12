import { Sparkles } from "lucide-react";

/**
 * The product shot: a sprint board with an AI estimate panel open.
 *
 * <p>Built from real markup and the product's own tokens rather than a screenshot.
 * It stays sharp at any density, follows light and dark automatically, and — because
 * it uses the same status and AI colours as the app — cannot drift into showing a
 * product that does not exist.</p>
 */

const COLUMNS = [
  {
    name: "To do",
    accent: "bg-status-todo",
    cards: [
      { key: "MBA-142", title: "Card tokenisation for saved payments", points: 8, ai: true },
      { key: "MBA-147", title: "Retry failed direct debits", points: 3 },
    ],
  },
  {
    name: "In progress",
    accent: "bg-status-progress",
    cards: [
      { key: "MBA-139", title: "Statement export as CSV", points: 5 },
      { key: "MBA-151", title: "Biometric re-auth on transfer", points: 5, blocked: true },
    ],
  },
  {
    name: "In review",
    accent: "bg-status-review",
    cards: [{ key: "MBA-133", title: "Standing order edit flow", points: 3 }],
  },
  {
    name: "Done",
    accent: "bg-status-done",
    cards: [
      { key: "MBA-128", title: "Balance refresh on pull", points: 2 },
      { key: "MBA-130", title: "Payee nickname support", points: 1 },
    ],
  },
] as const;

export function BoardPreview() {
  return (
    <div className="relative">
      <div
        aria-hidden
        className="pointer-events-none absolute -inset-x-8 -top-8 bottom-0 -z-10 rounded-[2rem] bg-[radial-gradient(50%_50%_at_50%_0%,var(--color-primary)/12%,transparent)]"
      />

      <div className="overflow-hidden rounded-xl border bg-card shadow-2xl shadow-primary/5">
        <div className="flex items-center gap-2 border-b bg-muted/40 px-4 py-2.5">
          <div className="flex gap-1.5" aria-hidden>
            <span className="size-2.5 rounded-full bg-destructive/40" />
            <span className="size-2.5 rounded-full bg-priority-medium/40" />
            <span className="size-2.5 rounded-full bg-status-done/40" />
          </div>
          <p className="ml-2 text-xs text-muted-foreground">
            Mobile Banking App · Sprint 7 · 6 days remaining
          </p>
        </div>

        <div className="grid gap-3 p-4 sm:grid-cols-2 lg:grid-cols-4">
          {COLUMNS.map((column) => (
            <div key={column.name} className="space-y-2">
              <div className="flex items-center gap-1.5">
                <span className={`size-1.5 rounded-full ${column.accent}`} aria-hidden />
                <span className="text-2xs font-medium tracking-wide text-muted-foreground uppercase">
                  {column.name}
                </span>
                <span className="ml-auto text-2xs text-muted-foreground">
                  {column.cards.length}
                </span>
              </div>

              {column.cards.map((card) => (
                <article key={card.key} className="rounded-md border bg-background p-2.5">
                  <p className="text-2xs text-muted-foreground">{card.key}</p>
                  <p className="mt-0.5 text-xs leading-snug">{card.title}</p>
                  <div className="mt-2 flex items-center gap-1.5">
                    <span className="rounded bg-muted px-1.5 py-0.5 text-2xs font-medium">
                      {card.points}
                    </span>
                    {"ai" in card && card.ai ? (
                      <span className="inline-flex items-center gap-1 rounded bg-ai-accent/12 px-1.5 py-0.5 text-2xs text-ai-accent">
                        <Sparkles className="size-2.5" aria-hidden />
                        AI
                      </span>
                    ) : null}
                    {"blocked" in card && card.blocked ? (
                      <span className="rounded bg-status-blocked/12 px-1.5 py-0.5 text-2xs text-status-blocked">
                        Blocked
                      </span>
                    ) : null}
                  </div>
                </article>
              ))}
            </div>
          ))}
        </div>
      </div>

      {/* The estimate panel, overlapping the board — the product's actual argument:
          the prediction appears on the decision, not on a separate analytics page. */}
      <div className="mx-auto -mt-6 w-full max-w-md rounded-lg border bg-popover p-4 shadow-xl sm:-mt-10 sm:mr-6 sm:ml-auto">
        <div className="flex items-center gap-1.5">
          <Sparkles className="size-3.5 text-ai-accent" aria-hidden />
          <p className="text-xs font-medium">Story point estimate · MBA-142</p>
        </div>

        <div className="mt-3 flex items-baseline gap-2">
          <span className="text-3xl font-semibold tracking-tight">8</span>
          <span className="text-xs text-muted-foreground">points</span>
          <span className="ml-auto rounded bg-ai-confidence-high/12 px-1.5 py-0.5 text-2xs text-ai-confidence-high">
            82% confidence
          </span>
        </div>
        <p className="mt-1 text-2xs text-muted-foreground">Likely range 5–13</p>

        <ul className="mt-3 space-y-1.5">
          {[
            { label: "Touches the payments service", weight: "+2.1" },
            { label: "Similar stories averaged 7.4", weight: "+1.4" },
            { label: "Acceptance criteria are specific", weight: "−0.8" },
          ].map((driver) => (
            <li key={driver.label} className="flex items-center gap-2 text-2xs">
              <span className="flex-1 text-muted-foreground">{driver.label}</span>
              <span className="font-mono text-muted-foreground">{driver.weight}</span>
            </li>
          ))}
        </ul>

        <div className="mt-3 flex gap-1.5">
          <span className="flex-1 rounded-md bg-primary px-2 py-1.5 text-center text-2xs font-medium text-primary-foreground">
            Accept
          </span>
          <span className="flex-1 rounded-md border px-2 py-1.5 text-center text-2xs">
            Override
          </span>
          <span className="flex-1 rounded-md border px-2 py-1.5 text-center text-2xs">
            Compare
          </span>
        </div>
      </div>
    </div>
  );
}
