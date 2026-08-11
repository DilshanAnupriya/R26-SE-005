import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end configuration.
 *
 * <p>These run against the real Spring backend, not a mock. The whole point of this
 * suite is to prove the pieces fit together — cookie handling, token rotation, the
 * tenant header — and every one of those is a seam between two systems that a mock
 * would paper over.</p>
 *
 * Requires the backend on :8080 (`make infra && make backend`).
 */
export default defineConfig({
  testDir: "./tests/e2e",
  // Registration writes to a shared database, so parallel workers would race over
  // the same email addresses and organization slugs.
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? "github" : "list",

  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },

  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
      // motion.spec asserts the reduced-motion path specifically. Running it here,
      // where motion is on, would assert that animations do not animate.
      testIgnore: /motion\.spec\.ts/,
    },
    {
      name: "reduced-motion",
      // Through contextOptions: the browser then reports the preference to CSS and
      // matchMedia exactly as a real user's setting would, rather than the test
      // faking it at the application level.
      use: { ...devices["Desktop Chrome"], contextOptions: { reducedMotion: "reduce" } },
      testMatch: /motion\.spec\.ts/,
    },
  ],

  webServer: {
    // `next start` against a production build: dev-mode double-rendering and
    // on-demand compilation make timings unrepresentative and flaky.
    command: "pnpm build && pnpm start",
    url: "http://localhost:3000",
    reuseExistingServer: !process.env.CI,
    timeout: 180_000,
  },
});
