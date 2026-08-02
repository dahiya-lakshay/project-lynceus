package com.lynceus.shared.exception;

/**
 * Thrown when a downstream dependency (inference service, database, Kafka, etc.) is unreachable or
 * degraded. Mapped to HTTP 503 by {@link GlobalExceptionHandler} per AGENTS.md's HTTP status code
 * table.
 */
public class ServiceUnavailableException extends RuntimeException {

  public ServiceUnavailableException(String message) {
    super(message);
  }

  public ServiceUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
