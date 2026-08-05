import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, beforeAll, vi } from "vitest";

/**
 * Storage must be installed at module scope, not in `beforeAll`.
 *
 * <p>Setup hooks run after the test file's imports, and any module that captures a
 * Storage reference at import time — zustand's `persist` middleware does — would
 * already be holding the broken one by then.</p>
 *
 * <p>Node 25 exposes its own experimental `localStorage` global. It shadows jsdom's
 * and is inert without `--localstorage-file`: `clear()` and friends are simply
 * absent, so anything touching the Storage API fails in tests for a reason that has
 * nothing to do with the code under test.</p>
 */
function installStorage(name: "localStorage" | "sessionStorage") {
  const entries = new Map<string, string>();

  const storage: Storage = {
    get length() {
      return entries.size;
    },
    key: (index: number) => [...entries.keys()][index] ?? null,
    getItem: (key: string) => entries.get(key) ?? null,
    setItem: (key: string, value: string) => void entries.set(key, String(value)),
    removeItem: (key: string) => void entries.delete(key),
    clear: () => entries.clear(),
  };

  for (const target of new Set<object>([globalThis, window])) {
    Object.defineProperty(target, name, {
      value: storage,
      writable: true,
      configurable: true,
    });
  }
}

installStorage("localStorage");
installStorage("sessionStorage");

afterEach(() => {
  cleanup();
  window.localStorage.clear();
});

beforeAll(() => {
  // jsdom implements neither of these, and Radix overlays call both on open —
  // without the stubs every dialog, popover and command-palette test throws.
  if (!window.matchMedia) {
    window.matchMedia = vi.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }));
  }

  if (!window.ResizeObserver) {
    window.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver;
  }

  if (!Element.prototype.scrollIntoView) {
    Element.prototype.scrollIntoView = vi.fn();
  }
});
