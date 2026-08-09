import type { Metadata } from "next";
import { NotConnectedYet } from "@/components/design/surfaces";

export const metadata: Metadata = { title: "Roadmap · SprintIQ" };

export default function Page() {
  return (
    <div className="mx-auto max-w-[1360px] animate-fade-up px-1 pb-14">
      <h1 className="text-2xl font-semibold tracking-[-0.02em]">Roadmap</h1>
      <p className="mt-1 mb-5 text-base text-ink-3">Epics and milestones across the quarter</p>
      <NotConnectedYet
        title="The roadmap needs the milestone timeline API"
        body="Milestones and epics exist in the backend; the quarter view that arranges them on a timeline is scheduled with the planning APIs."
      />
    </div>
  );
}
