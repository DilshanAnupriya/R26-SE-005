"use client";

import { FeatureDashboard } from "@/components/shared/FeatureDashboard";
import { AlertTriangle } from "lucide-react";

export default function RiskDashboard() {
  return (
    <FeatureDashboard 
      title="Risk Analysis" 
      description="Identify and mitigate project risks before they impact your delivery."
      icon={AlertTriangle}
    />
  );
}
