import type { Metadata } from "next";
import { NotConnectedYet } from "@/components/design/surfaces";

export const metadata: Metadata = { title: "Goals & OKRs · SprintIQ" };

export default function Page() {
  return (
    <div className="mx-auto max-w-[1360px] animate-fade-up px-1 pb-14">
      <h1 className="text-2xl font-semibold tracking-[-0.02em]">Goals & OKRs</h1>
      <p className="mt-1 mb-5 text-base text-ink-3">Objectives and the work that moves them</p>
      <NotConnectedYet
        title="Goals are not modelled yet"
        body="OKRs need their own schema — objectives, key results and the link from a key result to the epics that advance it. Nothing in the current database backs this screen."
      />
    </div>
  );
}
