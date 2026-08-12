import { beforeEach, describe, expect, it, vi } from "vitest";
import { StaleSessionError, TokenStore, type RefreshFn } from "./token-store";

const deferred = <T>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
};

/** Lets a pending rejection settle without the runner reporting it unhandled. */
const swallow = (promise: Promise<unknown>) => promise.catch(() => undefined);

describe("TokenStore", () => {
  let store: TokenStore;

  beforeEach(() => {
    store = new TokenStore();
  });

  describe("token storage", () => {
    it("holds the access token in memory", () => {
      expect(store.get()).toBeNull();
      expect(store.hasToken()).toBe(false);

      store.setAccessToken("token-1");
      expect(store.get()).toBe("token-1");
      expect(store.hasToken()).toBe(true);

      store.clear();
      expect(store.get()).toBeNull();
    });

    it("never touches browser storage", () => {
      // The whole point of holding the token in memory is that an XSS reading
      // storage finds nothing. A getter/setter round-trip would pass against a
      // localStorage implementation, so assert on the storage APIs themselves.
      const localSpy = vi.spyOn(Storage.prototype, "setItem");
      const cookieSpy = vi.spyOn(document, "cookie", "set");

      store.setAccessToken("secret-token");
      store.get();
      store.clear();

      expect(localSpy).not.toHaveBeenCalled();
      expect(cookieSpy).not.toHaveBeenCalled();
      localSpy.mockRestore();
      cookieSpy.mockRestore();
    });

    it("keeps the token out of JSON serialisation", () => {
      // #private fields are invisible to JSON.stringify, so a token cannot walk
      // into an error report or a state dump.
      store.setAccessToken("secret-token");
      expect(JSON.stringify(store)).not.toContain("secret-token");
    });
  });

  describe("single-flight guarantee", () => {
    it("collapses concurrent refreshes into one call", async () => {
      const gate = deferred<string>();
      const refresh = vi.fn<RefreshFn>(() => gate.promise);
      store.configure({ refresh });

      // Six requests 401 at the same time — the exact shape of a board mounting.
      const all = Promise.all([
        store.refresh(),
        store.refresh(),
        store.refresh(),
        store.refresh(),
        store.refresh(),
        store.refresh(),
      ]);

      gate.resolve("fresh-token");
      const results = await all;

      expect(refresh).toHaveBeenCalledTimes(1);
      expect(results).toEqual(Array(6).fill("fresh-token"));
      expect(store.get()).toBe("fresh-token");
    });

    it("allows a new refresh after the previous one settles", async () => {
      const refresh = vi.fn<RefreshFn>().mockResolvedValueOnce("a").mockResolvedValueOnce("b");
      store.configure({ refresh });

      expect(await store.refresh()).toBe("a");
      expect(await store.refresh()).toBe("b");
      expect(refresh).toHaveBeenCalledTimes(2);
    });

    it("starts no second call when clear() happens during an in-flight refresh", async () => {
      // The defect this class exists to prevent. A promise cannot be cancelled, so
      // logging out mid-refresh leaves one running; if clear() released the latch,
      // the next refresh would open a SECOND concurrent call, present the rotated
      // token twice, and trip the backend's reuse detection — signing the user out
      // of every device.
      const gate = deferred<string>();
      const refresh = vi.fn<RefreshFn>(() => gate.promise);
      store.configure({ refresh });

      const first = swallow(store.refresh());
      store.clear();
      const second = swallow(store.refresh());

      expect(refresh).toHaveBeenCalledTimes(1);

      gate.resolve("token-for-dead-session");
      await Promise.all([first, second]);

      expect(refresh).toHaveBeenCalledTimes(1);
    });

    it("does not release the latch when a stale attempt settles", async () => {
      // An unconditional release lets a stale attempt reopen the gate for a newer
      // one still in flight, turning a two-call breach into an unbounded one.
      const first = deferred<string>();
      const second = deferred<string>();
      const refresh = vi
        .fn<RefreshFn>()
        .mockImplementationOnce(() => first.promise)
        .mockImplementationOnce(() => second.promise);
      store.configure({ refresh });

      const a = swallow(store.refresh());
      store.clear();
      first.resolve("stale");
      await a;

      // The stale attempt has now settled. A refresh may legitimately start again.
      const b = swallow(store.refresh());
      expect(refresh).toHaveBeenCalledTimes(2);

      // But while THAT one is in flight, further callers must still share it.
      const c = swallow(store.refresh());
      expect(refresh).toHaveBeenCalledTimes(2);

      second.resolve("current");
      await Promise.all([b, c]);
    });

    it("shares one refresh when waiters retry from their own rejection handlers", async () => {
      const refresh = vi
        .fn<RefreshFn>()
        .mockRejectedValueOnce(new Error("boom"))
        .mockResolvedValue("recovered");
      store.configure({ refresh });

      // Waiters receive the post-finally promise, so the latch is already released
      // when their handlers run and their retries share one new call. This is subtle
      // and load-bearing: storing the pre-finally promise would silently break it.
      const results = await Promise.all([
        store.refresh().catch(() => store.refresh()),
        store.refresh().catch(() => store.refresh()),
      ]);

      expect(results).toEqual(["recovered", "recovered"]);
      expect(refresh).toHaveBeenCalledTimes(2);
    });
  });

  describe("session lifecycle", () => {
    it("does not restore the token when a refresh started before clear() resolves", async () => {
      // On a shared machine, a token reappearing after logout is somebody else's
      // session handed to the next person at the keyboard.
      const gate = deferred<string>();
      store.configure({ refresh: () => gate.promise });

      const pending = swallow(store.refresh());
      store.clear();
      gate.resolve("token-from-dead-session");
      await pending;

      expect(store.get()).toBeNull();
    });

    it("rejects waiters of an abandoned refresh with StaleSessionError", async () => {
      const gate = deferred<string>();
      store.configure({ refresh: () => gate.promise });

      const pending = store.refresh();
      store.clear();
      gate.resolve("token-from-dead-session");

      await expect(pending).rejects.toBeInstanceOf(StaleSessionError);
    });

    it("keeps a token set by a fresh login when an older refresh resolves", async () => {
      const gate = deferred<string>();
      store.configure({ refresh: () => gate.promise });

      const pending = swallow(store.refresh());
      store.setAccessToken("fresh-login-token");
      gate.resolve("older-refresh-token");
      await pending;

      expect(store.get()).toBe("fresh-login-token");
    });

    it("does not clear a fresh login token, nor report expiry, when an older refresh fails", async () => {
      // Otherwise: session expires, user logs back in successfully, the doomed old
      // refresh rejects, and they are bounced straight back to /login — a loop they
      // cannot escape by retrying.
      const gate = deferred<string>();
      const onSessionExpired = vi.fn();
      store.configure({ refresh: () => gate.promise, onSessionExpired });

      const pending = swallow(store.refresh());
      store.setAccessToken("fresh-login-token");
      gate.reject(new Error("old token was rotated away"));
      await pending;

      expect(store.get()).toBe("fresh-login-token");
      expect(onSessionExpired).not.toHaveBeenCalled();
    });
  });

  describe("failure handling", () => {
    it("fails every queued waiter and clears the token when refresh fails", async () => {
      const gate = deferred<string>();
      const refresh = vi.fn<RefreshFn>(() => gate.promise);
      const onExpired = vi.fn();
      store.configure({ refresh, onSessionExpired: onExpired });
      store.setAccessToken("stale");

      const waiters = [
        store.refresh().catch((e) => e),
        store.refresh().catch((e) => e),
        store.refresh().catch((e) => e),
      ];
      const cause = new Error("refresh rejected");
      gate.reject(cause);
      const results = await Promise.all(waiters);

      expect(refresh).toHaveBeenCalledTimes(1);
      // The same instance, not a re-wrap: ApiError's status and correlationId must
      // survive for the interceptor and the toast that shows it.
      results.forEach((r) => expect(r).toBe(cause));
      expect(store.get()).toBeNull();
      expect(onExpired).toHaveBeenCalledTimes(1);
    });

    it("recovers: a failed refresh does not poison later attempts", async () => {
      const refresh = vi
        .fn<RefreshFn>()
        .mockRejectedValueOnce(new Error("boom"))
        .mockResolvedValueOnce("recovered");
      store.configure({ refresh });

      await expect(store.refresh()).rejects.toThrow("boom");
      await expect(store.refresh()).resolves.toBe("recovered");
    });

    it("reports expiry once across repeated failures, not once per attempt", async () => {
      // onSessionExpired typically redirects or toasts. Firing it per attempt gives
      // a stack of toasts and a polluted history.
      const onSessionExpired = vi.fn();
      store.configure({
        refresh: vi.fn<RefreshFn>().mockRejectedValue(new Error("down")),
        onSessionExpired,
      });

      await swallow(store.refresh());
      await swallow(store.refresh());
      await swallow(store.refresh());

      expect(onSessionExpired).toHaveBeenCalledTimes(1);
    });

    it("reports expiry again once a new session has been established", async () => {
      const onSessionExpired = vi.fn();
      store.configure({
        refresh: vi.fn<RefreshFn>().mockRejectedValue(new Error("down")),
        onSessionExpired,
      });

      await swallow(store.refresh());
      expect(onSessionExpired).toHaveBeenCalledTimes(1);

      store.setAccessToken("logged-in-again");
      await swallow(store.refresh());
      expect(onSessionExpired).toHaveBeenCalledTimes(2);
    });

    it("rejects with a clear message when refresh() is called before configure()", async () => {
      await expect(store.refresh()).rejects.toThrow(/configure\(\) was never called/);
    });

    it("serves an attempt with the config that started it, not a later one", async () => {
      // React StrictMode double-mounts and Fast Refresh re-runs effects, so a
      // mid-flight reconfigure is routine in development.
      const gate = deferred<string>();
      const firstExpired = vi.fn();
      const secondExpired = vi.fn();
      store.configure({ refresh: () => gate.promise, onSessionExpired: firstExpired });

      const pending = swallow(store.refresh());
      store.configure({ refresh: async () => "unused", onSessionExpired: secondExpired });
      gate.reject(new Error("failed"));
      await pending;

      expect(firstExpired).toHaveBeenCalledTimes(1);
      expect(secondExpired).not.toHaveBeenCalled();
    });
  });
});
