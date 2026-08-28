/**
 * The values the estimation models were actually trained on.
 *
 * <p>Kept in one place because three surfaces need the same list — the create dialog,
 * the edit dialog, and the hint each shows about what the model will do with the
 * answer. Two copies of this list would drift, and the failure would be silent: a stack
 * the model has never seen sets no one-hot column and the estimate quietly loses a
 * signal, with nothing on screen to say so.</p>
 *
 * <p>Mirrors `KNOWN_TECH_STACKS` and `KNOWN_DOMAINS` in `ai-service/app/features.py`.</p>
 */

export const TECH_STACKS = [
  "springboot",
  "nodejs",
  "django",
  "flask",
  "fastapi",
  "react",
  "angular",
  "vue",
  "dotnet",
  "rails",
] as const;

export type TechStack = (typeof TECH_STACKS)[number];

export const TECH_STACK_LABELS: Record<TechStack, string> = {
  springboot: "Spring Boot",
  nodejs: "Node.js",
  django: "Django",
  flask: "Flask",
  fastapi: "FastAPI",
  react: "React",
  angular: "Angular",
  vue: "Vue",
  dotnet: ".NET",
  rails: "Rails",
};

/**
 * Domains the velocity model has a fine-tuned adapter for.
 *
 * <p>Unlike tech stack, a domain outside this list is genuinely fine. The velocity
 * model is a transfer-learning bundle: the base regressor was trained across these
 * domains with the domain column deliberately excluded, so an unrecognised domain
 * routes to the base model and gets a zero-shot answer rather than a wrong one. These
 * five just get a fine-tuned adapter instead, which is more accurate — so the list is
 * a set of suggestions, not a set of permitted values.</p>
 *
 * <p>The story-point model does read domain as a feature, so a new domain does cost it
 * one signal. That is a smaller price than forcing a healthcare team to file itself
 * under "finance", which would actively mislead both models.</p>
 */
export const TUNED_DOMAINS = [
  "finance",
  "ecommerce",
  "healthcare",
  "logistics",
  "education",
] as const;

export const DOMAIN_LABELS: Record<string, string> = {
  finance: "Finance",
  ecommerce: "E-commerce",
  healthcare: "Healthcare",
  logistics: "Logistics",
  education: "Education",
};

export function domainLabel(value: string): string {
  return DOMAIN_LABELS[value] ?? value;
}

/** Whether the velocity model has a fine-tuned adapter for this domain. */
export function isTunedDomain(value: string | null | undefined): boolean {
  return value != null && (TUNED_DOMAINS as readonly string[]).includes(value.toLowerCase());
}

export const EXPERIENCE_LEVELS = [
  { value: "junior", label: "Mostly junior" },
  { value: "mid", label: "Mostly mid-level" },
  { value: "senior", label: "Mostly senior" },
] as const;
