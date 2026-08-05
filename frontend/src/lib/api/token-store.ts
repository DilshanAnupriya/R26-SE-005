type RefreshFn = () => Promise<string>;

interface TokenStoreConfig {
  refresh: RefreshFn;
  onSessionExpired?: () => void;
}

/**
 * Holds the access token in memory and serialises refreshes.
 *
 * In memory, not localStorage: an XSS that can read storage can steal a long-lived
 * credential and replay it anywhere. Memory limits the blast radius to one
 * 15-minute token, and the httpOnly refresh cookie is unreadable from JS entirely.
 *
 * Single-flight, because the backend rotates refresh tokens and treats a reused one
 * as theft by revoking the whole family. Concurrent refreshes would look exactly
 * like an attack and log the user out everywhere. So the first caller starts the
 * refresh and every other caller parks on the same promise.
 */
export class TokenStore {
  private accessToken: string | null = null;
  private inFlight: Promise<string> | null = null;
  private config: TokenStoreConfig | null = null;

  configure(config: TokenStoreConfig): void {
    this.config = config;
  }

  get(): string | null {
    return this.accessToken;
  }

  set(token: string): void {
    this.accessToken = token;
  }

  clear(): void {
    this.accessToken = null;
    this.inFlight = null;
  }

  /**
   * Returns a fresh access token. Concurrent callers share one network round trip.
   * Rejects for every waiter if the refresh fails, after clearing the session.
   */
  refresh(): Promise<string> {
    if (this.inFlight) {
      return this.inFlight;
    }
    if (!this.config) {
      return Promise.reject(new Error("TokenStore.configure() was never called"));
    }

    const attempt = this.config
      .refresh()
      .then((token) => {
        this.accessToken = token;
        return token;
      })
      .catch((error: unknown) => {
        this.accessToken = null;
        this.config?.onSessionExpired?.();
        throw error;
      })
      .finally(() => {
        // Released whether it resolved or rejected, so one failure never wedges
        // the store into a state where no refresh can ever be attempted again.
        this.inFlight = null;
      });

    this.inFlight = attempt;
    return attempt;
  }
}

/** Module-level instance: one token per browser tab. */
export const tokenStore = new TokenStore();
