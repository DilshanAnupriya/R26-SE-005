import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// .mts so Vite loads this as ESM natively; a .ts config is read as CommonJS and
// warns about the ESM syntax below.
export default defineConfig({
  plugins: [react()],
  resolve: {
    // Native replacement for vite-tsconfig-paths: tests import through "@/" exactly
    // as application code does, from the same tsconfig, with no second alias map.
    tsconfigPaths: true,
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/tests/setup.ts"],
    include: ["src/**/*.{test,spec}.{ts,tsx}"],
    // Playwright specs live in tests/e2e and are driven by Playwright, not Vitest.
    exclude: ["node_modules/**", ".next/**", "tests/e2e/**"],
    css: false,
  },
});
