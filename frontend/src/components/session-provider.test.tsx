import { StrictMode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { SessionProvider, useSession } from "./session-provider";
import { getActiveOrganization } from "@/lib/api/org-context";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
}));

const USER = {
  id: "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
  email: "dilshan@acme.test",
  fullName: "Dilshan",
  emailVerified: true,
  mfaEnabled: false,
  activeOrganizationId: "2c9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
  activeOrganizationName: "Acme Robotics",
  organizations: [],
  permissions: ["goal:read"],
};

const SESSION = {
  accessToken: "access-token-1",
  tokenType: "Bearer",
  expiresIn: 900,
  user: USER,
};

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });

function Probe() {
  const { status, activeOrganizationId } = useSession();
  return (
    <div>
      <span data-testid="status">{status}</span>
      <span data-testid="org">{activeOrganizationId ?? "none"}</span>
    </div>
  );
}

describe("SessionProvider boot", () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn(async () => json(SESSION));
    vi.stubGlobal("fetch", fetchMock);
  });

  function callsTo(path: string): number {
    return fetchMock.mock.calls.filter(([url]) => String(url) === path).length;
  }

  it("exchanges the cookie exactly once under StrictMode's double-invoke", async () => {
    // The exchange rotates the refresh token, and the backend treats a rotated token
    // presented twice as theft — it revokes the whole family and signs the user out
    // of every device. StrictMode fires mount effects twice in development, so an
    // unguarded boot sends two requests carrying the same cookie.
    render(
      <StrictMode>
        <SessionProvider>
          <Probe />
        </SessionProvider>
      </StrictMode>,
    );

    await waitFor(() => expect(screen.getByTestId("status")).toHaveTextContent("authenticated"));

    expect(callsTo("/api/auth/session")).toBe(1);
  });

  it("still boots when the provider is genuinely remounted", async () => {
    const first = render(
      <SessionProvider>
        <Probe />
      </SessionProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("status")).toHaveTextContent("authenticated"));
    first.unmount();

    render(
      <SessionProvider>
        <Probe />
      </SessionProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("status")).toHaveTextContent("authenticated"));

    // The latch is per provider instance, not global — a new provider is a new session
    // boot, which is what a full client-side navigation between layouts produces.
    expect(callsTo("/api/auth/session")).toBe(2);
  });

  it("settles anonymous when there is no cookie, without redirecting", async () => {
    fetchMock.mockImplementation(async () => json({ message: "no session" }, 401));

    render(
      <SessionProvider>
        <Probe />
      </SessionProvider>,
    );

    await waitFor(() => expect(screen.getByTestId("status")).toHaveTextContent("anonymous"));
    expect(callsTo("/api/auth/session")).toBe(1);
  });

  it("publishes the organization for the API client before rendering it", async () => {
    render(
      <SessionProvider>
        <Probe />
      </SessionProvider>,
    );

    await waitFor(() => expect(screen.getByTestId("org")).toHaveTextContent(USER.activeOrganizationId));

    // The header source and the rendered value must agree. They are deliberately not
    // the same storage — one is read on every request, the other on every render —
    // so nothing but a test catches them drifting apart.
    expect(getActiveOrganization()).toBe(USER.activeOrganizationId);
  });
});
