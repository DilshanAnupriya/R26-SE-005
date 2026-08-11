import { exchangeForSession, readJsonBody } from "@/server/auth-routes";

/**
 * Registration returns a token pair too — the backend creates the user, provisions
 * the organization and signs them straight in, so this goes through the same
 * cookie-splitting path as login rather than being a plain passthrough.
 */
export async function POST(request: Request) {
  return exchangeForSession("/auth/register", await readJsonBody(request));
}
