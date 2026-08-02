package com.lynceus.bff.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lynceus.shared.dto.RiskLevel;
import java.util.List;

/**
 * Count of transactions per risk level. The HTTP contract for {@code GET
 * /api/v1/dashboard/risk-breakdown} in {@code api-specs/dashboard-bff-api.yaml} is a bare JSON
 * array of {@code RiskBreakdownEntry}, not an object — {@code
 * com.lynceus.bff.controller.DashboardController} returns {@link #entries()} directly rather than
 * this wrapper. The wrapper exists purely so {@code com.lynceus.bff.config.RedisConfig} has a
 * single concrete type to bind a {@code Jackson2JsonRedisSerializer} to for this cache entry (a raw
 * {@code List<Entry>} would lose its element type to erasure at that binding), mirroring how {@code
 * TransactionRepository}/{@code FraudScoreRepository}'s response wrapper types are structured on
 * the transaction-service side.
 *
 * @param entries one entry per risk level with at least one transaction
 */
public record RiskBreakdownResponse(List<Entry> entries) {

  /**
   * Count of transactions at a single risk level.
   *
   * @param riskLevel the risk tier this count applies to
   * @param count number of transactions at this risk level
   */
  public record Entry(@JsonProperty("risk_level") RiskLevel riskLevel, long count) {}
}
