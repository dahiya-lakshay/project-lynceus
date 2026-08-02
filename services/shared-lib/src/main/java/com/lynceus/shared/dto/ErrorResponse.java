package com.lynceus.shared.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;

/**
 * Standard error envelope returned by every Lynceus service, matching the {@code ErrorResponse}
 * shape in {@code api-specs/shared/errors.yaml} and AGENTS.md's "Error Handling" section exactly,
 * so callers can rely on an identical error format regardless of which service (or language)
 * produced it.
 *
 * @param error the nested error payload
 */
public record ErrorResponse(ErrorDetail error) {

  /**
   * The nested {@code error} object inside {@link ErrorResponse}.
   *
   * @param code machine-readable error code, e.g. {@code TRANSACTION_NOT_FOUND}
   * @param message human-readable description of the error
   * @param details optional structured context; never includes stack traces
   * @param traceId identifier correlating this error to distributed trace/log records
   * @param timestamp time the error occurred
   */
  public record ErrorDetail(
      String code,
      String message,
      Map<String, Object> details,
      @JsonProperty("trace_id") String traceId,
      Instant timestamp) {}
}
