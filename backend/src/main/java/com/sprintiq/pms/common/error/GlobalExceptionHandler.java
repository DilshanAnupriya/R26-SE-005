package com.sprintiq.pms.common.error;

import com.sprintiq.pms.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * Translates every failure into the single {@link ApiError} contract.
 * Client errors are logged at WARN without stack traces; only genuine
 * server faults produce ERROR-level stack traces.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ApiError> handleApplication(ApplicationException ex, HttpServletRequest request) {
        ErrorCode code = ex.getErrorCode();
        if (code.getStatus().is5xxServerError()) {
            log.error("Application error [{}]: {}", code, ex.getMessage(), ex);
        } else {
            log.warn("Application error [{}]: {}", code, ex.getMessage());
        }
        return build(code, ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex,
                                                         HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldViolation(fe.getField(), fe.getDefaultMessage(), fe.getRejectedValue()))
                .toList();
        ApiError body = base(ErrorCode.VALIDATION_ERROR, "One or more fields are invalid", request)
                .withFieldErrors(violations);
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.getStatus()).body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex,
                                                              HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldViolation(v.getPropertyPath().toString(), v.getMessage(), v.getInvalidValue()))
                .toList();
        ApiError body = base(ErrorCode.VALIDATION_ERROR, "One or more parameters are invalid", request)
                .withFieldErrors(violations);
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.getStatus()).body(body);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleMalformed(Exception ex, HttpServletRequest request) {
        log.warn("Malformed request to {}: {}", request.getRequestURI(), ex.getMessage());
        return build(ErrorCode.MALFORMED_REQUEST, "Request could not be parsed", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return build(ErrorCode.UNAUTHENTICATED, ex.getMessage(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Access denied on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(ErrorCode.ACCESS_DENIED, null, request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockingFailureException ex,
                                                          HttpServletRequest request) {
        return build(ErrorCode.OPTIMISTIC_LOCK, "This record changed while you were editing it. Reload and retry.", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex,
                                                        HttpServletRequest request) {
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return build(ErrorCode.DUPLICATE_RESOURCE, "The operation violates a data constraint", request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleUploadTooLarge(MaxUploadSizeExceededException ex,
                                                         HttpServletRequest request) {
        return build(ErrorCode.PAYLOAD_TOO_LARGE, "File exceeds the maximum allowed size", request);
    }

    /**
     * A path nothing is mapped to.
     *
     * <p>{@link NoResourceFoundException} is the one Spring actually throws for an
     * unmapped path; {@link NoHandlerFoundException} only appears when the dispatcher is
     * configured to raise it. Without the former listed here an unknown URL fell to the
     * catch-all below and came back as <em>"An unexpected error occurred"</em> with a
     * correlation id — a 500 that reads like a crash for what is a typo, and which sends
     * whoever is debugging it hunting through logs for an exception that says only that
     * the route does not exist. It also hides the most ordinary cause of all: a running
     * server that predates the endpoint being called.</p>
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class,
            HttpRequestMethodNotSupportedException.class})
    public ResponseEntity<ApiError> handleNoHandler(Exception ex, HttpServletRequest request) {
        return build(ErrorCode.NOT_FOUND,
                "No handler for " + request.getMethod() + " " + request.getRequestURI(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred. Reference: "
                + MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY), request);
    }

    private ResponseEntity<ApiError> build(ErrorCode code, String message, HttpServletRequest request) {
        HttpStatus status = code.getStatus();
        return ResponseEntity.status(status).body(base(code, message, request));
    }

    private ApiError base(ErrorCode code, String message, HttpServletRequest request) {
        return ApiError.of(code, message, request.getRequestURI(),
                MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY));
    }
}
