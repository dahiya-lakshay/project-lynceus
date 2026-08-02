package com.lynceus.shared.exception;

import java.util.Map;

/**
 * Thrown when a request is syntactically valid but semantically invalid (e.g. a transaction amount
 * that fails a business rule not expressible via {@code jakarta.validation} annotations alone).
 * Mapped to HTTP 422 by {@link GlobalExceptionHandler} per AGENTS.md's HTTP status code table —
 * distinct from the 400 Bean Validation failures Spring raises automatically for malformed request
 * bodies.
 */
public class ValidationException extends RuntimeException {

  private final transient Map<String, Object> details;

  public ValidationException(String message) {
    super(message);
    this.details = Map.of();
  }

  public ValidationException(String message, Map<String, Object> details) {
    super(message);
    this.details = details == null ? Map.of() : Map.copyOf(details);
  }

  /** Structured context describing which fields/rules failed, for the error response body. */
  public Map<String, Object> getDetails() {
    return details;
  }
}
