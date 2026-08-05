import { describe, expect, it } from "vitest";
import { render } from "@testing-library/react";
import { axe } from "vitest-axe";
import type { AxeResults } from "axe-core";

import { AuthCard } from "@/features/auth/components/auth-card";
import { FormError } from "@/features/auth/components/form-error";
import { PasswordStrength } from "@/features/auth/components/password-strength";
import { EmptyState, ErrorState } from "@/components/feedback/states";
import { BoardPreview } from "@/components/marketing/board-preview";
import { Models } from "@/components/marketing/models";
import { Pricing } from "@/components/marketing/pricing";
import { Faq } from "@/components/marketing/faq";
import { Analytics } from "@/components/marketing/analytics";
import { ApiError } from "@/lib/api/errors";

/**
 * Automated accessibility checks.
 *
 * <p>axe catches roughly a third of real accessibility problems — it cannot tell
 * whether a label is *meaningful*, only whether one exists. It is a floor, not a
 * ceiling: it stops regressions like an unlabelled icon button or a skipped heading
 * level, which are exactly the things that slip in during a refactor.</p>
 *
 * <p><b>Colour contrast is NOT checked here.</b> axe computes contrast by rendering
 * to a canvas, and jsdom has none — the rule is skipped silently, which is why these
 * tests log "HTMLCanvasElement's getContext() method" warnings. Treating this file as
 * proof of contrast would be reading a green tick that was never earned. Contrast is
 * covered in the Playwright run instead, where a real browser can measure it.</p>
 */
async function expectNoViolations(ui: React.ReactElement) {
  const { container } = render(ui);
  const results = (await axe(container)) as AxeResults;

  if (results.violations.length > 0) {
    // Name the rule and the element rather than just failing — a bare "expected 3 to
    // be 0" sends the next person hunting.
    const summary = results.violations
      .map((violation) => `${violation.id}: ${violation.help}\n  ${violation.nodes[0]?.html ?? ""}`)
      .join("\n");
    throw new Error(`Accessibility violations:\n${summary}`);
  }
  expect(results.violations).toHaveLength(0);
}

describe("accessibility — auth", () => {
  it("auth card has no violations", async () => {
    await expectNoViolations(
      <AuthCard title="Welcome back" description="Sign in to continue.">
        <p>Form goes here</p>
      </AuthCard>,
    );
  });

  it("form error is announced and readable", async () => {
    await expectNoViolations(
      <FormError
        error={
          new ApiError({
            status: 401,
            code: "INVALID_CREDENTIALS",
            message: "Invalid email or password",
            correlationId: "corr-1",
          })
        }
      />,
    );
  });

  it("password strength meter has no violations", async () => {
    await expectNoViolations(<PasswordStrength password="Str0ng!Passw0rd#2026" />);
  });
});

describe("accessibility — feedback states", () => {
  it("empty state has no violations", async () => {
    await expectNoViolations(
      <EmptyState title="No projects yet" description="Create one to get started." />,
    );
  });

  it("permission error state has no violations", async () => {
    await expectNoViolations(
      <ErrorState
        error={new ApiError({ status: 403, code: "ACCESS_DENIED", message: "Nope" })}
      />,
    );
  });
});

describe("accessibility — marketing", () => {
  // The marketing page is the one surface reached by people who have never seen the
  // product, on whatever device and assistive technology they happen to use.
  it("board preview has no violations", async () => {
    await expectNoViolations(<BoardPreview />);
  });

  it("models section has no violations", async () => {
    await expectNoViolations(<Models />);
  });

  it("pricing section has no violations", async () => {
    await expectNoViolations(<Pricing />);
  });

  it("faq has no violations", async () => {
    await expectNoViolations(<Faq />);
  });

  it("analytics chart has a text alternative", async () => {
    // A bar chart built from divs is invisible to a screen reader without one.
    const { container } = render(<Analytics />);
    const chart = container.querySelector('[role="img"]');

    expect(chart).not.toBeNull();
    expect(chart?.getAttribute("aria-label")).toMatch(/velocity/i);

    await expectNoViolations(<Analytics />);
  });
});
