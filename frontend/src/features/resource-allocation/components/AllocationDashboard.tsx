"use client";

import { useSearchParams, useRouter, usePathname } from "next/navigation";
import { FeatureDashboard } from "@/components/shared/FeatureDashboard";
import { Users, Bot, Wand2 } from "lucide-react";
import { cn } from "@/lib/utils";
import AIAllocationPage from "@/app/(dashboard)/ai-allocation/page";
import DevelopersPage from "@/app/(dashboard)/developers/page";
import SimulatorPage from "@/app/(dashboard)/simulator/page";

type Tab = "allocation" | "developers" | "simulator";

export default function AllocationDashboard() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const pathname = usePathname();
  
  const tabParam = searchParams.get("tab") as Tab;
  const activeTab = (tabParam && ["allocation", "developers", "simulator"].includes(tabParam)) 
    ? tabParam 
    : "allocation";

  const handleTabChange = (tab: Tab) => {
    const params = new URLSearchParams(window.location.search);
    params.set("tab", tab);
    router.replace(`${pathname}?${params.toString()}`);
  };

  // Dynamic header configuration based on the active tab
  const getTabConfig = () => {
    switch (activeTab) {
      case "allocation":
        return {
          title: "AI Task Allocation",
          description: "Review and approve AI-ranked developer recommendations for each task.",
          icon: Bot,
        };
      case "developers":
        return {
          title: "Developer Intelligence",
          description: "Manage team skills, track performance metrics, and monitor workloads.",
          icon: Users,
        };
      case "simulator":
        return {
          title: "What-If Simulator",
          description: "Simulate and test allocation scenarios to predict sprint velocity and team health.",
          icon: Wand2,
        };
    }
  };

  const { title, description, icon } = getTabConfig();

  return (
    <FeatureDashboard title={title} description={description} icon={icon}>
      <div className="space-y-6">
        {/* Navigation Tabs Bar */}
        <div className="flex border-b border-card-border pb-px">
          <div className="flex space-x-1 bg-slate-950/40 p-1.5 rounded-xl border border-card-border/50">
            <button
              onClick={() => handleTabChange("allocation")}
              className={cn(
                "flex items-center gap-2 px-5 py-2.5 rounded-lg text-sm font-semibold transition-all duration-200",
                activeTab === "allocation"
                  ? "bg-primary text-white shadow-lg shadow-primary/20"
                  : "text-slate-400 hover:text-slate-200 hover:bg-slate-900/40"
              )}
            >
              <Bot className="w-4 h-4" />
              AI Allocation
            </button>
            <button
              onClick={() => handleTabChange("developers")}
              className={cn(
                "flex items-center gap-2 px-5 py-2.5 rounded-lg text-sm font-semibold transition-all duration-200",
                activeTab === "developers"
                  ? "bg-primary text-white shadow-lg shadow-primary/20"
                  : "text-slate-400 hover:text-slate-200 hover:bg-slate-900/40"
              )}
            >
              <Users className="w-4 h-4" />
              Developers
            </button>
            <button
              onClick={() => handleTabChange("simulator")}
              className={cn(
                "flex items-center gap-2 px-5 py-2.5 rounded-lg text-sm font-semibold transition-all duration-200",
                activeTab === "simulator"
                  ? "bg-primary text-white shadow-lg shadow-primary/20"
                  : "text-slate-400 hover:text-slate-200 hover:bg-slate-900/40"
              )}
            >
              <Wand2 className="w-4 h-4" />
              What-If Simulator
            </button>
          </div>
        </div>

        {/* Active View Content Area */}
        <div className="mt-6">
          {activeTab === "allocation" && <AIAllocationPage isEmbedded={true} />}
          {activeTab === "developers" && <DevelopersPage isEmbedded={true} />}
          {activeTab === "simulator" && <SimulatorPage isEmbedded={true} />}
        </div>
      </div>
    </FeatureDashboard>
  );
}
