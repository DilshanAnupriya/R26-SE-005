import { describe, expect, it } from "vitest";
import {
  ApiError,
  CONTRACT_ERROR_CODE,
  NETWORK_ERROR_CODE,
  toApiError,
  toApiErrorFromResponse,
} from "./errors";

function jsonResponse(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("toApiErrorFromResponse", () => {
  it("carries code, correlation id and field errors off the wire", async () => {
    const error = await toApiErrorFromResponse(
      jsonResponse(
        {
          timestamp: "2026-08-05T10:15:00Z",
          status: 422,
          code: "VALIDATION_ERROR",
          message: "Request validation failed",
          path: "/api/v1/auth/register",
          correlationId: "corr-123",
          fieldErrors: [
            { field: "password", message: "too short", rejectedValue: null },
            { field: "email", message: "already taken", rejectedValue: null },
          ],
        },
        422,
      ),
    );

    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe("VALIDATION_ERROR");
    expect(error.correlationId).toBe("corr-123");
    expect(error.hasFieldErrors).toBe(true);
    expect(error.fieldErrorMap()).toEqual({
      password: "too short",
      email: "already taken",
    });
  });

  it("keeps the first message when a field appears twice", () => {
    const error = new ApiError({
      status: 422,
      code: "VALIDATION_ERROR",
      message: "failed",
      fieldErrors: [
        { field: "password", message: "too short" },
        { field: "password", message: "needs a symbol" },
      ],
    });

    // Two messages under one input reads as a bug, so the first wins.
    expect(error.fieldErrorMap()).toEqual({ password: "too short" });
  });

  it("preserves the HTTP status when the body is not JSON at all", async () => {
    // A gateway timeout typically returns HTML, not our error envelope.
    const response = new Response("<html>502 Bad Gateway</html>", { status: 502 });
    const error = await toApiErrorFromResponse(response);

    expect(error.status).toBe(502);
    expect(error.code).toBe(CONTRACT_ERROR_CODE);
  });

  it("preserves the HTTP status when the JSON is not our error envelope", async () => {
    const error = await toApiErrorFromResponse(jsonResponse({ oops: true }, 500));

    expect(error.status).toBe(500);
    expect(error.code).toBe(CONTRACT_ERROR_CODE);
  });
});

describe("error classification", () => {
  it("recognises an expired access token, which is what triggers a refresh", async () => {
    const error = await toApiErrorFromResponse(
      jsonResponse({ status: 401, code: "TOKEN_EXPIRED", message: "Token has expired" }, 401),
    );
    expect(error.isExpiredToken).toBe(true);
  });

  it("treats a plain 401 as expired too, since the backend sends both codes", async () => {
    const error = await toApiErrorFromResponse(
      jsonResponse({ status: 401, code: "UNAUTHENTICATED", message: "Authentication required" }, 401),
    );
    expect(error.isExpiredToken).toBe(true);
  });

  it("does not treat a 403 as expired — refreshing would not help", async () => {
    const error = await toApiErrorFromResponse(
      jsonResponse({ status: 403, code: "ACCESS_DENIED", message: "No permission" }, 403),
    );
    expect(error.isExpiredToken).toBe(false);
    expect(error.isPermissionOrMissing).toBe(true);
  });

  it("flags an unresolved tenant so the shell can re-resolve the organization", async () => {
    const error = await toApiErrorFromResponse(
      jsonResponse({ status: 403, code: "TENANT_NOT_RESOLVED", message: "No organization" }, 403),
    );
    expect(error.isTenantUnresolved).toBe(true);
  });
});

describe("toApiError", () => {
  it("distinguishes an unreachable network from a server error", () => {
    const error = toApiError(new TypeError("Failed to fetch"));

    expect(error.code).toBe(NETWORK_ERROR_CODE);
    expect(error.status).toBe(0);
    expect(error.message).toMatch(/could not reach the server/i);
  });

  it("recognises an aborted request", () => {
    const error = toApiError(new DOMException("The operation was aborted.", "AbortError"));
    expect(error.code).toBe("ABORTED");
  });

  it("passes an ApiError straight through rather than re-wrapping it", () => {
    const original = new ApiError({ status: 404, code: "NOT_FOUND", message: "Gone" });
    expect(toApiError(original)).toBe(original);
  });

  it("survives a thrown non-Error value", () => {
    expect(toApiError("just a string").message).toBe("Something went wrong");
  });
});
