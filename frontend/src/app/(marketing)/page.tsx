import type { Metadata } from "next";
import { Hero } from "@/components/marketing/hero";
import { Models } from "@/components/marketing/models";
import { HowItWorks } from "@/components/marketing/how-it-works";
import { Analytics } from "@/components/marketing/analytics";
import { Pricing } from "@/components/marketing/pricing";
import { Faq } from "@/components/marketing/faq";
import { ClosingCta } from "@/components/marketing/closing-cta";

export const metadata: Metadata = {
  title: "SprintIQ — Agile delivery that tells you what is about to go wrong",
  description:
    "Story point estimation, sprint risk, resource allocation and cost forecasting, each attached to the decision you are making. Free to start.",
  openGraph: {
    title: "SprintIQ — AI-powered agile delivery",
    description:
      "Four AI models wired into planning, execution and retrospectives. Every prediction shows its reasoning and can be overridden.",
    type: "website",
  },
};

/**
 * Sections are ordered the way the argument runs: what it does, what makes it
 * different, how the loop works, what you get out of it, what it costs, the
 * objections, and the ask.
 */
export default function LandingPage() {
  return (
    <>
      <Hero />
      <Models />
      <HowItWorks />
      <Analytics />
      <Pricing />
      <Faq />
      <ClosingCta />
    </>
  );
}
