import { exchangeForSession, noSession } from "@/server/auth-routes";
import { readRefreshToken } from "@/server/session";

/**
 * Rotation. The browser never sends a token here — it sends the cookie, and the
 * backend rotates it. The client's single-flight queue guarantees only one of these
 * is ever in flight per tab; concurrent calls would present the same rotated token
 * twice and trip the backend's reuse detection.
 */
export async function POST() {
  const refreshToken = await readRefreshToken();
  if (!refreshToken) {
    return noSession("Your session has expired. Please sign in again.");
  }
  return exchangeForSession("/auth/refresh", { refreshToken });
}
