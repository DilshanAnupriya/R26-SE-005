import { describe, expect, it } from "vitest";
import { NextRequest } from "next/server";
import { proxy, config } from "./proxy";
import { REFRESH_COOKIE } from "./server/cookie-names";

function request(path: string, options: { signedIn?: boolean } = {}) {
  const req = new NextRequest(new URL(`http://localhost:3000${path}`));
  if (options.signedIn) {
    req.cookies.set(REFRESH_COOKIE, "opaque-refresh-token");
  }
  return req;
}

describe("middleware", () => {
  describe("protecting the signed-in area", () => {
    it("redirects to login when there is no session cookie", () => {
      const response = proxy(request("/app/acme/dashboard"));

      expect(response.status).toBe(307);
      const location = new URL(response.headers.get("location")!);
      expect(location.pathname).toBe("/login");
    });

    it("remembers where the visitor was going", () => {
      // Otherwise everyone lands on the dashboard after signing in, and a shared
      // link to a specific board silently loses its destination.
      const response = proxy(request("/app/acme/projects/123?tab=backlog"));

      const location = new URL(response.headers.get("location")!);
      expect(location.searchParams.get("next")).toBe("/app/acme/projects/123?tab=backlog");
    });

    it("lets a request through when the session cookie is present", () => {
      const response = proxy(request("/app/acme/dashboard", { signedIn: true }));

      // NextResponse.next() — no redirect.
      expect(response.headers.get("location")).toBeNull();
    });
  });

  describe("auth pages", () => {
    it("shows the login page to a signed-out visitor", () => {
      const response = proxy(request("/login"));
      expect(response.headers.get("location")).toBeNull();
    });

    it("sends an already signed-in visitor into the app", () => {
      const response = proxy(request("/login", { signedIn: true }));

      const location = new URL(response.headers.get("location")!);
      expect(location.pathname).toBe("/app");
    });

    it("does not trap a signed-out visitor on the password reset page", () => {
      // Resetting a password is something you do precisely because you cannot sign
      // in; gating it behind a session would make it unreachable.
      const response = proxy(request("/reset-password?token=abc"));
      expect(response.headers.get("location")).toBeNull();
    });
  });

  describe("matcher", () => {
    it("does not gate the BFF auth routes", () => {
      // /api/auth/session is exactly what a visitor with no valid session must be
      // able to call; gating it would make recovery impossible.
      expect(config.matcher.some((pattern) => pattern.startsWith("/api"))).toBe(false);
    });

    it("does not gate the marketing page", () => {
      expect(config.matcher).not.toContain("/");
    });
  });
});
