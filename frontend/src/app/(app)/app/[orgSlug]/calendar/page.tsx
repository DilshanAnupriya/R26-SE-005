import type { Metadata } from "next";
import { NotConnectedYet } from "@/components/design/surfaces";

export const metadata: Metadata = { title: "Calendar · SprintIQ" };

export default function Page() {
  return (
    <div className="mx-auto max-w-[1360px] animate-fade-up px-1 pb-14">
      <h1 className="text-2xl font-semibold tracking-[-0.02em]">Calendar</h1>
      <p className="mt-1 mb-5 text-base text-ink-3">Due dates, sprints and absences in one month view</p>
      <NotConnectedYet
        title="The calendar needs an events endpoint"
        body="Due dates and sprint boundaries exist on their own records. Collecting them into a month view, alongside team absences, is a later slice."
      />
    </div>
  );
}
