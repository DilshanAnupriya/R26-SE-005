import "server-only";
import { NextResponse } from "next/server";
import { callBackend } from "./backend";
import { attachRefreshCookie, clearRefreshCookie } from "./session";
import { authTokensSchema } from "@/lib/api/schemas";

/**
 * Shared behaviour for every BFF auth route.
 *
 * <p>The one rule this file exists to enforce, in one place: when the backend
 * returns a token pair, the refresh token goes into the httpOnly cookie and is
 * stripped from the body. Repeating that per handler is how one route eventually
 * forgets, and a single leak hands a 30-day credential to any XSS on the page.</p>
 */

/** Relays the backend's own error body, so correlation ids and field errors survive. */
export function backendError(status: number, error: unknown): NextResponse {
  return NextResponse.json(error, { status });
}

/**
 * Calls a backend endpoint that returns `AuthTokens`, then splits the response:
 * refresh token into the cookie, everything else to the browser.
 */
export async function exchangeForSession(
  path: string,
  body: unknown,
): Promise<NextResponse> {
  const result = await callBackend<unknown>(path, { method: "POST", body });

  if (!result.ok) {
    const response = backendError(result.status, result.error);
    if (result.status === 401 || result.status === 403) {
      clearRefreshCookie(response);
    }
    return response;
  }

  const parsed = authTokensSchema.safeParse(result.data);
  if (!parsed.success) {
    // Better to fail the sign-in than to proceed with a session we cannot reason
    // about — a missing refreshToken here would silently produce a session that
    // cannot be renewed and logs the user out 15 minutes later.
    return NextResponse.json(
      {
        status: 502,
        code: "CONTRACT_ERROR",
        message: "The authentication service returned an unexpected response.",
      },
      { status: 502 },
    );
  }

  const { refreshToken, ...clientSession } = parsed.data;
  const response = NextResponse.json(clientSession, { status: 200 });
  attachRefreshCookie(response, refreshToken);
  return response;
}

/**
 * Ends the session: clears the cookie whatever the backend said.
 *
 * <p>204 is built with the bare `NextResponse` constructor rather than
 * `NextResponse.json`. A 204 must carry no body at all, and `.json()` always writes
 * one — which makes the `Response` constructor throw, turning logout into a 500 that
 * leaves the cookie in place. The user then bounces between /login and /app forever,
 * because the proxy still sees a session and the app still does not.</p>
 */
export function endSession(status: number, body?: unknown): NextResponse {
  const response =
    status === 204 || body === undefined || body === null
      ? new NextResponse(null, { status })
      : NextResponse.json(body, { status });

  clearRefreshCookie(response);
  return response;
}

/** The shape returned when there is no usable session. */
export function noSession(message = "No active session"): NextResponse {
  const response = NextResponse.json(
    { status: 401, code: "UNAUTHENTICATED", message },
    { status: 401 },
  );
  // Any cookie we hold is either absent or rejected by the backend; either way it
  // should not be presented again — a stale token retried on every request is what
  // reuse detection is looking for.
  clearRefreshCookie(response);
  return response;
}

/** Parses a JSON request body, tolerating an empty one. */
export async function readJsonBody(request: Request): Promise<unknown> {
  try {
    return await request.json();
  } catch {
    return {};
  }
}
