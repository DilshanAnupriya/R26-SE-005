import { Suspense } from "react";
import AllocationDashboard from "@/features/resource-allocation/components/AllocationDashboard";

export default function AllocationPage() {
  return (
    <Suspense fallback={
      <div className="min-h-screen flex items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent" />
      </div>
    }>
      <AllocationDashboard />
    </Suspense>
  );
}

