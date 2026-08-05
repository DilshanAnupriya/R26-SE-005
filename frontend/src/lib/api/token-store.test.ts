import { beforeEach, describe, expect, it, vi } from "vitest";
import { TokenStore } from "./token-store";

const deferred = <T>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
};

describe("TokenStore", () => {
  let store: TokenStore;

  beforeEach(() => {
    store = new TokenStore();
  });

  it("holds the access token in memory", () => {
    expect(store.get()).toBeNull();
    store.set("token-1");
    expect(store.get()).toBe("token-1");
    store.clear();
    expect(store.get()).toBeNull();
  });

  it("collapses concurrent refreshes into one call", async () => {
    const gate = deferred<string>();
    const refresh = vi.fn(() => gate.promise);
    store.configure({ refresh });

    // Six requests 401 at the same time — the exact shape of a board mounting.
    const all = Promise.all([
      store.refresh(), store.refresh(), store.refresh(),
      store.refresh(), store.refresh(), store.refresh(),
    ]);

    gate.resolve("fresh-token");
    const results = await all;

    expect(refresh).toHaveBeenCalledTimes(1);
    expect(results).toEqual(Array(6).fill("fresh-token"));
    expect(store.get()).toBe("fresh-token");
  });

  it("allows a new refresh after the previous one settles", async () => {
    const refresh = vi.fn().mockResolvedValueOnce("a").mockResolvedValueOnce("b");
    store.configure({ refresh });

    expect(await store.refresh()).toBe("a");
    expect(await store.refresh()).toBe("b");
    expect(refresh).toHaveBeenCalledTimes(2);
  });

  it("fails every queued waiter and clears the token when refresh fails", async () => {
    const gate = deferred<string>();
    const refresh = vi.fn(() => gate.promise);
    const onExpired = vi.fn();
    store.configure({ refresh, onSessionExpired: onExpired });
    store.set("stale");

    const waiters = [
      store.refresh().catch((e) => e),
      store.refresh().catch((e) => e),
      store.refresh().catch((e) => e),
    ];
    gate.reject(new Error("refresh rejected"));
    const results = await Promise.all(waiters);

    expect(refresh).toHaveBeenCalledTimes(1);
    results.forEach((r) => expect(r).toBeInstanceOf(Error));
    expect(store.get()).toBeNull();
    expect(onExpired).toHaveBeenCalledTimes(1);
  });

  it("recovers: a failed refresh does not poison later attempts", async () => {
    const refresh = vi
      .fn()
      .mockRejectedValueOnce(new Error("boom"))
      .mockResolvedValueOnce("recovered");
    store.configure({ refresh });

    await expect(store.refresh()).rejects.toThrow("boom");
    await expect(store.refresh()).resolves.toBe("recovered");
  });
});
