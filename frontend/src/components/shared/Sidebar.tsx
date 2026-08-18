"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { 
  LayoutDashboard, 
  Users, 
  AlertTriangle, 
  Hash,
  ChevronRight
} from "lucide-react";
import { cn } from "@/lib/utils";

const navigation = [
  { name: "Resource Allocation", shortName: "Allocation", href: "/allocation", icon: Users },
  { name: "Risk Analysis", shortName: "Risk", href: "/risk", icon: AlertTriangle },
  { name: "Story Point Estimation", shortName: "Points", href: "/story-points", icon: Hash },
];

export function Sidebar() {
  const pathname = usePathname();

  return (
    <>
    <aside className="sticky top-0 hidden h-screen w-64 flex-col border-r border-card-border glass lg:flex">
      <div className="p-6 flex items-center space-x-3 border-b border-card-border">
        <div className="w-8 h-8 rounded-lg bg-primary flex items-center justify-center">
          <LayoutDashboard className="text-white w-5 h-5" />
        </div>
        <span className="text-lg font-bold">AI Agile</span>
      </div>
      
      <nav className="flex-1 p-4 space-y-2 overflow-y-auto">
        {navigation.map((item) => {
          const isActive = pathname === item.href;
          return (
            <Link
              key={item.name}
              href={item.href}
              className={cn(
                "group flex items-center justify-between rounded-lg p-3 transition-all",
                isActive 
                  ? "bg-primary/20 text-primary border border-primary/20 shadow-lg shadow-primary/10" 
                  : "text-slate-400 hover:bg-slate-900/50 hover:text-white"
              )}
            >
              <div className="flex items-center space-x-3">
                <item.icon className={cn("w-5 h-5", isActive ? "text-primary" : "group-hover:text-white")} />
                <span className="font-medium">{item.name}</span>
              </div>
              {isActive && <ChevronRight className="w-4 h-4" />}
            </Link>
          );
        })}
      </nav>

      <div className="p-6 border-t border-card-border">
        <div className="flex items-center space-x-3 rounded-lg p-3 glass">
          <div className="w-8 h-8 rounded-full bg-slate-700" />
          <div className="flex-1 min-w-0">
            <p className="text-sm font-medium truncate">Team Member</p>
            <p className="text-xs text-slate-500 truncate">member@agile.ai</p>
          </div>
        </div>
      </div>
    </aside>

    <nav
      className="fixed inset-x-2 bottom-2 z-50 flex rounded-lg border bg-background/95 p-1 shadow-lg backdrop-blur lg:hidden"
      aria-label="Dashboard modules"
    >
      {navigation.map((item) => {
        const isActive = pathname === item.href;
        return (
          <Link
            key={item.name}
            href={item.href}
            aria-current={isActive ? "page" : undefined}
            className={cn(
              "flex min-w-0 flex-1 flex-col items-center gap-1 rounded-md px-1 py-2 text-[10px] font-medium",
              isActive ? "bg-primary/10 text-primary" : "text-muted-foreground",
            )}
          >
            <item.icon className="size-4" aria-hidden />
            <span className="truncate">{item.shortName}</span>
          </Link>
        );
      })}
    </nav>
    </>
  );
}
