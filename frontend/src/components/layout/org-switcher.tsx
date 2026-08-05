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
        <Avatar className="size-6 rounded-md">
          {active.logoUrl ? <AvatarImage src={active.logoUrl} alt="" /> : null}
          <AvatarFallback className="rounded-md bg-primary text-2xs text-primary-foreground">
            {initials(active.name)}
          </AvatarFallback>
        </Avatar>
        {!collapsed ? (
          <>
            <span className="flex-1 truncate text-sm font-medium">{active.name}</span>
            <ChevronsUpDown className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
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
