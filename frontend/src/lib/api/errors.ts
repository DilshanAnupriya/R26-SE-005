import { apiErrorSchema, type FieldViolation } from "./schemas";

/**
 * Every failure the client can encounter, in one shape.
 *
 * <p>A caller should never have to ask "is this a Response, a TypeError from a dead
 * network, or a Zod issue?" before it can show a message. Everything funnels through
 * {@link toApiError}, so `catch` blocks handle exactly one type.</p>
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly correlationId?: string;
  readonly fieldErrors: FieldViolation[];
  readonly path?: string;

  constructor(init: {
    status: number;
    code: string;
    message: string;
    correlationId?: string | null;
    fieldErrors?: FieldViolation[] | null;
    path?: string | null;
  }) {
    super(init.message);
    this.name = "ApiError";
    this.status = init.status;
    this.code = init.code;
    this.correlationId = init.correlationId ?? undefined;
    this.fieldErrors = init.fieldErrors ?? [];
    this.path = init.path ?? undefined;
  }

  /** 401 with an expired access token — the signal the refresh queue reacts to. */
  get isExpiredToken(): boolean {
    return this.status === 401 && (this.code === "TOKEN_EXPIRED" || this.code === "UNAUTHENTICATED");
  }

  /** Renders in place as an empty state rather than as a toast: a state, not an event. */
  get isPermissionOrMissing(): boolean {
    return this.status === 403 || this.status === 404;
  }

  /** The active organization could not be resolved; the shell re-resolves rather than erroring. */
  get isTenantUnresolved(): boolean {
    return this.code === "TENANT_NOT_RESOLVED" || this.code === "TENANT_MISMATCH";
  }

  /** Field-level failures belong on the inputs that caused them, not in a toast. */
  get hasFieldErrors(): boolean {
    return this.fieldErrors.length > 0;
  }

  /**
   * `{ password: "must be at least 12 characters" }`, ready for react-hook-form's
   * `setError`. Duplicate fields keep the first message — showing two errors under
   * one input reads as a bug.
   */
  fieldErrorMap(): Record<string, string> {
    const map: Record<string, string> = {};
    for (const violation of this.fieldErrors) {
      if (!(violation.field in map)) {
        map[violation.field] = violation.message;
      }
    }
    return map;
  }
}

/** Network failure — the request never reached the backend. */
export const NETWORK_ERROR_CODE = "NETWORK_ERROR";
/** The backend answered, but not in a shape this client understands. */
export const CONTRACT_ERROR_CODE = "CONTRACT_ERROR";

/**
 * Normalises a failed `Response` into an {@link ApiError}.
 *
 * <p>Reads the body defensively: an error path is exactly where a gateway is most
 * likely to return HTML, an empty body, or a 502 from something that is not the
 * application at all. When the body is not a recognisable `ApiError`, the HTTP
 * status still carries real information, so it is preserved rather than collapsed
 * into a generic failure.</p>
 */
export async function toApiErrorFromResponse(response: Response): Promise<ApiError> {
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    return new ApiError({
      status: response.status,
      code: CONTRACT_ERROR_CODE,
      message: response.statusText || `Request failed with status ${response.status}`,
    });
  }

  const parsed = apiErrorSchema.safeParse(body);
  if (!parsed.success) {
    return new ApiError({
      status: response.status,
      code: CONTRACT_ERROR_CODE,
      message: response.statusText || `Request failed with status ${response.status}`,
    });
  }

  return new ApiError({
    status: parsed.data.status || response.status,
    code: parsed.data.code,
    message: parsed.data.message,
    correlationId: parsed.data.correlationId,
    fieldErrors: parsed.data.fieldErrors,
    path: parsed.data.path,
  });
}

/**
 * Last resort for a thrown value of unknown provenance. `fetch` rejects with a
 * `TypeError` when the network is unreachable, which is worth distinguishing from a
 * server error: one is worth retrying, the other is not.
 */
export function toApiError(cause: unknown): ApiError {
  if (cause instanceof ApiError) {
    return cause;
  }
  if (cause instanceof DOMException && cause.name === "AbortError") {
    return new ApiError({ status: 0, code: "ABORTED", message: "Request cancelled" });
  }
  if (cause instanceof TypeError) {
    return new ApiError({
      status: 0,
      code: NETWORK_ERROR_CODE,
      message: "Could not reach the server. Check your connection and try again.",
    });
  }
  return new ApiError({
    status: 0,
    code: "INTERNAL_ERROR",
    message: cause instanceof Error ? cause.message : "Something went wrong",
  });
}
