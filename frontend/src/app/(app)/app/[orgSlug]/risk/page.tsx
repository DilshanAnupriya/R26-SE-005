import type { Metadata } from "next";
import RiskDashboard from "@/features/risk-analysis/components/RiskDashboard";

export const metadata: Metadata = { title: "Risk Analysis · SprintIQ" };

export default function RiskPage() {
  return <RiskDashboard />;
}
