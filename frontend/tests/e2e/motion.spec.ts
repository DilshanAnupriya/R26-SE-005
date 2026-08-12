import { expect, test } from "@playwright/test";

/**
 * Reduced motion, run in a browser that actually reports the preference.
 *
 * <p>This suite exists because the failure mode is silent and severe: the reveal
 * works by hiding content and animating it back, so if the reduced-motion path is
 * broken, someone who asked for less motion gets a blank page instead of a calmer
 * one. Shipping that would be worse than shipping no animation at all.</p>
 *
 * <p>The `reduced-motion` Playwright project runs this file with the preference set.</p>
 */

test("content is fully visible when the visitor asks for reduced motion", async ({ page }) => {
  await page.goto("/");
  await page.waitForLoadState("networkidle");

  const headline = page.getByRole("heading", { level: 1 });
  await expect(headline).toBeVisible();

  // Not merely "visible" per Playwright, which tolerates partial opacity — assert the
  // element is fully opaque and unshifted, i.e. no animation was applied at all.
  const state = await headline.evaluate((element) => {
    const style = getComputedStyle(element);
    return { opacity: style.opacity, transform: style.transform };
  });
  expect(Number(state.opacity)).toBe(1);
  expect(["none", "matrix(1, 0, 0, 1, 0, 0)"]).toContain(state.transform);
});

test("sections further down the page are visible without scrolling to them", async ({ page }) => {
  await page.goto("/");
  await page.waitForLoadState("networkidle");

  // With motion reduced there is no scroll trigger, so every section must already be
  // readable — including to a screen reader, which does not scroll.
  for (const heading of [
    /four models, each attached to a decision/i,
    /pricing that scales/i,
    /questions worth asking/i,
  ]) {
    const element = page.getByRole("heading", { name: heading });
    const opacity = await element.evaluate((node) => getComputedStyle(node).opacity);
    expect(Number(opacity), `${heading} should be fully opaque`).toBe(1);
  }
});
