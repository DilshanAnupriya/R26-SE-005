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

export const env = parsedPublic.data;

/**
 * The backend's origin (scheme, host and port), derived from `NEXT_PUBLIC_API_URL`.
 *
 * For the calls written with a full path — `/api/v1/...`, and the resource allocation
 * module's `/api/...` — rather than through the ApiClient. Hard-coding
 * `http://localhost:8080` there worked on a laptop and nowhere else: on the hosted
 * site the browser fetched its own machine and showed "Failed to fetch".
 */
export const backendOrigin = new URL(env.NEXT_PUBLIC_API_URL).origin;

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
