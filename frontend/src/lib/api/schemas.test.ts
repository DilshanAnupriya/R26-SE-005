import { describe, expect, it } from "vitest";
import {
  apiErrorSchema,
  authTokensSchema,
  clientSessionSchema,
  pageResponseSchema,
} from "./schemas";
import { z } from "zod";

/**
 * These assert against payloads shaped exactly as the Spring backend emits them.
 * If the backend contract moves, these fail rather than a component silently
 * rendering undefined.
 */
describe("apiErrorSchema", () => {
  it("parses a validation error with field violations", () => {
    const wire = {
      timestamp: "2026-08-05T10:15:00Z",
      status: 422,
      code: "VALIDATION_ERROR",
      message: "Request validation failed",
      path: "/api/v1/auth/register",
      correlationId: "3f7c2a11-0b1e-4a2c-9f3e-9c1d2b4a5e6f",
      fieldErrors: [
        {
          field: "password",
          message: "Password must be at least 12 characters",
          rejectedValue: null,
        },
      ],
    };

    const parsed = apiErrorSchema.parse(wire);
    expect(parsed.fieldErrors).toHaveLength(1);
    expect(parsed.fieldErrors?.[0].field).toBe("password");
  });

  it("parses an error whose optional fields Jackson omitted entirely", () => {
    // ApiError is @JsonInclude(NON_NULL): absent fields are missing, not null.
    const parsed = apiErrorSchema.parse({
      status: 404,
      code: "NOT_FOUND",
      message: "Project not found: 8c1f",
    });

    expect(parsed.fieldErrors).toBeUndefined();
    expect(parsed.correlationId).toBeUndefined();
  });

  it("accepts an unrecognised code rather than failing to parse", () => {
    // A newer backend may add codes. Rendering its message beats showing nothing.
    const parsed = apiErrorSchema.parse({
      status: 418,
      code: "SOME_FUTURE_CODE",
      message: "Unknown to this client",
    });
    expect(parsed.code).toBe("SOME_FUTURE_CODE");
  });

  it("rejects a body missing the required status and code", () => {
    expect(() => apiErrorSchema.parse({ message: "nope" })).toThrow();
  });
});

describe("pageResponseSchema", () => {
  it("parses the standard page envelope", () => {
    const schema = pageResponseSchema(z.object({ id: z.uuid(), name: z.string() }));
    const parsed = schema.parse({
      content: [{ id: "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed", name: "Mobile Banking" }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      hasNext: false,
      hasPrevious: false,
    });

    expect(parsed.content).toHaveLength(1);
    expect(parsed.hasNext).toBe(false);
  });

  it("rejects a page whose items do not match the item schema", () => {
    const schema = pageResponseSchema(z.object({ id: z.uuid() }));
    expect(() =>
      schema.parse({
        content: [{ id: "not-a-uuid" }],
        page: 0,
        size: 20,
        totalElements: 1,
        totalPages: 1,
        hasNext: false,
        hasPrevious: false,
      }),
    ).toThrow();
  });
});

describe("auth schemas", () => {
  const tokens = {
    accessToken: "header.payload.signature",
    refreshToken: "opaque-refresh-token",
    tokenType: "Bearer",
    expiresIn: 900,
    user: {
      id: "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
      email: "dev@example.com",
      fullName: "Dev User",
      avatarUrl: null,
      timezone: "UTC",
      locale: "en",
      emailVerified: true,
      mfaEnabled: false,
      lastLoginAt: "2026-08-05T10:00:00Z",
      activeOrganizationId: "2c9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
      activeOrganizationName: "Acme Robotics",
      activeRole: "OWNER",
      permissions: ["project:read", "project:create"],
      organizations: [
        {
          id: "2c9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
          name: "Acme Robotics",
          slug: "acme-robotics",
          logoUrl: null,
          roleCode: "OWNER",
        },
      ],
    },
  };

  it("parses the token pair the backend returns on login", () => {
    const parsed = authTokensSchema.parse(tokens);
    expect(parsed.user.permissions).toContain("project:create");
    expect(parsed.user.organizations[0].slug).toBe("acme-robotics");
  });

  it("defaults permissions and organizations when the backend omits them", () => {
    const { permissions, organizations, ...user } = tokens.user;
    void permissions;
    void organizations;

    const parsed = authTokensSchema.parse({ ...tokens, user });
    expect(parsed.user.permissions).toEqual([]);
    expect(parsed.user.organizations).toEqual([]);
  });

  it("strips the refresh token from what reaches the browser", () => {
    const session = clientSessionSchema.parse(tokens);
    // The refresh token lives in an httpOnly cookie. If it ever appears here, an
    // XSS can read a 30-day credential — so this is a security assertion, not a
    // shape assertion.
    expect(session).not.toHaveProperty("refreshToken");
    expect(session.accessToken).toBe(tokens.accessToken);
  });
});
