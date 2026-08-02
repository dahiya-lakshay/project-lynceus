package com.lynceus.shared.exception;

import com.lynceus.shared.dto.ErrorResponse;
import com.lynceus.shared.dto.ErrorResponse.ErrorDetail;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Central exception-to-HTTP-response mapping shared by every Lynceus service, so every error
 * response uses the same {@link ErrorResponse} envelope regardless of which controller raised it.
 * Status codes follow AGENTS.md's HTTP status code table exactly.
 *
 * <p>The catch-all handler never includes the exception message or stack trace in the response body
 * (AGENTS.md Security section: never leak internals to callers) — the full exception is logged
 * server-side at ERROR level for operators to diagnose, and the caller only sees a generic message
 * plus a trace ID they can hand to support.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(ResourceNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
    log.warn("Resource not found: {}", ex.getMessage());
    return errorResponse(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", ex.getMessage(), null);
  }

  @ExceptionHandler(ValidationException.class)
  public ResponseEntity<ErrorResponse> handleValidation(ValidationException ex) {
    log.warn("Validation failed: {}", ex.getMessage());
    return errorResponse(
        HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", ex.getMessage(), ex.getDetails());
  }

  @ExceptionHandler(ServiceUnavailableException.class)
  public ResponseEntity<ErrorResponse> handleServiceUnavailable(ServiceUnavailableException ex) {
    log.error("Downstream dependency unavailable: {}", ex.getMessage(), ex);
    return errorResponse(
        HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", ex.getMessage(), null);
  }

  // Bean Validation (@Valid) failures on @RequestBody DTOs — a 400 per AGENTS.md's status
  // table, distinct from the 422 ValidationException uses for semantic business-rule
  // failures Bean Validation annotations can't express.
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex) {
    Map<String, Object> fieldErrors = new LinkedHashMap<>();
    for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
      fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
    }
    log.warn("Request body validation failed: {}", fieldErrors);
    return errorResponse(
        HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Request validation failed", fieldErrors);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
    // Full exception (with stack trace) goes to the server-side log only — the response
    // body must never leak internals to the caller.
    log.error("Unhandled exception", ex);
    return errorResponse(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_SERVER_ERROR",
        "An unexpected error occurred. Please contact support with the trace ID.",
        null);
  }

  private ResponseEntity<ErrorResponse> errorResponse(
      HttpStatus status, String code, String message, Map<String, Object> details) {
    var detail =
        new ErrorDetail(code, message, details, UUID.randomUUID().toString(), Instant.now());
    return ResponseEntity.status(status).body(new ErrorResponse(detail));
  }
}
