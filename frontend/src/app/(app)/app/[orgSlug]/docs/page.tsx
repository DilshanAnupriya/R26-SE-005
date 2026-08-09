import type { Metadata } from "next";
import { NotConnectedYet } from "@/components/design/surfaces";

export const metadata: Metadata = { title: "Documents · SprintIQ" };

export default function Page() {
  return (
    <div className="mx-auto max-w-[1360px] animate-fade-up px-1 pb-14">
      <h1 className="text-2xl font-semibold tracking-[-0.02em]">Documents</h1>
      <p className="mt-1 mb-5 text-base text-ink-3">Specs, notes and decisions beside the work</p>
      <NotConnectedYet
        title="Documents are not built yet"
        body="Attachments exist for files on an item. A document store with editing and version history is a separate capability that has not been started."
      />
    </div>
  );
}
