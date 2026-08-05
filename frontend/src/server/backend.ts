import "server-only";
import { serverEnv } from "@/lib/env";

export interface BackendResponse<T> {
  ok: boolean;
  status: number;
  data: T | null;
  /** The backend's `ApiError` body when the call failed, passed through untouched. */
  error: unknown;
}

/**
 * Server-side calls from route handlers to the Spring backend.
 *
 * <p>Separate from the browser's `ApiClient` because the constraints differ: this
 * runs on the server, reaches the backend over the container network, carries no
 * bearer token of its own, and must relay the backend's error body verbatim so the
 * browser still receives the real `ApiError` — including its correlation id, which is
 * what ties a user's report to the backend's logs.</p>
 */
export async function callBackend<T>(
  path: string,
  init: { method: "GET" | "POST"; body?: unknown; accessToken?: string } = { method: "GET" },
): Promise<BackendResponse<T>> {
  const { backendUrl } = serverEnv();

  const headers: Record<string, string> = { Accept: "application/json" };
  if (init.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  if (init.accessToken) {
    headers.Authorization = `Bearer ${init.accessToken}`;
  }

  let response: Response;
  try {
    response = await fetch(`${backendUrl.replace(/\/$/, "")}${path}`, {
      method: init.method,
      headers,
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
      // Auth responses are per-user and must never be cached by Next's data cache.
      cache: "no-store",
    });
  } catch {
    return {
      ok: false,
      status: 503,
      data: null,
      error: {
        status: 503,
        code: "EXTERNAL_SERVICE_ERROR",
        message: "The SprintIQ API is unreachable. Please try again shortly.",
      },
    };
  }

  const text = await response.text();
  const payload: unknown = text ? safeJsonParse(text) : null;

  return response.ok
    ? { ok: true, status: response.status, data: payload as T, error: null }
    : {
        ok: false,
        status: response.status,
        data: null,
        error: payload ?? {
          status: response.status,
          code: "INTERNAL_ERROR",
          message: response.statusText || "Request failed",
        },
      };
}

function safeJsonParse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    // A gateway returning HTML on an error path should not crash the route handler.
    return null;
  }
}
