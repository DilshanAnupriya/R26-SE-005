import { exchangeForSession, noSession, readJsonBody } from "@/server/auth-routes";
import { readRefreshToken } from "@/server/session";

/**
 * Switching organizations re-mints the token pair with a new tenant claim, so it
 * needs the refresh token — which only the server can see. Hence a BFF route rather
 * than a direct call.
 */
export async function POST(request: Request) {
  const refreshToken = await readRefreshToken();
  if (!refreshToken) {
    return noSession();
  }
  const body = (await readJsonBody(request)) as { organizationId?: string };
  return exchangeForSession("/auth/switch-organization", {
    refreshToken,
    organizationId: body.organizationId,
  });
}
