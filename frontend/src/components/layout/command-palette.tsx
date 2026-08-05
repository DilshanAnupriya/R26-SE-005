"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import {
  BarChart3,
  Bell,
  FolderKanban,
  LayoutDashboard,
  LogOut,
  Moon,
  Settings,
  Sparkles,
  Sun,
  Users,
} from "lucide-react";
import { useTheme } from "next-themes";
import {
  CommandDialog,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  CommandSeparator,
} from "@/components/ui/command";
import { useUiStore } from "@/stores/ui-store";
import { useSession } from "@/components/session-provider";

const NAVIGATION = [
  { href: "dashboard", label: "Dashboard", icon: LayoutDashboard },
  { href: "projects", label: "Projects", icon: FolderKanban },
  { href: "ai-insights", label: "AI insights", icon: Sparkles },
  { href: "reports", label: "Reports", icon: BarChart3 },
  { href: "team", label: "Team", icon: Users },
  { href: "notifications", label: "Notifications", icon: Bell },
  { href: "settings", label: "Settings", icon: Settings },
] as const;

/**
 * ⌘K navigation.
 *
 * <p>A primary way to move around, not a power-user shortcut — which is why the
 * topbar advertises the key and why the actions here mirror the sidebar exactly
 * rather than being a separate, half-maintained list.</p>
 */
export function CommandPalette({ orgSlug }: { orgSlug: string }) {
  const router = useRouter();
  const { signOut } = useSession();
  const { setTheme } = useTheme();
  const open = useUiStore((state) => state.commandPaletteOpen);
  const setOpen = useUiStore((state) => state.setCommandPaletteOpen);
  const toggle = useUiStore((state) => state.toggleCommandPalette);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key.toLowerCase() !== "k" || !(event.metaKey || event.ctrlKey)) return;

      // Browsers bind ⌘K to the address bar; without this the palette and the
      // browser both react.
      event.preventDefault();
      toggle();
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [toggle]);

  function run(action: () => void) {
    // Close first: leaving the dialog up while the route changes behind it looks
    // like the command did not register.
    setOpen(false);
    action();
  }

  return (
    <CommandDialog
      open={open}
      onOpenChange={setOpen}
      title="Command palette"
      description="Search for a page or run a command"
    >
      <CommandInput placeholder="Search pages and commands…" />
      <CommandList>
        <CommandEmpty>No results.</CommandEmpty>

        <CommandGroup heading="Go to">
          {NAVIGATION.map((item) => {
            const Icon = item.icon;
            return (
              <CommandItem
                key={item.href}
                value={item.label}
                onSelect={() => run(() => router.push(`/app/${orgSlug}/${item.href}`))}
              >
                <Icon className="size-3.5" aria-hidden />
                {item.label}
              </CommandItem>
            );
          })}
        </CommandGroup>

        <CommandSeparator />

        <CommandGroup heading="Appearance">
          <CommandItem value="Light theme" onSelect={() => run(() => setTheme("light"))}>
            <Sun className="size-3.5" aria-hidden />
            Light theme
          </CommandItem>
          <CommandItem value="Dark theme" onSelect={() => run(() => setTheme("dark"))}>
            <Moon className="size-3.5" aria-hidden />
            Dark theme
          </CommandItem>
        </CommandGroup>

        <CommandSeparator />

        <CommandGroup heading="Account">
          <CommandItem value="Sign out" onSelect={() => run(() => void signOut())}>
            <LogOut className="size-3.5" aria-hidden />
            Sign out
          </CommandItem>
        </CommandGroup>
      </CommandList>
    </CommandDialog>
  );
}
