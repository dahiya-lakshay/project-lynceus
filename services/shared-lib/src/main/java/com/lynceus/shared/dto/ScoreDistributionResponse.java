package com.lynceus.shared.dto;

import java.util.List;

/**
 * Full histogram of ten 0.1-wide fraud score buckets spanning 0.0 to 1.0, computed over scored
 * transactions only. Mirrors the {@code ScoreDistributionResponse} schema in {@code
 * api-specs/transaction-api.yaml}.
 *
 * <p>Produced by {@code transaction-service}'s {@code GET
 * /api/v1/transactions/stats/score-distribution} and consumed by {@code dashboard-bff}'s {@code
 * TransactionClient}. No {@code @JsonProperty} mappings are needed here (unlike most other
 * shared-lib DTOs) — {@code buckets} is already the exact field name the API contract uses, and
 * {@link Bucket}'s {@code range}/{@code count} fields need no snake_case translation either.
 *
 * @param buckets the ten buckets, ordered 0.0-0.1 through 0.9-1.0
 */
public record ScoreDistributionResponse(List<Bucket> buckets) {

  /**
   * Count of scored transactions whose ensemble score falls within a fixed 0.1-wide range.
   *
   * @param range inclusive-lower, exclusive-upper score range, e.g. {@code "0.0-0.1"}
   * @param count number of scored transactions in this range
   */
  public record Bucket(String range, long count) {}
}
