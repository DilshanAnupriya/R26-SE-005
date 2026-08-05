import { expect, test } from "@playwright/test";

/**
 * The Phase 1b exit criterion, end to end against the live backend:
 * register → land in the app → reload → sign out → sign back in.
 *
 * <p>Runs against real Postgres and the real Spring API because every interesting
 * thing here is a seam between two systems — the httpOnly cookie, token rotation,
 * the tenant header. A mocked backend would assert that our mock behaves like our
 * mock.</p>
 */

const PASSWORD = "Str0ng!Passw0rd#2026";

/** Unique per run: registration writes to a shared database. */
function uniqueAccount() {
  const suffix = `${Date.now()}${Math.floor(Math.random() * 1000)}`;
  return {
    email: `e2e+${suffix}@example.test`,
    fullName: "E2E Tester",
    organizationName: `E2E Org ${suffix}`,
    password: PASSWORD,
  };
}

async function register(page: import("@playwright/test").Page, account: ReturnType<typeof uniqueAccount>) {
  await page.goto("/register");
  await page.getByLabel("Your name").fill(account.fullName);
  await page.getByLabel("Organization").fill(account.organizationName);
  await page.getByLabel("Work email").fill(account.email);
  await page.getByLabel("Password", { exact: true }).fill(account.password);
  await page.getByLabel("Confirm password").fill(account.password);
  await page.getByRole("button", { name: "Create account" }).click();
}

test.describe("authentication", () => {
  test("register, reload, sign out and sign back in", async ({ page, context }) => {
    const account = uniqueAccount();

    await register(page, account);

    // Registration signs the user straight in and lands them in the app.
    await expect(page).toHaveURL(/\/app\//, { timeout: 20_000 });
    await expect(page.getByRole("heading", { name: /welcome back/i })).toBeVisible();

    // The refresh token must be in an httpOnly cookie, unreadable from JavaScript.
    // This is the single most important assertion in the suite: if it fails, an XSS
    // can steal a 30-day credential.
    const cookies = await context.cookies();
    const refreshCookie = cookies.find((cookie) => cookie.name === "sq_rt");
    expect(refreshCookie, "refresh cookie must be set").toBeDefined();
    expect(refreshCookie!.httpOnly, "refresh cookie must be httpOnly").toBe(true);
    expect(refreshCookie!.sameSite).toBe("Lax");

    const visibleToJs = await page.evaluate(() => document.cookie);
    expect(visibleToJs).not.toContain("sq_rt");

    // A hard reload proves memory-only access-token storage survives, because the
    // cookie can be exchanged for a fresh one at boot.
    await page.reload();
    await expect(page.getByRole("heading", { name: /welcome back/i })).toBeVisible({
      timeout: 20_000,
    });

    await page.getByRole("button", { name: "Account menu" }).click();
    await page.getByRole("menuitem", { name: "Sign out" }).click();
    await expect(page).toHaveURL(/\/login/, { timeout: 20_000 });

    // The cookie must be gone — a surviving refresh token after logout is the
    // failure that matters on a shared machine.
    const afterLogout = await context.cookies();
    const stale = afterLogout.find((cookie) => cookie.name === "sq_rt" && cookie.value !== "");
    expect(stale, "refresh cookie must be cleared on sign out").toBeUndefined();

    await page.getByLabel("Email").fill(account.email);
    await page.getByLabel("Password", { exact: true }).fill(account.password);
    await page.getByRole("button", { name: "Sign in" }).click();

    await expect(page).toHaveURL(/\/app\//, { timeout: 20_000 });
    await expect(page.getByRole("heading", { name: /welcome back/i })).toBeVisible();
  });

  test("rejects a wrong password without revealing whether the account exists", async ({ page }) => {
    const account = uniqueAccount();
    await register(page, account);
    await expect(page).toHaveURL(/\/app\//, { timeout: 20_000 });

    await page.getByRole("button", { name: "Account menu" }).click();
    await page.getByRole("menuitem", { name: "Sign out" }).click();
    await expect(page).toHaveURL(/\/login/);

    await page.getByLabel("Email").fill(account.email);
    await page.getByLabel("Password", { exact: true }).fill("WrongPassword!123");
    await page.getByRole("button", { name: "Sign in" }).click();

    const alert = page.getByRole("alert");
    await expect(alert).toBeVisible({ timeout: 15_000 });
    // Must not say "no such user" or "wrong password" — either tells an attacker
    // whether the address is registered.
    await expect(alert).not.toContainText(/no such|not found|unknown user/i);
    await expect(page).toHaveURL(/\/login/);
  });

  test("shows the backend's password rule on the password field, not in a toast", async ({ page }) => {
    await page.goto("/register");
    await page.getByLabel("Your name").fill("Weak Password User");
    await page.getByLabel("Organization").fill("Weak Org");
    await page.getByLabel("Work email").fill(`weak+${Date.now()}@example.test`);
    await page.getByLabel("Password", { exact: true }).fill("short");
    await page.getByLabel("Confirm password").fill("short");
    await page.getByRole("button", { name: "Create account" }).click();

    // The rule checklist appears under the password input, which is where the user
    // can act on it.
    await expect(page.getByText("12 characters or more")).toBeVisible();
    await expect(page).toHaveURL(/\/register/);
  });
});

test.describe("route protection", () => {
  test("sends an anonymous visitor to login and remembers the destination", async ({ page }) => {
    await page.goto("/app/some-org/dashboard");

    await expect(page).toHaveURL(/\/login\?next=/, { timeout: 15_000 });
    expect(new URL(page.url()).searchParams.get("next")).toBe("/app/some-org/dashboard");
  });

  test("lets an anonymous visitor reach the marketing page and reset flow", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();

    // Password reset must never be gated behind a session — you use it precisely
    // because you cannot sign in.
    await page.goto("/forgot-password");
    await expect(page.getByRole("button", { name: "Send reset link" })).toBeVisible();
  });

  test("does not disclose whether an email is registered when resetting", async ({ page }) => {
    await page.goto("/forgot-password");
    await page.getByLabel("Email").fill("definitely-not-registered@example.test");
    await page.getByRole("button", { name: "Send reset link" }).click();

    // Same response as for a real address, or the page becomes an enumeration oracle.
    await expect(page.getByText(/a reset link is on its way/i)).toBeVisible({ timeout: 15_000 });
  });
});
