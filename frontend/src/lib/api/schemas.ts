import { z } from "zod";

/**
 * Zod mirrors of the backend's wire contract.
 *
 * <p>Parsing at the boundary means a backend contract change surfaces as one loud
 * error naming the field that moved, rather than as `undefined` three components
 * deep in a component that renders it.</p>
 *
 * <p>These mirror `common/error/ApiError.java`, `common/web/PageResponse.java` and
 * `modules/auth/web/dto/AuthDtos.java`. When one of those changes, this changes.</p>
 */

/** Mirrors `ErrorCode.java`. Kept as a union so `switch` over it is exhaustive. */
export const errorCodeSchema = z.enum([
  "VALIDATION_ERROR",
  "MALFORMED_REQUEST",
  "UNAUTHENTICATED",
  "INVALID_CREDENTIALS",
  "TOKEN_EXPIRED",
  "TOKEN_INVALID",
  "ACCOUNT_LOCKED",
  "ACCOUNT_NOT_ACTIVE",
  "MFA_REQUIRED",
  "ACCESS_DENIED",
  "TENANT_NOT_RESOLVED",
  "TENANT_MISMATCH",
  "NOT_FOUND",
  "CONFLICT",
  "DUPLICATE_RESOURCE",
  "OPTIMISTIC_LOCK",
  "ILLEGAL_STATE_TRANSITION",
  "BUSINESS_RULE_VIOLATION",
  "PAYLOAD_TOO_LARGE",
  "UNSUPPORTED_MEDIA_TYPE",
  "RATE_LIMITED",
  "AI_FEATURE_DISABLED",
  "AI_SERVICE_UNAVAILABLE",
  "EXTERNAL_SERVICE_ERROR",
  "INTERNAL_ERROR",
]);
export type ErrorCode = z.infer<typeof errorCodeSchema>;

export const fieldViolationSchema = z.object({
  field: z.string(),
  message: z.string(),
  rejectedValue: z.unknown().nullish(),
});
export type FieldViolation = z.infer<typeof fieldViolationSchema>;

/**
 * `ApiError` is annotated `@JsonInclude(NON_NULL)`, so absent fields are omitted
 * entirely rather than sent as null — hence the nullish optionals. `code` is
 * deliberately loose (`string`) rather than the enum: an unrecognised code from a
 * newer backend should still render its message, not fail to parse.
 */
export const apiErrorSchema = z.object({
  timestamp: z.string().nullish(),
  status: z.number().int(),
  code: z.string(),
  message: z.string(),
  path: z.string().nullish(),
  correlationId: z.string().nullish(),
  fieldErrors: z.array(fieldViolationSchema).nullish(),
});
export type ApiErrorBody = z.infer<typeof apiErrorSchema>;

/** Mirrors `PageResponse<T>`. Generic over the item schema. */
export function pageResponseSchema<T extends z.ZodType>(item: T) {
  return z.object({
    content: z.array(item),
    page: z.number().int(),
    size: z.number().int(),
    totalElements: z.number().int(),
    totalPages: z.number().int(),
    hasNext: z.boolean(),
    hasPrevious: z.boolean(),
  });
}

export type PageResponse<T> = {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;
};

// ---------------------------------------------------------------------------- auth

export const organizationSummarySchema = z.object({
  id: z.uuid(),
  name: z.string(),
  slug: z.string(),
  logoUrl: z.string().nullish(),
  roleCode: z.string().nullish(),
});
export type OrganizationSummary = z.infer<typeof organizationSummarySchema>;

export const authenticatedUserSchema = z.object({
  id: z.uuid(),
  email: z.email(),
  fullName: z.string(),
  avatarUrl: z.string().nullish(),
  timezone: z.string().nullish(),
  locale: z.string().nullish(),
  emailVerified: z.boolean(),
  mfaEnabled: z.boolean(),
  lastLoginAt: z.string().nullish(),
  activeOrganizationId: z.uuid().nullish(),
  activeOrganizationName: z.string().nullish(),
  activeRole: z.string().nullish(),
  // Jackson serialises Set<String> as an array; the permission set drives every
  // client-side capability check.
  permissions: z.array(z.string()).default([]),
  organizations: z.array(organizationSummarySchema).default([]),
});
export type AuthenticatedUser = z.infer<typeof authenticatedUserSchema>;

/** Mirrors `AuthDtos.AuthTokens`. */
export const authTokensSchema = z.object({
  accessToken: z.string().min(1),
  refreshToken: z.string().min(1),
  tokenType: z.string(),
  expiresIn: z.number().int(),
  user: authenticatedUserSchema,
});
export type AuthTokens = z.infer<typeof authTokensSchema>;

/**
 * What the BFF returns to the browser: the same payload minus `refreshToken`,
 * which stays in the httpOnly cookie and must never reach JavaScript.
 */
export const clientSessionSchema = authTokensSchema.omit({ refreshToken: true });
export type ClientSession = z.infer<typeof clientSessionSchema>;
