import { beforeEach, describe, expect, it } from "vitest";
import { useUiStore } from "./ui-store";

describe("useUiStore", () => {
  beforeEach(() => {
    window.localStorage.clear();
    useUiStore.setState({ sidebarCollapsed: false, commandPaletteOpen: false });
  });

  it("toggles the sidebar", () => {
    useUiStore.getState().toggleSidebar();
    expect(useUiStore.getState().sidebarCollapsed).toBe(true);

    useUiStore.getState().toggleSidebar();
    expect(useUiStore.getState().sidebarCollapsed).toBe(false);
  });

  it("toggles the command palette", () => {
    useUiStore.getState().toggleCommandPalette();
    expect(useUiStore.getState().commandPaletteOpen).toBe(true);
  });

  it("persists the sidebar preference but not the command palette", () => {
    useUiStore.setState({ sidebarCollapsed: true, commandPaletteOpen: true });

    const persisted = JSON.parse(window.localStorage.getItem("sprintiq-ui") ?? "{}");
    expect(persisted.state.sidebarCollapsed).toBe(true);
    // Rehydrating an open palette would greet the user with a modal they never
    // asked for.
    expect(persisted.state.commandPaletteOpen).toBeUndefined();
  });
});
