package com.lynceus.shared.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Average ensemble fraud score for a single time bucket. Mirrors the {@code TimelineSeriesPoint}
 * schema in {@code api-specs/transaction-api.yaml}.
 *
 * <p>Produced (as a {@code List<TimelineSeriesPoint>}, ordered oldest to newest) by {@code
 * transaction-service}'s {@code GET /api/v1/transactions/stats/timeline} and consumed by {@code
 * dashboard-bff}'s {@code TransactionClient}.
 *
 * @param timestamp start of the time bucket (UTC)
 * @param averageScore mean ensemble fraud score for transactions scored within this bucket
 */
public record TimelineSeriesPoint(
    Instant timestamp, @JsonProperty("average_score") double averageScore) {}
