import { Suspense } from "react";
import type { Metadata } from "next";
import { Skeleton } from "@/components/ui/skeleton";
import RiskDashboard from "@/features/risk-analysis/components/RiskDashboard";

export const metadata: Metadata = { title: "Risk Analysis · SprintIQ" };

export default function RiskPage() {
  return (
    // The dashboard reads ?projectId=&sprintId= so it can be linked to from the sprint
    // risk board, and `useSearchParams` needs a boundary above it to prerender against.
    <Suspense
      fallback={
        <div className="space-y-3">
          <Skeleton className="h-9 w-72" />
          <Skeleton className="h-64 w-full" />
        </div>
      }
    >
      <RiskDashboard />
    </Suspense>
  );
}
