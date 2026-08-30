"use client";

import { useSearchParams, useRouter, usePathname } from "next/navigation";
import Link from "next/link";
import { Users, Bot, Wand2, ArrowLeft, ShieldAlert, ShieldCheck } from "lucide-react";
import { cn } from "@/lib/utils";
import { useSession } from "@/components/session-provider";
import { Badge } from "@/components/ui/badge";
import AIAllocationView from "./views/AIAllocationView";
import DevelopersView from "./views/DevelopersView";
import SimulatorView from "./views/SimulatorView";

type Tab = "allocation" | "developers" | "simulator";

export default function AllocationDashboard() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const pathname = usePathname();
  const { user, hasPermission } = useSession();

  const roleCode = user?.activeRole?.toUpperCase() || "MEMBER";
  const isManagerOrAdmin = 
    roleCode === "OWNER" || 
    roleCode === "ADMIN" || 
    roleCode === "MANAGER" || 
    hasPermission("sprint:manage");
  
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
              <div className="flex items-center gap-3">
                <h1 className="text-2xl font-bold tracking-tight text-foreground flex items-center gap-2.5">
                  <Bot className="w-7 h-7 text-primary" />
                  Resource Allocation Intelligence
                </h1>
                {isManagerOrAdmin ? (
                  <Badge className="bg-emerald-500/15 text-emerald-400 border-emerald-500/30 gap-1 text-[11px] font-semibold py-0.5 px-2">
                    <ShieldCheck className="w-3 h-3" />
                    Manager Allocation Access
                  </Badge>
                ) : (
                  <Badge className="bg-blue-500/15 text-blue-400 border-blue-500/30 gap-1 text-[11px] font-semibold py-0.5 px-2">
                    <ShieldAlert className="w-3 h-3" />
                    Contributor Read-Only View
                  </Badge>
                )}
              </div>
              <p className="text-sm text-muted-foreground mt-1">
                Intelligent developer ranking, skill-task matching, and what-if simulation for agile delivery.
              </p>
            </div>
          </div>
        </div>

        {/* Read-Only Notice for Developers / Contributors */}
        {!isManagerOrAdmin && (
          <div className="flex items-start sm:items-center gap-3 p-3.5 bg-blue-500/10 border border-blue-500/20 rounded-2xl text-xs text-blue-300">
            <ShieldAlert className="w-4 h-4 text-blue-400 shrink-0 mt-0.5 sm:mt-0" />
            <div className="flex-1 leading-relaxed">
              <span className="font-semibold text-blue-200">Developer Contributor Access:</span>{" "}
              You can view AI match scores, developer intelligence telemetry, and simulation projections. Executing live sprint task allocations and applying what-if simulations is restricted to <strong>Owners</strong>, <strong>Admins</strong>, and <strong>Managers</strong>.
            </div>
          </div>
        )}

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
