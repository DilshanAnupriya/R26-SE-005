import type { Metadata } from "next";
import { NotConnectedYet } from "@/components/design/surfaces";

export const metadata: Metadata = { title: "Automation · SprintIQ" };

export default function Page() {
  return (
    <div className="mx-auto max-w-[1360px] animate-fade-up px-1 pb-14">
      <h1 className="text-2xl font-semibold tracking-[-0.02em]">Automation</h1>
      <p className="mt-1 mb-5 text-base text-ink-3">Rules that act when work changes</p>
      <NotConnectedYet
        title="Automation is not built yet"
        body="The integrations registry exists in the schema, but the rule engine — trigger, condition, action — has no backend behind it."
      />
    </div>
  );
}
