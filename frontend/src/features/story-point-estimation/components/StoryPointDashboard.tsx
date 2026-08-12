"use client";

import { FeatureDashboard } from "@/components/shared/FeatureDashboard";
import { Hash } from "lucide-react";

export default function StoryPointDashboard() {
  return (
    <FeatureDashboard 
      title="Story Point Estimation" 
      description="Automate story point calculation based on historical data and complexity."
      icon={Hash}
    />
  );
}
