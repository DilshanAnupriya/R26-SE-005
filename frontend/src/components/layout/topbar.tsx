"use client";

import { Search } from "lucide-react";
import { NotificationBell } from "./notification-bell";
import { UserMenu } from "./user-menu";
import { useUiStore } from "@/stores/ui-store";

export function Topbar({
  orgSlug,
  breadcrumb,
}: {
  orgSlug: string;
  breadcrumb?: React.ReactNode;
}) {
  const openPalette = useUiStore((state) => state.setCommandPaletteOpen);

  return (
    <header className="flex h-12 shrink-0 items-center gap-3 border-b px-4">
      <div className="min-w-0 flex-1">{breadcrumb}</div>

      <button
        type="button"
        onClick={() => openPalette(true)}
        className="flex items-center gap-2 rounded-md border px-2.5 py-1.5 text-xs text-muted-foreground transition-colors hover:bg-accent focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
        aria-label="Search and commands"
      >
        <Search className="size-3.5" aria-hidden />
        <span className="hidden sm:inline">Search…</span>
        {/* The shortcut is advertised on the control rather than hidden in docs —
            the palette is a primary navigation path, not a power-user secret. */}
        <kbd className="hidden rounded border bg-muted px-1 font-mono text-2xs sm:inline">⌘K</kbd>
      </button>

      <NotificationBell orgSlug={orgSlug} />

      <UserMenu />
    </header>
  );
}
