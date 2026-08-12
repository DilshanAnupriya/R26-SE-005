import { beforeEach, describe, expect, it, vi } from "vitest";
import { z } from "zod";
import { ApiClient } from "./client";
import { ApiError } from "./errors";
import { TokenStore, type RefreshFn } from "./token-store";

const projectSchema = z.object({ id: z.uuid(), name: z.string() });
const PROJECT = { id: "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed", name: "Mobile Banking" };

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });

const unauthorized = () =>
  json({ status: 401, code: "TOKEN_EXPIRED", message: "Token has expired" }, 401);

/**
 * Awaits a rejection and returns it typed. A bare `.catch()` widens the result to a
 * union with the success type, and a test that silently passes because the call
 * resolved is worse than one that fails.
 */
async function rejectionOf(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    return error as ApiError;
  }
  throw new Error("Expected the request to reject, but it resolved");
}

describe("ApiClient", () => {
  let store: TokenStore;
  let fetchMock: ReturnType<typeof vi.fn>;
  let client: ApiClient;
  let organizationId: string | null;

  beforeEach(() => {
    store = new TokenStore();
    organizationId = "2c9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed";
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    client = new ApiClient({
      baseUrl: "http://localhost:8080/api/v1",
      tokenStore: store,
      getOrganizationId: () => organizationId,
    });
  });

  const lastRequest = () => {
    const [url, init] = fetchMock.mock.lastCall as [string, RequestInit];
    return { url, init, headers: init.headers as Record<string, string> };
  };

  describe("request shaping", () => {
    it("attaches the bearer token, tenant header and a correlation id", async () => {
      store.setAccessToken("access-token");
      fetchMock.mockResolvedValue(json(PROJECT));

      await client.get("/projects/1", { schema: projectSchema });

      const { headers } = lastRequest();
      expect(headers.Authorization).toBe("Bearer access-token");
      expect(headers["X-Organization-Id"]).toBe(organizationId);
      expect(headers["X-Correlation-Id"]).toMatch(/^[0-9a-f-]{36}$/);
    });

    it("omits the tenant header when no organization is active", async () => {
      store.setAccessToken("access-token");
      organizationId = null;
      fetchMock.mockResolvedValue(json(PROJECT));

      await client.get("/projects/1", { schema: projectSchema });

      expect(lastRequest().headers["X-Organization-Id"]).toBeUndefined();
    });

    it("sends no credentials to the backend", async () => {
      // The backend is a stateless bearer-token API. Sending cookies would achieve
      // nothing and widen what a CSRF attempt could reach.
      store.setAccessToken("access-token");
      fetchMock.mockResolvedValue(json(PROJECT));

      await client.get("/projects/1", { schema: projectSchema });

      expect(lastRequest().init.credentials).toBe("omit");
    });

    it("drops empty query parameters instead of sending them as strings", async () => {
      fetchMock.mockResolvedValue(json(PROJECT));

      await client.get("/projects", {
        schema: projectSchema,
        query: { status: "ACTIVE", workspaceId: undefined, leadId: null, q: "" },
      });

      const { url } = lastRequest();
      // "undefined" reaching the backend as a literal string would fail UUID parsing.
      expect(url).toContain("status=ACTIVE");
      expect(url).not.toContain("workspaceId");
      expect(url).not.toContain("leadId");
      expect(url).not.toContain("q=");
    });

    it("skips auth headers entirely when asked", async () => {
      store.setAccessToken("access-token");
      fetchMock.mockResolvedValue(json(PROJECT));

      await client.get("/auth/login", { schema: projectSchema, skipAuth: true });

      const { headers } = lastRequest();
      expect(headers.Authorization).toBeUndefined();
      expect(headers["X-Organization-Id"]).toBeUndefined();
    });
  });

  describe("response handling", () => {
    it("parses and returns the validated body", async () => {
      fetchMock.mockResolvedValue(json(PROJECT));

      const result = await client.get("/projects/1", { schema: projectSchema });
      expect(result).toEqual(PROJECT);
    });

    it("throws a CONTRACT_ERROR naming the field when the shape drifts", async () => {
      // mockImplementation, not mockResolvedValue: a Response body can only be read
      // once, so a shared instance would fail the second call for the wrong reason.
      fetchMock.mockImplementation(async () => json({ id: "not-a-uuid", name: "Broken" }));

      const error = await rejectionOf(client.get("/projects/1", { schema: projectSchema }));

      expect(error.code).toBe("CONTRACT_ERROR");
      // Naming the offending field is the whole point — otherwise a contract drift
      // is indistinguishable from any other failure.
      expect(error.message).toMatch(/id/);
    });

    it("returns nothing for a 204", async () => {
      fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
      await expect(client.delete("/projects/1")).resolves.toBeUndefined();
    });

    it("surfaces a backend error with its code and correlation id intact", async () => {
      fetchMock.mockResolvedValue(
        json(
          {
            status: 403,
            code: "ACCESS_DENIED",
            message: "You do not have permission",
            correlationId: "corr-9",
          },
          403,
        ),
      );

      const error = await rejectionOf(client.get("/projects/1", { schema: projectSchema }));

      expect(error).toBeInstanceOf(ApiError);
      expect(error.code).toBe("ACCESS_DENIED");
      expect(error.correlationId).toBe("corr-9");
    });

    it("reports an unreachable network distinctly from a server error", async () => {
      fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));

      await expect(client.get("/projects/1", { schema: projectSchema })).rejects.toMatchObject({
        code: "NETWORK_ERROR",
      });
    });
  });

  describe("expired token recovery", () => {
    it("refreshes once and replays the request with the new token", async () => {
      store.setAccessToken("expired-token");
      store.configure({ refresh: async () => "fresh-token" });

      fetchMock.mockResolvedValueOnce(unauthorized()).mockResolvedValueOnce(json(PROJECT));

      const result = await client.get("/projects/1", { schema: projectSchema });

      expect(result).toEqual(PROJECT);
      expect(fetchMock).toHaveBeenCalledTimes(2);
      // The replay must carry the NEW token, or it 401s again for the same reason.
      expect(lastRequest().headers.Authorization).toBe("Bearer fresh-token");
    });

    it("refreshes only once for many requests that 401 together", async () => {
      // A board mounting is several parallel queries. Each must not fire its own
      // refresh: the backend rotates refresh tokens and reads a replay as theft,
      // revoking the family and signing the user out of every device.
      store.setAccessToken("expired-token");
      const refresh = vi.fn<RefreshFn>(async () => "fresh-token");
      store.configure({ refresh });

      fetchMock.mockImplementation(async (_url: string, init: RequestInit) => {
        const headers = init.headers as Record<string, string>;
        return headers.Authorization === "Bearer fresh-token" ? json(PROJECT) : unauthorized();
      });

      const results = await Promise.all([
        client.get("/projects/1", { schema: projectSchema }),
        client.get("/projects/2", { schema: projectSchema }),
        client.get("/projects/3", { schema: projectSchema }),
        client.get("/projects/4", { schema: projectSchema }),
        client.get("/projects/5", { schema: projectSchema }),
        client.get("/projects/6", { schema: projectSchema }),
      ]);

      expect(results).toHaveLength(6);
      expect(refresh).toHaveBeenCalledTimes(1);
    });

    it("gives up after one retry rather than looping", async () => {
      // Hammering the refresh endpoint is exactly the traffic reuse detection watches.
      store.setAccessToken("expired-token");
      const refresh = vi.fn<RefreshFn>(async () => "still-rejected");
      store.configure({ refresh });
      fetchMock.mockResolvedValue(unauthorized());

      await expect(client.get("/projects/1", { schema: projectSchema })).rejects.toMatchObject({
        status: 401,
      });
      expect(fetchMock).toHaveBeenCalledTimes(2);
      expect(refresh).toHaveBeenCalledTimes(1);
    });

    it("does not attempt a refresh for auth routes", async () => {
      const refresh = vi.fn<RefreshFn>(async () => "fresh");
      store.configure({ refresh });
      fetchMock.mockResolvedValue(
        json({ status: 401, code: "INVALID_CREDENTIALS", message: "Bad password" }, 401),
      );

      await expect(
        client.post("/auth/login", {}, { schema: projectSchema, skipAuth: true }),
      ).rejects.toMatchObject({ code: "INVALID_CREDENTIALS" });

      // A wrong password is not an expired token; refreshing would be nonsense.
      expect(refresh).not.toHaveBeenCalled();
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it("does not refresh on a 403, which another token would not fix", async () => {
      store.setAccessToken("valid-token");
      const refresh = vi.fn<RefreshFn>(async () => "fresh");
      store.configure({ refresh });
      fetchMock.mockResolvedValue(
        json({ status: 403, code: "ACCESS_DENIED", message: "Nope" }, 403),
      );

      await expect(client.get("/projects/1", { schema: projectSchema })).rejects.toMatchObject({
        code: "ACCESS_DENIED",
      });
      expect(refresh).not.toHaveBeenCalled();
    });

    it("surfaces the original 401 when the session ended mid-refresh", async () => {
      store.setAccessToken("expired-token");
      let releaseRefresh!: (token: string) => void;
      const gate = new Promise<string>((resolve) => {
        releaseRefresh = resolve;
      });
      store.configure({ refresh: () => gate });
      fetchMock.mockImplementation(async () => unauthorized());

      const pending = client.get("/projects/1", { schema: projectSchema });
      // The refresh only starts once the first 401 lands, so wait for it rather than
      // assuming it has begun.
      await vi.waitFor(() => expect(store.isRefreshing()).toBe(true));

      // The user logs out while the refresh is in flight.
      store.clear();
      releaseRefresh("token-for-a-session-nobody-is-in");

      // "Your session ended" is the truthful message here — not whatever the
      // abandoned refresh happened to fail with.
      await expect(pending).rejects.toMatchObject({ status: 401 });
    });
  });
});
