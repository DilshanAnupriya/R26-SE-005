import { z } from "zod";
import { ApiError, toApiError, toApiErrorFromResponse } from "./errors";
import { StaleSessionError, type TokenStore } from "./token-store";

export interface ApiClientOptions {
  baseUrl: string;
  tokenStore: TokenStore;
  /** The organization the UI is currently scoped to; sent as `X-Organization-Id`. */
  getOrganizationId?: () => string | null | undefined;
}

export interface RequestOptions<TSchema extends z.ZodType | undefined> {
  method?: "GET" | "POST" | "PATCH" | "PUT" | "DELETE";
  body?: unknown;
  /** Validates the response at the boundary. Omit for endpoints that return no body. */
  schema?: TSchema;
  query?: Record<string, string | number | boolean | null | undefined>;
  signal?: AbortSignal;
  /** Skips the bearer token and the 401-refresh dance. For the BFF's own auth routes. */
  skipAuth?: boolean;
}

/**
 * The single path from this application to the Spring backend.
 *
 * <p>Responsibilities kept deliberately in one place: attach the bearer token and
 * tenant header, tag the request with a correlation id, parse the response through
 * its schema, normalise every failure into {@link ApiError}, and recover from an
 * expired access token exactly once per request.</p>
 *
 * <p>It talks to the backend directly rather than proxying through Next. Only
 * {@code /auth/*} goes through the BFF, because that is the only place the refresh
 * cookie is needed — routing board traffic through Next would add a hop per request
 * and buy nothing.</p>
 */
export class ApiClient {
  constructor(private readonly options: ApiClientOptions) {}

  async request<TSchema extends z.ZodType>(
    path: string,
    options: RequestOptions<TSchema> & { schema: TSchema },
  ): Promise<z.infer<TSchema>>;
  async request(path: string, options?: RequestOptions<undefined>): Promise<void>;
  async request<TSchema extends z.ZodType>(
    path: string,
    options: RequestOptions<TSchema | undefined> = {},
  ): Promise<unknown> {
    const response = await this.send(path, options, false);

    if (response.status === 204 || response.headers.get("content-length") === "0") {
      return undefined;
    }
    if (!options.schema) {
      return undefined;
    }

    let payload: unknown;
    try {
      payload = await response.json();
    } catch {
      throw new ApiError({
        status: response.status,
        code: "CONTRACT_ERROR",
        message: "The server returned a response this client could not read",
      });
    }

    const parsed = options.schema.safeParse(payload);
    if (!parsed.success) {
      // Loud and specific: a contract drift should name the field that moved rather
      // than surface as `undefined` inside whichever component renders it.
      throw new ApiError({
        status: response.status,
        code: "CONTRACT_ERROR",
        message: `Unexpected response shape from ${path}: ${z.prettifyError(parsed.error)}`,
      });
    }
    return parsed.data;
  }

  get<TSchema extends z.ZodType>(
    path: string,
    options: Omit<RequestOptions<TSchema>, "method" | "body"> & { schema: TSchema },
  ) {
    return this.request(path, { ...options, method: "GET" });
  }

  post<TSchema extends z.ZodType>(
    path: string,
    body: unknown,
    options: Omit<RequestOptions<TSchema>, "method" | "body"> & { schema: TSchema },
  ) {
    return this.request(path, { ...options, method: "POST", body });
  }

  patch<TSchema extends z.ZodType>(
    path: string,
    body: unknown,
    options: Omit<RequestOptions<TSchema>, "method" | "body"> & { schema: TSchema },
  ) {
    return this.request(path, { ...options, method: "PATCH", body });
  }

  put<TSchema extends z.ZodType>(
    path: string,
    body: unknown,
    options: Omit<RequestOptions<TSchema>, "method" | "body"> & { schema: TSchema },
  ) {
    return this.request(path, { ...options, method: "PUT", body });
  }

  /** DELETE endpoints return 204 with no body, so there is nothing to parse. */
  delete(path: string, options: Omit<RequestOptions<undefined>, "method" | "body"> = {}) {
    return this.request(path, { ...options, method: "DELETE" });
  }

  // ------------------------------------------------------------------ internals

  /**
   * Performs the request, recovering once from an expired access token.
   *
   * <p>Retry is capped at a single attempt. A refreshed token that still yields 401
   * means something is wrong that another round trip cannot fix, and looping would
   * hammer the refresh endpoint — which is precisely the traffic the backend's reuse
   * detection is watching for.</p>
   */
  private async send(
    path: string,
    options: RequestOptions<z.ZodType | undefined>,
    isRetry: boolean,
  ): Promise<Response> {
    let response: Response;
    try {
      response = await fetch(this.url(path, options.query), {
        method: options.method ?? "GET",
        headers: this.headers(options),
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
        signal: options.signal,
        // The backend is a stateless bearer-token API; sending cookies to it would
        // achieve nothing and widens what a CSRF attempt could reach.
        credentials: "omit",
      });
    } catch (cause) {
      throw toApiError(cause);
    }

    if (response.ok) {
      return response;
    }

    const error = await toApiErrorFromResponse(response);

    if (error.isExpiredToken && !options.skipAuth && !isRetry) {
      try {
        await this.options.tokenStore.refresh();
      } catch (refreshFailure) {
        // A stale refresh means the session was replaced or ended underneath us.
        // Surface the original 401 — "your session ended" is the truthful message,
        // not whatever the abandoned refresh happened to fail with.
        if (refreshFailure instanceof StaleSessionError) {
          throw error;
        }
        throw toApiError(refreshFailure);
      }
      return this.send(path, options, true);
    }

    throw error;
  }

  private headers(options: RequestOptions<z.ZodType | undefined>): HeadersInit {
    const headers: Record<string, string> = {
      Accept: "application/json",
      // Echoed back on the error response and written into the backend's structured
      // logs, so a user reporting a problem can be traced to the exact request.
      "X-Correlation-Id": crypto.randomUUID(),
    };

    if (options.body !== undefined) {
      headers["Content-Type"] = "application/json";
    }

    if (!options.skipAuth) {
      const token = this.options.tokenStore.get();
      if (token) {
        headers.Authorization = `Bearer ${token}`;
      }
      const organizationId = this.options.getOrganizationId?.();
      if (organizationId) {
        headers["X-Organization-Id"] = organizationId;
      }
    }

    return headers;
  }

  private url(path: string, query?: RequestOptions<z.ZodType>["query"]): string {
    const base = this.options.baseUrl.replace(/\/$/, "");
    const url = new URL(`${base}${path.startsWith("/") ? path : `/${path}`}`);

    for (const [key, value] of Object.entries(query ?? {})) {
      // Absent filters must not become the literal strings "undefined"/"null",
      // which the backend would try to parse as a UUID.
      if (value !== undefined && value !== null && value !== "") {
        url.searchParams.set(key, String(value));
      }
    }
    return url.toString();
  }
}
