import "server-only";
import { cookies } from "next/headers";
import type { NextResponse } from "next/server";

/**
 * Reading and writing the refresh cookie — the only place the long-lived credential
 * is handled.
 *
 * <p>Everything here is server-only. The `server-only` import turns a client import
 * into a build error rather than a runtime surprise; the entire security argument
 * for the BFF is that this token never reaches JavaScript.</p>
 *
 * <p>The cookie's name lives in {@code cookie-names.ts} instead, because middleware
 * needs it and runs in the Edge runtime where this module cannot load.</p>
 */
export { REFRESH_COOKIE } from "./cookie-names";
import { REFRESH_COOKIE } from "./cookie-names";

/** Matches the backend's 30-day refresh token lifetime. */
const THIRTY_DAYS_IN_SECONDS = 60 * 60 * 24 * 30;

function cookieOptions() {
  return {
    httpOnly: true,
    // Unreadable by JavaScript, so an XSS cannot exfiltrate it.
    secure: process.env.NODE_ENV === "production",
    // Lax, not Strict: Strict would drop the cookie when a user arrives from an
    // email verification or invitation link, logging them out at exactly the moment
    // the product asked them to click something. Lax still blocks cross-site POSTs,
    // which is the CSRF case that matters.
    sameSite: "lax" as const,
    path: "/",
    maxAge: THIRTY_DAYS_IN_SECONDS,
  };
}

/** Reads the refresh token from the incoming request, if the browser sent one. */
export async function readRefreshToken(): Promise<string | null> {
  const store = await cookies();
  return store.get(REFRESH_COOKIE)?.value ?? null;
}

/**
 * Writes the rotated refresh token onto an outgoing response.
 *
 * <p>Set on the response rather than through the `cookies()` mutator so it works in
 * route handlers, and so rotation is atomic with the response that reports it.</p>
 */
export function attachRefreshCookie(response: NextResponse, refreshToken: string): void {
  response.cookies.set(REFRESH_COOKIE, refreshToken, cookieOptions());
}

/**
 * Removes the cookie on logout.
 *
 * <p>Written as an immediate expiry with an empty value rather than a bare delete:
 * some browsers keep a cookie whose attributes do not match the one being deleted,
 * and a surviving refresh token after logout is the failure that matters here.</p>
 */
export function clearRefreshCookie(response: NextResponse): void {
  response.cookies.set(REFRESH_COOKIE, "", { ...cookieOptions(), maxAge: 0 });
}
