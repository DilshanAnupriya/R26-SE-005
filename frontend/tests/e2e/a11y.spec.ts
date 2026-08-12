import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

/**
 * Accessibility in a real browser.
 *
 * <p>This is where colour contrast is actually measured. The Vitest axe run cannot
 * check it — axe rasterises to a canvas to compute contrast and jsdom has none, so
 * the rule is skipped there without saying so. Here it runs for real.</p>
 */

const PAGES = [
  { path: "/", name: "landing" },
  { path: "/login", name: "login" },
  { path: "/register", name: "register" },
  { path: "/forgot-password", name: "forgot password" },
] as const;

for (const page_ of PAGES) {
  test(`${page_.name} has no accessibility violations`, async ({ page }) => {
    await page.goto(page_.path);
    // Reveals hide content until they run; auditing mid-animation would flag
    // legitimately-hidden elements.
    await page.waitForLoadState("networkidle");

    const results = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
      .analyze();

    // Name the rule and element on failure — "expected 2 to be 0" sends the next
    // person hunting.
    const summary = results.violations
      .map((v) => `${v.id} (${v.impact}): ${v.help}\n    ${v.nodes[0]?.html ?? ""}`)
      .join("\n");
    expect(results.violations, summary).toEqual([]);
  });
}

test("landing page passes colour contrast in both themes", async ({ page }) => {
  for (const scheme of ["light", "dark"] as const) {
    await page.emulateMedia({ colorScheme: scheme });
    await page.goto("/");
    await page.waitForLoadState("networkidle");

    const results = await new AxeBuilder({ page })
      .withRules(["color-contrast"])
      .analyze();

    const summary = results.violations
      .map((v) => `${scheme}: ${v.nodes.map((n) => n.html).join("\n      ")}`)
      .join("\n");
    expect(results.violations, summary).toEqual([]);
  }
});

test("keyboard reaches the content past the nav", async ({ page }) => {
  await page.goto("/");
  await page.keyboard.press("Tab");

  // The skip link must be the first stop, and must become visible on focus — a
  // permanently invisible one is worse than none, because it silently swallows a tab.
  const skipLink = page.getByRole("link", { name: /skip to content/i });
  await expect(skipLink).toBeFocused();
  await expect(skipLink).toBeVisible();
});
