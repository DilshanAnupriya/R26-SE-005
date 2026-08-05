"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  BarChart3,
  Bell,
  FolderKanban,
  LayoutDashboard,
  PanelLeftClose,
  PanelLeftOpen,
  Settings,
  Sparkles,
  Users,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { OrgSwitcher } from "./org-switcher";
import { useUiStore } from "@/stores/ui-store";
import { cn } from "@/lib/utils";

const NAV = [
  { href: "dashboard", label: "Dashboard", icon: LayoutDashboard },
  { href: "projects", label: "Projects", icon: FolderKanban },
  { href: "ai-insights", label: "AI insights", icon: Sparkles },
  { href: "reports", label: "Reports", icon: BarChart3 },
  { href: "team", label: "Team", icon: Users },
  { href: "notifications", label: "Notifications", icon: Bell },
] as const;

export function Sidebar({ orgSlug }: { orgSlug: string }) {
  const pathname = usePathname();
  const collapsed = useUiStore((state) => state.sidebarCollapsed);
  const toggle = useUiStore((state) => state.toggleSidebar);

  return (
    <aside
      className={cn(
        "flex shrink-0 flex-col border-r bg-sidebar transition-[width] duration-200 ease-out-quart",
        collapsed ? "w-14" : "w-60",
      )}
      // A landmark, so screen-reader users can jump to navigation directly.
      aria-label="Main"
    >
      <div className="p-2">
        <OrgSwitcher collapsed={collapsed} />
      </div>

      <nav className="flex-1 space-y-0.5 px-2">
        {NAV.map((item) => {
          const href = `/app/${orgSlug}/${item.href}`;
          // startsWith, so a nested route (a project detail page) keeps its section
          // lit rather than leaving the sidebar looking like nothing is selected.
          const active = pathname === href || pathname.startsWith(`${href}/`);
          const Icon = item.icon;

          const link = (
            <Link
              href={href}
              aria-current={active ? "page" : undefined}
              className={cn(
                "flex items-center gap-2.5 rounded-md px-2 py-1.5 text-sm transition-colors",
                "focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none",
                active
                  ? "bg-sidebar-accent font-medium text-sidebar-accent-foreground"
                  : "text-muted-foreground hover:bg-sidebar-accent/60 hover:text-foreground",
                collapsed && "justify-center px-0",
              )}
            >
              <Icon className="size-4 shrink-0" aria-hidden />
              {!collapsed ? <span className="truncate">{item.label}</span> : null}
              {collapsed ? <span className="sr-only">{item.label}</span> : null}
            </Link>
          );

          // Collapsed labels are invisible, so the tooltip is the only way to tell
          // the icons apart.
          return collapsed ? (
            <Tooltip key={item.href}>
              <TooltipTrigger asChild>{link}</TooltipTrigger>
              <TooltipContent side="right">{item.label}</TooltipContent>
            </Tooltip>
          ) : (
            <div key={item.href}>{link}</div>
          );
        })}
      </nav>

      <div className="space-y-0.5 border-t p-2">
        <Link
          href={`/app/${orgSlug}/settings`}
          className={cn(
            "flex items-center gap-2.5 rounded-md px-2 py-1.5 text-sm text-muted-foreground transition-colors hover:bg-sidebar-accent/60 hover:text-foreground",
            "focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none",
            collapsed && "justify-center px-0",
          )}
        >
          <Settings className="size-4 shrink-0" aria-hidden />
          {!collapsed ? <span>Settings</span> : <span className="sr-only">Settings</span>}
        </Link>

        <Button
          variant="ghost"
          size="sm"
          onClick={toggle}
          className={cn("w-full text-muted-foreground", collapsed ? "px-0" : "justify-start")}
          aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
          aria-expanded={!collapsed}
        >
          {collapsed ? (
            <PanelLeftOpen className="size-4" aria-hidden />
          ) : (
            <>
              <PanelLeftClose className="size-4" aria-hidden />
              <span>Collapse</span>
            </>
          )}
        </Button>
      </div>
    </aside>
  );
}
