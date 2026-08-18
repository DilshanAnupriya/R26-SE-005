import { expect, test } from "@playwright/test";

const PASSWORD = "Str0ng!Passw0rd#2026";

test("opens Sprint Cost from the main dashboard", async ({ page }) => {
  const suffix = `${Date.now()}${Math.floor(Math.random() * 1000)}`;

  await page.goto("/register");
  await page.getByLabel("Your name").fill("Sprint Cost E2E");
  await page.getByLabel("Organization").fill(`Sprint Cost Org ${suffix}`);
  await page.getByLabel("Work email").fill(`sprint-cost+${suffix}@example.test`);
  await page.getByLabel("Password", { exact: true }).fill(PASSWORD);
  await page.getByLabel("Confirm password").fill(PASSWORD);
  await page.getByRole("button", { name: "Create account" }).click();

  await expect(page).toHaveURL(/\/app\//, { timeout: 20_000 });
  const orgSlug = new URL(page.url()).pathname.split("/")[2];
  await page.goto(`/app/${orgSlug}/dashboard`);

  await expect(page.getByText("Sprint Cost Forecast", { exact: true })).toBeVisible();
  const navLink = page.getByRole("link", { name: "Sprint Cost", exact: true });
  await expect(navLink).toHaveAttribute("href", `/app/${orgSlug}/sprint-cost`);

  await page.getByRole("link", { name: "View Forecast" }).click();

  await expect(page).toHaveURL(`/app/${orgSlug}/sprint-cost`);
  await expect(page.getByRole("heading", { name: "Sprint Cost", exact: true })).toBeVisible();
  await expect(page.getByText("No projects yet", { exact: true })).toBeVisible();
});
