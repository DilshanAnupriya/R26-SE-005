package com.sprintiq.pms.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** The single error shape returned by every endpoint. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ApiError", description = "Standard error response")
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String correlationId,
        List<FieldViolation> fieldErrors) {

    @Schema(name = "FieldViolation")
    public record FieldViolation(String field, String message, Object rejectedValue) {}

    public static ApiError of(ErrorCode code, String message, String path, String correlationId) {
        return new ApiError(Instant.now(), code.getStatus().value(), code.name(),
                message != null ? message : code.getDefaultMessage(), path, correlationId, null);
    }

    public ApiError withFieldErrors(List<FieldViolation> violations) {
        return new ApiError(timestamp, status, code, message, path, correlationId, violations);
    }
}
