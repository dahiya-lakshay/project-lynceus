package com.lynceus.transaction.model.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Time-bucket width for {@code GET /api/v1/transactions/stats/timeline}'s {@code interval} query
 * parameter, matching the closed enum declared on that parameter in {@code
 * api-specs/transaction-api.yaml}.
 *
 * <p>Transaction-service-local (unlike {@link com.lynceus.shared.dto.RiskLevel}/{@link
 * com.lynceus.shared.dto.MerchantCategory}, which live in shared-lib because they're embedded in
 * DTOs that cross a service boundary): {@code interval} is only ever a query parameter on this one
 * endpoint, never part of a response body another service needs to deserialize, so there's no
 * cross-module reuse to justify shared-lib placement.
 *
 * <p>A closed Java enum (rather than a plain {@code String}) rejects an invalid interval with a 400
 * — via the same {@code MethodArgumentTypeMismatchException} handling {@code
 * TransactionExceptionHandler} already applies to {@code risk_level}/{@code merchant_category} —
 * instead of passing an arbitrary string into {@code date_trunc(...)} and surfacing a raw Postgres
 * error as a 500.
 *
 * <p>{@link #wireValue()}/{@link #fromWireValue(String)} pin JSON/query-param (de)serialization to
 * the spec's exact lowercase strings ({@code hour}, {@code day}) instead of Jackson's default enum
 * handling, which would serialize the constant name verbatim (e.g. {@code DAY}).
 */
public enum TimeInterval {
  HOUR("hour"),
  DAY("day");

  private final String wireValue;

  TimeInterval(String wireValue) {
    this.wireValue = wireValue;
  }

  @JsonValue
  public String wireValue() {
    return wireValue;
  }

  @JsonCreator
  public static TimeInterval fromWireValue(String value) {
    for (TimeInterval interval : values()) {
      if (interval.wireValue.equals(value)) {
        return interval;
      }
    }
    throw new IllegalArgumentException("Unknown interval: " + value);
  }
}
