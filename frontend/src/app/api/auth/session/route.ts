import { exchangeForSession, noSession } from "@/server/auth-routes";
import { readRefreshToken } from "@/server/session";

/**
 * Exchanges the cookie for a fresh access token on app boot and after a hard
 * refresh. This is what lets the access token live in memory only: the browser
 * holds nothing across a reload, and this call re-establishes the session from a
 * cookie JavaScript cannot read.
 */
export async function POST() {
  const refreshToken = await readRefreshToken();
  if (!refreshToken) {
    return noSession();
  }
  return exchangeForSession("/auth/refresh", { refreshToken });
}
