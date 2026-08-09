import type { Metadata } from "next";
import { NotConnectedYet } from "@/components/design/surfaces";

export const metadata: Metadata = { title: "Timeline · SprintIQ" };

export default function Page() {
  return (
    <div className="mx-auto max-w-[1360px] animate-fade-up px-1 pb-14">
      <h1 className="text-2xl font-semibold tracking-[-0.02em]">Timeline</h1>
      <p className="mt-1 mb-5 text-base text-ink-3">Delivery plotted against dates</p>
      <NotConnectedYet
        title="The timeline needs the Gantt endpoint"
        body="Projects, milestones and sprints all carry dates already. The endpoint that returns them as a single ordered series for a Gantt view has not been built."
      />
    </div>
  );
}
