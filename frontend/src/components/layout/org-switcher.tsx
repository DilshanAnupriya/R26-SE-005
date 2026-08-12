"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Check, ChevronsUpDown, Loader2, Plus } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { useSession } from "@/components/session-provider";
import { cn } from "@/lib/utils";
import { toast } from "sonner";

/** The org's plan tier, from the caller's role. Falls back to a neutral word. */
function planLabel(roleCode?: string | null): string {
  if (!roleCode) return "Workspace";
  return roleCode.charAt(0) + roleCode.slice(1).toLowerCase();
}

/** First letters of the organization name, for the avatar fallback. */
function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((word) => word[0]?.toUpperCase() ?? "")
    .join("");
}

export function OrgSwitcher({ collapsed }: { collapsed: boolean }) {
  const router = useRouter();
  const { user, switchOrganization, activeOrganizationId } = useSession();
  const [switching, setSwitching] = useState<string | null>(null);

  const organizations = user?.organizations ?? [];
  const active = organizations.find((org) => org.id === activeOrganizationId) ?? organizations[0];

  if (!active) return null;

  async function handleSelect(organizationId: string, slug: string) {
    if (organizationId === activeOrganizationId) return;
    setSwitching(organizationId);
    try {
      // Switching re-mints the token pair with a new tenant claim, so this is a
      // round trip, not a client-side filter.
      await switchOrganization(organizationId);
      router.push(`/app/${slug}/dashboard`);
    } catch {
      toast.error("Could not switch organization. Please try again.");
    } finally {
      setSwitching(null);
    }
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        className={cn(
          "flex w-full items-center gap-2 rounded-md p-1.5 text-left transition-colors hover:bg-accent",
          "focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none",
          collapsed && "justify-center",
        )}
        aria-label={`Current organization: ${active.name}. Switch organization`}
      >
        <Avatar className="size-[30px] rounded-lg">
          {active.logoUrl ? <AvatarImage src={active.logoUrl} alt="" /> : null}
          <AvatarFallback className="rounded-lg bg-[linear-gradient(135deg,var(--accent),var(--violet))] text-sm font-bold text-white">
            {initials(active.name)}
          </AvatarFallback>
        </Avatar>
        {!collapsed ? (
          <>
            {/* The design's two-line company block: name over plan and headcount.
                leading-[1.15] because two 11–13px lines at the default 1.45 drift
                apart enough to read as separate items rather than one identity. */}
            <span className="min-w-0 flex-1 leading-[1.15]">
              <span className="block truncate text-base font-semibold">{active.name}</span>
              <span className="block truncate text-xs text-ink-3">
                {planLabel(active.roleCode)} · {organizations.length === 1 ? "1 workspace" : `${organizations.length} workspaces`}
              </span>
            </span>
            <ChevronsUpDown className="size-4 shrink-0 text-ink-3" aria-hidden />
          </>
        ) : null}
      </DropdownMenuTrigger>

      <DropdownMenuContent align="start" className="w-60">
        <DropdownMenuLabel className="text-2xs font-normal text-muted-foreground">
          Organizations
        </DropdownMenuLabel>
        {organizations.map((org) => (
          <DropdownMenuItem
            key={org.id}
            onSelect={() => void handleSelect(org.id, org.slug)}
            className="gap-2"
          >
            <Avatar className="size-5 rounded">
              {org.logoUrl ? <AvatarImage src={org.logoUrl} alt="" /> : null}
              <AvatarFallback className="rounded bg-muted text-2xs">
                {initials(org.name)}
              </AvatarFallback>
            </Avatar>
            <span className="flex-1 truncate">{org.name}</span>
            {switching === org.id ? (
              <Loader2 className="size-3.5 animate-spin" aria-hidden />
            ) : org.id === activeOrganizationId ? (
              <Check className="size-3.5" aria-hidden />
            ) : null}
          </DropdownMenuItem>
        ))}
        <DropdownMenuSeparator />
        <DropdownMenuItem className="gap-2 text-muted-foreground" disabled>
          <Plus className="size-3.5" aria-hidden />
          New organization
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
