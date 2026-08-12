import { callBackend } from "@/server/backend";
import { endSession } from "@/server/auth-routes";
import { readRefreshToken } from "@/server/session";

/**
 * Revokes server-side, then clears the cookie.
 *
 * <p>The cookie is cleared even when the backend call fails. A user who clicked
 * "log out" must end up logged out of this browser regardless — leaving a valid
 * refresh cookie behind because the network blipped is the worse outcome, especially
 * on a shared machine.</p>
 */
export async function POST() {
  const refreshToken = await readRefreshToken();
  if (refreshToken) {
    await callBackend("/auth/logout", { method: "POST", body: { refreshToken } });
  }
  return endSession(204, null);
}
