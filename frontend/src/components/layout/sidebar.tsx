"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  Bot,
  Calendar,
  ChevronLeft,
  ClipboardList,
  CircleDollarSign,
  Cpu,
  FileText,
  FolderKanban,
  Gauge,
  Home,
  KanbanSquare,
  ListTodo,
  Map,
  Settings,
  Target,
  Users,
  Wand2,
  Workflow,
} from "lucide-react";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { OrgSwitcher } from "./org-switcher";
import { useUiStore } from "@/stores/ui-store";
import { cn } from "@/lib/utils";

type NavItem = {
  href: string;
  label: string;
  icon: typeof Home;
  /** Rendered as a count pill; hidden when the sidebar is collapsed. */
  badge?: number;
};

/**
 * The design's four nav groups. The first is deliberately unlabelled — Home, My Work
 * and Projects are where people start, and a heading above them would be noise.
 * The rest follow the plan → work → workspace progression the design sets out.
 */
const NAV_GROUPS: { title?: string; items: NavItem[] }[] = [
  {
    items: [
      { href: "dashboard", label: "Home", icon: Home },
      { href: "my-work", label: "My Work", icon: ListTodo, badge: 3 },
      { href: "projects", label: "Projects", icon: FolderKanban },
    ],
  },
  {
    title: "Plan",
    items: [
      { href: "roadmap", label: "Roadmap", icon: Map },
      { href: "goals", label: "Goals & OKRs", icon: Target },
      { href: "timeline", label: "Timeline", icon: Gauge },
      { href: "calendar", label: "Calendar", icon: Calendar },
    ],
  },
  {
    title: "Work",
    items: [
      { href: "board", label: "Board", icon: KanbanSquare },
      { href: "backlog", label: "Backlog & Sprints", icon: ClipboardList },
      { href: "reports", label: "Reports", icon: Gauge },
    ],
  },
  {
    title: "Workspace",
    items: [
      { href: "allocation?tab=allocation", label: "Resource Allocation", icon: Cpu },
      { href: "allocation?tab=simulator", label: "What-If Simulator", icon: Wand2 },
      { href: "sprint-cost", label: "Sprint Cost", icon: CircleDollarSign },
      { href: "ai-insights", label: "AI Assistant", icon: Bot },
      { href: "automation", label: "Automation", icon: Workflow },
      { href: "team", label: "Team", icon: Users },
      { href: "docs", label: "Documents", icon: FileText },
      { href: "settings", label: "Settings", icon: Settings },
    ],
  },
];

export function Sidebar({ orgSlug }: { orgSlug: string }) {
  const pathname = usePathname();
  const collapsed = useUiStore((state) => state.sidebarCollapsed);
  const toggle = useUiStore((state) => state.toggleSidebar);
  const expanded = !collapsed;

  return (
    <aside
      className={cn(
        "flex shrink-0 flex-col overflow-hidden border-r border-line bg-surface",
        "transition-[width] duration-200 ease-out-quart",
      )}
      style={{ width: expanded ? "var(--sidebar-w)" : "var(--sidebar-w-collapsed)" }}
      // A landmark, so screen-reader users can jump straight to navigation.
      aria-label="Main"
    >
      <div className="border-b border-line p-3 pb-2.5">
        <OrgSwitcher collapsed={collapsed} />
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto px-2 pt-2 pb-4">
        {NAV_GROUPS.map((group, groupIndex) => (
          <div key={group.title ?? `group-${groupIndex}`} className="mt-2.5 first:mt-0">
            {group.title && expanded ? (
              <div className="px-2 pt-1 pb-1.5 text-2xs font-semibold tracking-[0.06em] text-ink-3 uppercase">
                {group.title}
              </div>
            ) : null}

            {group.items.map((item) => {
              const href = item.href.startsWith("/") ? item.href : `/app/${orgSlug}/${item.href}`;
              // Prefix match, so a nested route (a project detail page) keeps its
              // section lit instead of leaving the sidebar looking unselected.
              const active = pathname === href || pathname.startsWith(`${href}/`);
              const Icon = item.icon;

              const link = (
                <Link
                  href={href}
                  aria-current={active ? "page" : undefined}
                  title={item.label}
                  className={cn(
                    "my-px flex items-center gap-2.5 rounded-md text-base transition-colors",
                    "focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none",
                    expanded ? "px-2.5 py-[7px]" : "justify-center py-2",
                    active
                      ? "bg-accent-soft font-semibold text-accent-text"
                      : "font-medium text-ink-2 hover:bg-surface-3 hover:text-ink",
                  )}
                >
                  <Icon className="size-[18px] shrink-0" strokeWidth={1.8} aria-hidden />
                  {expanded ? <span className="flex-1 truncate">{item.label}</span> : null}
                  {expanded && item.badge ? (
                    <span className="min-w-[18px] rounded-full bg-surface-3 px-1.5 py-px text-center text-2xs font-semibold text-ink-2">
                      {item.badge}
                    </span>
                  ) : null}
                  {!expanded ? <span className="sr-only">{item.label}</span> : null}
                </Link>
              );

              // Collapsed labels are invisible, so a tooltip is the only way to tell
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
          </div>
        ))}
      </div>

      <button
        type="button"
        onClick={toggle}
        className="flex items-center gap-2.5 border-t border-line px-3 py-2.5 text-sm text-ink-3 transition-colors hover:bg-surface-3 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
        aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
        aria-expanded={expanded}
      >
        <ChevronLeft
          className={cn("size-4 shrink-0 transition-transform duration-200", collapsed && "rotate-180")}
          aria-hidden
        />
        {expanded ? <span>Collapse</span> : null}
      </button>
    </aside>
  );
}
