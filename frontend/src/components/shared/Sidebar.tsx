"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { 
  LayoutDashboard, 
  Users, 
  DollarSign, 
  AlertTriangle, 
  Hash,
  ChevronRight
} from "lucide-react";
import { cn } from "@/lib/utils";

const navigation = [
  { name: "Resource Allocation", href: "/allocation", icon: Users },
  { name: "Cost Estimation", href: "/cost", icon: DollarSign },
  { name: "Risk Analysis", href: "/risk", icon: AlertTriangle },
  { name: "Story Point Estimation", href: "/story-points", icon: Hash },
];

export function Sidebar() {
  const pathname = usePathname();

  return (
    <div className="flex flex-col w-64 glass border-r border-card-border h-screen sticky top-0">
      <div className="p-6 flex items-center space-x-3 border-b border-card-border">
        <div className="w-8 h-8 rounded-lg bg-primary flex items-center justify-center">
          <LayoutDashboard className="text-white w-5 h-5" />
        </div>
        <span className="font-bold text-lg tracking-tight">AI Agile</span>
      </div>
      
      <nav className="flex-1 p-4 space-y-2 overflow-y-auto">
        {navigation.map((item) => {
          const isActive = pathname === item.href;
          return (
            <Link
              key={item.name}
              href={item.href}
              className={cn(
                "flex items-center justify-between p-3 rounded-xl transition-all group",
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
        <div className="flex items-center space-x-3 p-3 glass rounded-xl">
          <div className="w-8 h-8 rounded-full bg-slate-700" />
          <div className="flex-1 min-w-0">
            <p className="text-sm font-medium truncate">Team Member</p>
            <p className="text-xs text-slate-500 truncate">member@agile.ai</p>
          </div>
        </div>
      </div>
    </div>
  );
}
