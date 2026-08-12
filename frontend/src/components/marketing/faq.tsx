import { Reveal } from "./reveal";

/**
 * Answers the objections a sceptical engineering manager actually raises, and answers
 * them honestly — including where the product deliberately does less than a buyer
 * might assume.
 */
const FAQS = [
  {
    q: "Does the AI make decisions for my team?",
    a: "No. Every prediction arrives with Accept, Override and Compare. Overriding is a first-class action that is recorded as feedback, not an escape hatch — the difference between what the model suggested and what your team chose is the most useful signal it gets.",
  },
  {
    q: "What happens if the AI service is down?",
    a: "The product keeps working. Predictions degrade to a banner rather than an error, because a sprint board that will not load because an estimate could not be computed is a worse outcome than a missing estimate.",
  },
  {
    q: "Does SprintIQ handle billing or invoicing?",
    a: "No, and it deliberately never will. Cost forecasting is a planning aid — there is no billing, invoicing, profit or payment code anywhere in the system.",
  },
  {
    q: "Can a person belong to more than one organization?",
    a: "Yes. One email is one login, and access is granted per organization, so a contractor working with several clients switches between them without a second account.",
  },
  {
    q: "How is my data separated from other customers?",
    a: "Every tenant-scoped row carries an organization id, enforced at the query level and proven by tests that assert one tenant cannot see another's rows. Reads fail closed: a missing tenant context raises rather than widening the query.",
  },
  {
    q: "Can we export our data?",
    a: "Yes — CSV and JSON per entity. It is your backlog.",
  },
] as const;

export function Faq() {
  return (
    <section className="border-t py-20 sm:py-28">
      <div className="mx-auto max-w-3xl px-4">
        <Reveal className="text-center">
          <h2 className="text-3xl font-semibold tracking-tight text-balance">
            Questions worth asking
          </h2>
        </Reveal>

        <Reveal stagger className="mt-10 divide-y rounded-xl border bg-card">
          {FAQS.map((faq) => (
            <details key={faq.q} className="group p-5 [&_summary::-webkit-details-marker]:hidden">
              {/* <details> rather than a JS accordion: it is keyboard accessible, works
                  without JavaScript, and is findable by the browser's own in-page search. */}
              <summary className="flex cursor-pointer items-center justify-between gap-4 text-sm font-medium">
                {faq.q}
                <span
                  aria-hidden
                  className="shrink-0 text-muted-foreground transition-transform duration-200 group-open:rotate-45"
                >
                  +
                </span>
              </summary>
              <p className="mt-3 text-sm leading-relaxed text-muted-foreground">{faq.a}</p>
            </details>
          ))}
        </Reveal>
      </div>
    </section>
  );
}
