import { z } from "zod";

/**
 * Validated environment access.
 *
 * <p>Reading `process.env.X` inline means a missing variable surfaces as a fetch to
 * `undefined/auth/login` at the moment a user tries to sign in. Parsing here fails at
 * module load instead, with the name of the variable that is wrong.</p>
 *
 * Next inlines `NEXT_PUBLIC_*` at build time only for statically analysable member
 * expressions, so each one is written out in full rather than looked up dynamically.
 */
const publicSchema = z.object({
  NEXT_PUBLIC_API_URL: z.url(),
  NEXT_PUBLIC_WS_URL: z.string().min(1),
});

const parsedPublic = publicSchema.safeParse({
  NEXT_PUBLIC_API_URL: process.env.NEXT_PUBLIC_API_URL,
  NEXT_PUBLIC_WS_URL: process.env.NEXT_PUBLIC_WS_URL,
});

if (!parsedPublic.success) {
  throw new Error(
    `Invalid public environment configuration:\n${z.prettifyError(parsedPublic.error)}\n` +
      `Copy frontend/.env.example to frontend/.env.local.`,
  );
}

/**
 * Points a `localhost` backend URL at whatever host the page was actually opened on.
 *
 * <p>The dev `.env` says `localhost:8080`, which is only right on the machine running
 * the stack. Open the app from a phone or another laptop at `192.168.x.x:3000` and
 * `localhost` is that device, so every API call after sign-in fails. Only a loopback
 * host is rewritten; a real configured hostname is left exactly as written.</p>
 */
function onPageHost(url: string): string {
  if (typeof window === "undefined") return url;
  const parsed = new URL(url);
  const loopback = ["localhost", "127.0.0.1", "[::1]"];
  if (!loopback.includes(parsed.hostname) || loopback.includes(window.location.hostname)) {
    return url;
  }
  parsed.hostname = window.location.hostname;
  // URL.toString() appends a trailing slash to a bare origin; keep the input's shape.
  return url.endsWith("/") ? parsed.toString() : parsed.toString().replace(/\/$/, "");
}

export const env = {
  NEXT_PUBLIC_API_URL: onPageHost(parsedPublic.data.NEXT_PUBLIC_API_URL),
  NEXT_PUBLIC_WS_URL: onPageHost(parsedPublic.data.NEXT_PUBLIC_WS_URL),
};

/**
 * Server-only configuration. Route handlers reach the backend over the container
 * network, where the hostname differs from the one the browser uses — in Docker the
 * browser sees `localhost:8080` and the Next container sees `backend:8080`.
 *
 * Throws if called from the browser: importing this into a client component would
 * otherwise leak an internal hostname into the bundle.
 */
export function serverEnv() {
  if (typeof window !== "undefined") {
    throw new Error("serverEnv() must not be called in the browser");
  }
  return {
    backendUrl: process.env.BACKEND_INTERNAL_URL ?? env.NEXT_PUBLIC_API_URL,
    isProduction: process.env.NODE_ENV === "production",
  };
}
