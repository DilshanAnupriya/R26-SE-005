/**
 * Cookie names, deliberately free of the `server-only` marker.
 *
 * <p>Middleware runs in the Edge runtime, where `next/headers` is unavailable, so it
 * cannot import {@code session.ts}. It does legitimately need the cookie's name to
 * check presence. The name is not a secret — the value is, and that stays behind
 * `server-only` in {@code session.ts}.</p>
 */
export const REFRESH_COOKIE = "sq_rt";
