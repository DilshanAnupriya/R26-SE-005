"use client";

import { FeatureDashboard } from "@/components/shared/FeatureDashboard";
import { DollarSign } from "lucide-react";

export default function CostDashboard() {
  return (
    <FeatureDashboard 
      title="Cost Estimation" 
      description="Predict project costs and budget requirements with ML forecasting."
      icon={DollarSign}
    />
  );
}
