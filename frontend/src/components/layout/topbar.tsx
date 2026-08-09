"use client";

import Link from "next/link";
import { Moon, Plus, Search, Sun } from "lucide-react";
import { useTheme } from "next-themes";
import { useEffect, useState } from "react";
import { BrandLockup } from "@/components/design/brand";
import { NotificationBell } from "./notification-bell";
import { UserMenu } from "./user-menu";
import { WorkTimer } from "./work-timer";
import { useUiStore } from "@/stores/ui-store";
import { useSession } from "@/components/session-provider";

export function Topbar({ orgSlug, crumb }: { orgSlug: string; crumb?: string }) {
  const openPalette = useUiStore((state) => state.setCommandPaletteOpen);
  const { user } = useSession();
  const { resolvedTheme, setTheme } = useTheme();

  // The theme is unknown until the provider has read the stored preference, and
  // rendering the wrong icon first produces a visible flip on every load.
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  const orgName = user?.activeOrganizationName ?? "Workspace";

  return (
    <header className="relative z-20 flex h-[52px] flex-none items-center gap-3.5 border-b border-line bg-[color-mix(in_srgb,var(--surface)_82%,transparent)] px-4 backdrop-blur-xl">
      {/* Product identity lives here; the company lives in the sidebar. Keeping them
          on separate surfaces means neither has to be truncated to fit the other,
          and a workspace switch never looks like the product changed. */}
      <Link
        href={`/app/${orgSlug}/dashboard`}
        className="flex max-w-60 min-w-0 flex-none items-center gap-[7px] rounded-md transition-opacity hover:opacity-80 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
      >
        <BrandLockup />
        {crumb ? (
          <>
            <span className="text-ink-3">/</span>
            <span className="truncate text-base font-semibold">{crumb}</span>
          </>
        ) : null}
      </Link>

      <button
        type="button"
        onClick={() => openPalette(true)}
        className="mx-auto flex h-[34px] max-w-[440px] min-w-[120px] flex-1 items-center gap-2.5 rounded-[9px] border border-line bg-surface-2 px-2.5 text-base text-ink-3 transition-colors hover:border-line-strong hover:bg-surface focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
        aria-label="Search issues, projects and documents"
      >
        <Search className="size-[15px] flex-none" aria-hidden />
        <span className="flex-1 truncate text-left">Search issues, projects, docs…</span>
        {/* The shortcut is advertised on the control: the palette is a primary way
            to navigate, not a power-user secret. */}
        <kbd className="rounded-[5px] border border-line-strong bg-surface px-1.5 py-0.5 font-mono text-xs">
          ⌘K
        </kbd>
      </button>

      <div className="flex items-center gap-1">
        <WorkTimer />

        <NotificationBell orgSlug={orgSlug} />

        <button
          type="button"
          onClick={() => setTheme(resolvedTheme === "dark" ? "light" : "dark")}
          className="flex size-8 items-center justify-center rounded-md text-ink-2 transition-colors hover:bg-surface-3 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
          aria-label={resolvedTheme === "dark" ? "Switch to light theme" : "Switch to dark theme"}
        >
          {mounted && resolvedTheme === "dark" ? (
            <Sun className="size-[18px]" aria-hidden />
          ) : (
            <Moon className="size-[18px]" aria-hidden />
          )}
        </button>

        <button
          type="button"
          onClick={() => openPalette(true)}
          className="ml-1 flex h-8 items-center gap-1.5 rounded-md bg-accent pr-3 pl-2.5 text-base font-semibold text-white shadow-card transition-colors hover:bg-accent-hover focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
        >
          <Plus className="size-4" aria-hidden />
          Create
        </button>

        <div className="ml-1">
          <UserMenu />
        </div>
      </div>
    </header>
  );
}
