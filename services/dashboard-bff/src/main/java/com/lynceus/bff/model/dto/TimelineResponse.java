package com.lynceus.bff.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * Time series of average ensemble fraud score. The HTTP contract for {@code GET
 * /api/v1/dashboard/timeline} in {@code api-specs/dashboard-bff-api.yaml} is a bare JSON array of
 * {@code TimelinePoint}, not an object — {@code com.lynceus.bff.controller.DashboardController}
 * returns {@link #points()} directly rather than this wrapper. Not cached (see {@link
 * com.lynceus.bff.service.DashboardService}'s class Javadoc for which endpoints are cached and why
 * timeline isn't one of them), but kept as a wrapper record anyway for symmetry with {@link
 * FraudDistributionResponse}/{@link RiskBreakdownResponse} and in case caching is added later.
 *
 * @param points the time series, ordered oldest to newest
 */
public record TimelineResponse(List<Point> points) {

  /**
   * Average ensemble fraud score for a single time bucket.
   *
   * @param timestamp start of the time bucket (UTC)
   * @param averageScore mean ensemble fraud score for transactions scored within this bucket
   */
  public record Point(Instant timestamp, @JsonProperty("average_score") double averageScore) {}
}
