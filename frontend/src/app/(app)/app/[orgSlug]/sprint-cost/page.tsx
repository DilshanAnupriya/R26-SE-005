import type { Metadata } from "next";
import CostDashboard from "@/features/cost-estimation/components/CostDashboard";

export const metadata: Metadata = { title: "Sprint Cost · SprintIQ" };

export default function SprintCostPage() {
  return <CostDashboard />;
}
