package com.sprintiq.pms.common.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** Stable, client-facing error taxonomy. Never renumber or reuse a code. */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.UNPROCESSABLE_ENTITY, "Request validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid email or password"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Token has expired"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Token is invalid"),
    ACCOUNT_LOCKED(HttpStatus.LOCKED, "Account temporarily locked"),
    ACCOUNT_NOT_ACTIVE(HttpStatus.FORBIDDEN, "Account is not active"),
    MFA_REQUIRED(HttpStatus.UNAUTHORIZED, "Multi-factor authentication required"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "You do not have permission to perform this action"),
    TENANT_NOT_RESOLVED(HttpStatus.FORBIDDEN, "Organization context could not be resolved"),
    TENANT_MISMATCH(HttpStatus.FORBIDDEN, "Resource belongs to a different organization"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    CONFLICT(HttpStatus.CONFLICT, "Resource conflict"),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "Resource already exists"),
    OPTIMISTIC_LOCK(HttpStatus.CONFLICT, "Resource was modified by someone else"),
    ILLEGAL_STATE_TRANSITION(HttpStatus.CONFLICT, "Operation not allowed in the current state"),
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "Business rule violation"),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "Uploaded content is too large"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    AI_FEATURE_DISABLED(HttpStatus.FORBIDDEN, "This AI capability is disabled for your workspace"),
    AI_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI service is temporarily unavailable"),
    EXTERNAL_SERVICE_ERROR(HttpStatus.BAD_GATEWAY, "Upstream service error"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected internal error");

    private final HttpStatus status;
    private final String defaultMessage;
}
