"use client";

import { FeatureDashboard } from "@/components/shared/FeatureDashboard";
import { Users } from "lucide-react";

export default function AllocationDashboard() {
  return (
    <FeatureDashboard 
      title="Resource Allocation" 
      description="Optimize team assignments and workload distribution using AI matching."
      icon={Users}
    />
  );
}
