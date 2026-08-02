package com.lynceus.shared.exception;

/**
 * Thrown when a requested resource (transaction, customer, fraud score, etc.) does not exist for
 * the caller's tenant. Mapped to HTTP 404 by {@link GlobalExceptionHandler} per AGENTS.md's HTTP
 * status code table.
 */
public class ResourceNotFoundException extends RuntimeException {

  public ResourceNotFoundException(String message) {
    super(message);
  }

  public ResourceNotFoundException(String message, Throwable cause) {
    super(message, cause);
  }
}
