export type RefreshFn = () => Promise<string>;

export interface TokenStoreConfig {
  refresh: RefreshFn;
  onSessionExpired?: () => void;
}

/**
 * Thrown to waiters parked on a refresh that belonged to a session which has since
 * ended — a logout, or a fresh login that replaced the token underneath it.
 *
 * Distinct from a network or auth failure: nothing went wrong, the answer simply
 * arrived for a session nobody is in any more, and applying it would resurrect
 * credentials the user just discarded.
 */
export class StaleSessionError extends Error {
  constructor() {
    super("The session changed while the token refresh was in flight");
    this.name = "StaleSessionError";
  }
}

/**
 * Holds the access token in memory and serialises refreshes.
 *
 * <p><b>In memory, not localStorage.</b> An XSS that can read storage can steal a
 * long-lived credential and replay it from anywhere. Memory limits the blast radius
 * to a single 15-minute token, and the refresh token itself lives in an httpOnly
 * cookie that JavaScript cannot read at all.</p>
 *
 * <p><b>Single-flight, because the backend treats a reused refresh token as theft.</b>
 * It rotates the token on every use and revokes the whole family on reuse. Six
 * parallel queries 401-ing at once — exactly what a board mounting looks like —
 * would otherwise fire six refreshes, present the rotated token six times, and get
 * the user signed out of every device they own. The first caller starts the refresh;
 * every other caller parks on the same promise.</p>
 *
 * <p><b>Generation tracking.</b> A promise cannot be cancelled, so a refresh started
 * before a logout is still running after it. Every attempt captures the generation it
 * began in; {@link clear} and {@link setAccessToken} bump that counter. When a stale
 * attempt settles, its result is discarded rather than written — otherwise a refresh
 * in flight at logout would repopulate a valid token seconds after the user left,
 * which on a shared machine is somebody else's session.</p>
 *
 * <p>Note the deliberate trade: {@link clear} does <i>not</i> release the in-flight
 * latch. A refresh issued immediately after a logout parks on the doomed attempt and
 * fails once, rather than opening a second concurrent call. A single spurious failure
 * retries cleanly; tripping reuse detection does not.</p>
 */
export class TokenStore {
  #accessToken: string | null = null;
  #inFlight: Promise<string> | null = null;
  #config: TokenStoreConfig | null = null;
  #generation = 0;
  /** Latches expiry so N failed attempts produce one redirect, not N. */
  #expiryNotified = false;

  configure(config: TokenStoreConfig): void {
    this.#config = config;
  }

  get(): string | null {
    return this.#accessToken;
  }

  hasToken(): boolean {
    return this.#accessToken !== null;
  }

  isRefreshing(): boolean {
    return this.#inFlight !== null;
  }

  /**
   * Stores a token obtained outside the refresh path — a login, or the boot-time
   * session exchange. Bumps the generation so an older refresh still in flight can
   * neither overwrite this newer token nor report the session as expired.
   */
  setAccessToken(token: string): void {
    this.#accessToken = token;
    this.#generation += 1;
    this.#expiryNotified = false;
  }

  /**
   * Abandons the session. Bumps the generation so any refresh still in flight is
   * discarded when it lands. The in-flight latch is intentionally left in place —
   * see the class note on why a second concurrent refresh is the worse outcome.
   */
  clear(): void {
    this.#accessToken = null;
    this.#generation += 1;
    this.#expiryNotified = false;
  }

  /**
   * Returns a fresh access token. Concurrent callers share one network round trip.
   * If the refresh fails, every waiter rejects and the session is reported expired
   * once.
   */
  refresh(): Promise<string> {
    if (this.#inFlight) {
      return this.#inFlight;
    }
    const config = this.#config;
    if (!config) {
      return Promise.reject(new Error("TokenStore.configure() was never called"));
    }

    // Captured once, so a single attempt is served entirely by the config that
    // started it. Reading onSessionExpired at rejection time would hand the failure
    // to a different callback if configure() ran in between — which React StrictMode
    // and Fast Refresh both make routine in development.
    const { refresh: runRefresh, onSessionExpired } = config;
    const generation = this.#generation;
    const isCurrent = () => generation === this.#generation;

    const attempt: Promise<string> = runRefresh()
      .then((token) => {
        if (!isCurrent()) {
          throw new StaleSessionError();
        }
        this.#accessToken = token;
        this.#expiryNotified = false;
        return token;
      })
      .catch((error: unknown) => {
        // A stale attempt must not clear a newer token or announce an expiry: the
        // user may have just logged back in, and bouncing them to /login for a
        // failure that belonged to the previous session is unrecoverable by retrying.
        if (!isCurrent()) {
          throw error instanceof StaleSessionError ? error : new StaleSessionError();
        }
        this.#accessToken = null;
        if (!this.#expiryNotified) {
          this.#expiryNotified = true;
          onSessionExpired?.();
        }
        throw error;
      })
      .finally(() => {
        // Identity-guarded: only the attempt that installed the latch may release it.
        // An unconditional release lets a stale attempt settling later reopen the gate
        // for a newer one that is still running, which is how a two-call breach becomes
        // an unbounded one.
        if (this.#inFlight === attempt) {
          this.#inFlight = null;
        }
      });

    this.#inFlight = attempt;
    return attempt;
  }
}

let instance: TokenStore | null = null;

/**
 * The per-tab token store.
 *
 * <p>Lazily constructed and guarded against server use. A module-level instance
 * imported — even transitively — by a Server Component or Route Handler would be one
 * instance per server process, shared by every concurrent request: one user's access
 * token handed to another. Failing loudly on the server beats that silently.</p>
 */
export function getTokenStore(): TokenStore {
  if (typeof window === "undefined") {
    throw new Error(
      "getTokenStore() is browser-only. Server code must not share a token store across requests — " +
        "route handlers read the refresh cookie via src/server/session.ts instead.",
    );
  }
  instance ??= new TokenStore();
  return instance;
}
