package com.lynceus.bff.model.dto;

import java.util.List;

/**
 * Full histogram of ten 0.1-wide fraud score buckets spanning 0.0 to 1.0. Mirrors the {@code
 * FraudDistributionResponse} schema in {@code api-specs/dashboard-bff-api.yaml} exactly.
 *
 * @param buckets the ten buckets, ordered 0.0-0.1 through 0.9-1.0
 */
public record FraudDistributionResponse(List<Bucket> buckets) {

  /**
   * Count of transactions whose ensemble score falls within a fixed 0.1-wide range.
   *
   * @param range inclusive-lower, exclusive-upper score range, e.g. {@code "0.0-0.1"}
   * @param count number of transactions in this range
   */
  public record Bucket(String range, long count) {}
}
