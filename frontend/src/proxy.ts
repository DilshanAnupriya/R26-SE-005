import { NextResponse, type NextRequest } from "next/server";
import { REFRESH_COOKIE } from "@/server/cookie-names";

/** Where an unauthenticated visitor is sent, carrying where they were headed. */
const LOGIN_PATH = "/login";

/** Signed-in users have no business on these; they get bounced to the app. */
const AUTH_PAGES = ["/login", "/register", "/forgot-password", "/reset-password"];

/**
 * Cookie-presence gate for the signed-in area.
 *
 * <p>Named `proxy` and living in `src/proxy.ts`: Next 16 renamed the middleware
 * entry point, and the old name is deprecated.</p>
 *
 * <p>Deliberately shallow: this cannot validate the token without a network
 * call, and putting one on every navigation would add backend latency to each route
 * change. Presence is enough to stop the logged-out flash — rendering the shell,
 * discovering there is no session, and bouncing to /login. Real validation is the
 * first `/api/auth/session` call, which fails closed.</p>
 *
 * <p>So this is a UX guard, not a security boundary. The security boundary is the
 * backend, which authorises every request on its own.</p>
 */
export function proxy(request: NextRequest) {
  const { pathname, search } = request.nextUrl;
  const hasSession = request.cookies.has(REFRESH_COOKIE);

  if (AUTH_PAGES.includes(pathname)) {
    if (hasSession) {
      // Already signed in: send them into the app rather than showing a login form
      // they do not need. `/app` resolves the active org and redirects onward.
      return NextResponse.redirect(new URL("/app", request.url));
    }
    return NextResponse.next();
  }

  if (!hasSession) {
    const loginUrl = new URL(LOGIN_PATH, request.url);
    // Preserve the destination so signing in returns them where they were going,
    // rather than dumping everyone on the dashboard.
    loginUrl.searchParams.set("next", `${pathname}${search}`);
    return NextResponse.redirect(loginUrl);
  }

  return NextResponse.next();
}

export const config = {
  /*
   * Matches the signed-in area and the auth pages only. Marketing, static assets,
   * the BFF routes and Next internals are all excluded — the BFF in particular must
   * never be gated here, since /api/auth/session is precisely what a visitor with no
   * valid session needs to be able to call.
   */
  matcher: [
    "/app/:path*",
    "/login",
    "/register",
    "/forgot-password",
    "/reset-password",
  ],
};
