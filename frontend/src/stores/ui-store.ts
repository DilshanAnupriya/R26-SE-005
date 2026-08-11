import { create } from "zustand";
import { persist, createJSONStorage } from "zustand/middleware";

/**
 * Client-only UI state.
 *
 * <p>Strictly what the server has no opinion about. Nothing that came from an API
 * belongs here — TanStack Query owns server state, and duplicating a project list
 * into Zustand creates two copies that disagree the moment one is invalidated.</p>
 *
 * <p>Only genuinely durable preferences are persisted. Whether the command palette
 * happens to be open is not something a user expects to survive a reload.</p>
 */
/** Stand-in during server rendering, where persistence is meaningless. */
const memoryStorage: Storage = {
  length: 0,
  key: () => null,
  getItem: () => null,
  setItem: () => undefined,
  removeItem: () => undefined,
  clear: () => undefined,
};

interface UiState {
  sidebarCollapsed: boolean;
  commandPaletteOpen: boolean;

  toggleSidebar: () => void;
  setSidebarCollapsed: (collapsed: boolean) => void;
  setCommandPaletteOpen: (open: boolean) => void;
  toggleCommandPalette: () => void;
}

export const useUiStore = create<UiState>()(
  persist(
    (set) => ({
      sidebarCollapsed: false,
      commandPaletteOpen: false,

      toggleSidebar: () => set((state) => ({ sidebarCollapsed: !state.sidebarCollapsed })),
      setSidebarCollapsed: (sidebarCollapsed) => set({ sidebarCollapsed }),
      setCommandPaletteOpen: (commandPaletteOpen) => set({ commandPaletteOpen }),
      toggleCommandPalette: () =>
        set((state) => ({ commandPaletteOpen: !state.commandPaletteOpen })),
    }),
    {
      name: "sprintiq-ui",
      // Reached through `window` rather than the bare global: the store is imported
      // by components that also render on the server, where no Storage exists, and
      // Node 25 exposes an inert `localStorage` lexical global that is not the
      // browser's. Going through `window` gets the real one or nothing.
      storage: createJSONStorage(() =>
        typeof window === "undefined" ? memoryStorage : window.localStorage,
      ),
      // Persist the sidebar only. Rehydrating an open command palette would greet
      // the user with a modal they never asked for.
      partialize: (state) => ({ sidebarCollapsed: state.sidebarCollapsed }),
    },
  ),
);
