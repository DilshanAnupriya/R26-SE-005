"use client";

import { useSearchParams, useRouter, usePathname } from "next/navigation";
import Link from "next/link";
import { Users, Bot, Wand2, ArrowLeft } from "lucide-react";
import { cn } from "@/lib/utils";
import AIAllocationView from "./views/AIAllocationView";
import DevelopersView from "./views/DevelopersView";
import SimulatorView from "./views/SimulatorView";

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

  return (
    <div className="min-h-screen bg-background text-foreground antialiased p-6 lg:p-10">
      <div className="max-w-7xl mx-auto space-y-6">
        <div>
          <Link
            href="/app"
            className="inline-flex items-center gap-1.5 text-xs text-muted-foreground hover:text-foreground transition-colors mb-3"
          >
            <ArrowLeft className="size-3.5" />
            Back to Workspace & Projects
          </Link>
          
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
            <div>
              <h1 className="text-2xl font-bold tracking-tight text-foreground flex items-center gap-2.5">
                <Bot className="w-7 h-7 text-primary" />
                Resource Allocation Intelligence
              </h1>
              <p className="text-sm text-muted-foreground mt-1">
                Intelligent developer ranking, skill-task matching, and what-if simulation for agile delivery.
              </p>
            </div>
          </div>
        </div>

        {/* Navigation Tabs Bar */}
        <div className="flex border-b border-border pb-px">
          <div className="flex space-x-1 bg-muted/40 p-1 rounded-xl border border-border">
            <button
              onClick={() => handleTabChange("allocation")}
              className={cn(
                "flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold transition-all duration-200",
                activeTab === "allocation"
                  ? "bg-primary text-white shadow-sm"
                  : "text-muted-foreground hover:text-foreground hover:bg-muted"
              )}
            >
              <Bot className="w-3.5 h-3.5" />
              AI Allocation
            </button>
            <button
              onClick={() => handleTabChange("developers")}
              className={cn(
                "flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold transition-all duration-200",
                activeTab === "developers"
                  ? "bg-primary text-white shadow-sm"
                  : "text-muted-foreground hover:text-foreground hover:bg-muted"
              )}
            >
              <Users className="w-3.5 h-3.5" />
              Developer Intelligence
            </button>
            <button
              onClick={() => handleTabChange("simulator")}
              className={cn(
                "flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold transition-all duration-200",
                activeTab === "simulator"
                  ? "bg-primary text-white shadow-sm"
                  : "text-muted-foreground hover:text-foreground hover:bg-muted"
              )}
            >
              <Wand2 className="w-3.5 h-3.5" />
              What-If Simulator
            </button>
          </div>
        </div>

        {/* Active View Content Area */}
        <div className="pt-2">
          {activeTab === "allocation" && <AIAllocationView />}
          {activeTab === "developers" && <DevelopersView />}
          {activeTab === "simulator" && <SimulatorView />}
        </div>
      </div>
    </div>
  );
}
