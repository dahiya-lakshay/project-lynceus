package com.lynceus.shared.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Count of scored transactions at a single risk level. Mirrors the {@code RiskLevelCount} schema in
 * {@code api-specs/transaction-api.yaml}.
 *
 * <p>Produced (as a {@code List<RiskLevelCount>}, one entry per risk level with at least one scored
 * transaction) by {@code transaction-service}'s {@code GET
 * /api/v1/transactions/stats/risk-breakdown} and consumed by {@code dashboard-bff}'s {@code
 * TransactionClient}.
 *
 * @param riskLevel the risk tier this count applies to
 * @param count number of scored transactions at this risk level
 */
public record RiskLevelCount(@JsonProperty("risk_level") RiskLevel riskLevel, long count) {}
