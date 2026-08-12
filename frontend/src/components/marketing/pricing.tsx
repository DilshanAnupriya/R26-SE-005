import Link from "next/link";
import { Check } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Reveal } from "./reveal";
import { cn } from "@/lib/utils";

/**
 * The four tiers from the backend's `OrganizationPlan` enum — FREE, STARTER, PRO,
 * ENTERPRISE. Invented tiers here would be a promise the product cannot keep.
 */
const PLANS = [
  {
    name: "Free",
    price: "£0",
    cadence: "forever",
    summary: "For a small team trying the whole loop.",
    features: [
      "Up to 5 members",
      "3 projects",
      "Full backlog, sprints and boards",
      "Story point estimation (M1)",
    ],
    cta: "Start free",
    featured: false,
  },
  {
    name: "Starter",
    price: "£8",
    cadence: "per member / month",
    summary: "For a team running real sprints.",
    features: [
      "Up to 20 members",
      "Unlimited projects",
      "Sprint risk prediction (M2)",
      "Velocity forecasting",
      "Time tracking and timesheets",
    ],
    cta: "Start free trial",
    featured: true,
  },
  {
    name: "Pro",
    price: "£16",
    cadence: "per member / month",
    summary: "For several teams that need to compare.",
    features: [
      "Unlimited members",
      "Resource allocation (M3)",
      "Cost forecasting (M4)",
      "Custom roles and permissions",
      "Full analytics suite and export",
    ],
    cta: "Start free trial",
    featured: false,
  },
  {
    name: "Enterprise",
    price: "Talk to us",
    cadence: "",
    summary: "For organizations with their own rules.",
    features: [
      "SSO and SCIM",
      "Audit export and retention control",
      "Self-hosted AI gateway",
      "Priority support",
    ],
    cta: "Contact sales",
    featured: false,
  },
] as const;

export function Pricing() {
  return (
    <section id="pricing" className="scroll-mt-20 border-t bg-muted/25 py-20 sm:py-28">
      <div className="mx-auto max-w-6xl px-4">
        <Reveal stagger className="mx-auto max-w-2xl text-center">
          <h2 className="text-3xl font-semibold tracking-tight text-balance">
            Pricing that scales with the team, not the seat count you fear
          </h2>
          <p className="mt-4 text-pretty text-muted-foreground">
            Start free and stay free while your team is small. Every tier includes the full
            agile loop — the paid ones add models.
          </p>
        </Reveal>

        <Reveal stagger as="ul" className="mt-12 grid gap-4 lg:grid-cols-4">
          {PLANS.map((plan) => (
            <li
              key={plan.name}
              className={cn(
                "flex flex-col rounded-xl border bg-card p-6",
                plan.featured && "border-primary shadow-lg shadow-primary/10",
              )}
            >
              {plan.featured ? (
                <span className="mb-3 -mt-1 self-start rounded-full bg-primary px-2 py-0.5 text-2xs font-medium text-primary-foreground">
                  Most popular
                </span>
              ) : null}

              <h3 className="font-medium">{plan.name}</h3>
              <div className="mt-2 flex items-baseline gap-1.5">
                <span className="text-2xl font-semibold tracking-tight">{plan.price}</span>
                {plan.cadence ? (
                  <span className="text-2xs text-muted-foreground">{plan.cadence}</span>
                ) : null}
              </div>
              <p className="mt-2 text-xs text-muted-foreground">{plan.summary}</p>

              <ul className="mt-5 flex-1 space-y-2">
                {plan.features.map((feature) => (
                  <li key={feature} className="flex items-start gap-2 text-xs">
                    <Check className="mt-0.5 size-3 shrink-0 text-status-done" aria-hidden />
                    <span className="text-muted-foreground">{feature}</span>
                  </li>
                ))}
              </ul>

              <Button
                asChild
                variant={plan.featured ? "default" : "outline"}
                className="mt-6 w-full"
                size="sm"
              >
                <Link href="/register">{plan.cta}</Link>
              </Button>
            </li>
          ))}
        </Reveal>
      </div>
    </section>
  );
}
