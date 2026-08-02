package com.lynceus.bff.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Top-line KPIs shown on the dashboard landing view. Mirrors the {@code DashboardOverview} schema
 * in {@code api-specs/dashboard-bff-api.yaml} exactly.
 *
 * <p>Deliberately its own class rather than reusing {@code
 * com.lynceus.shared.dto.TransactionStatsResponse} directly, even though the two shapes coincide
 * today: this is the BFF's own outward-facing contract (a distinct bounded context from
 * transaction-service's), and {@link com.lynceus.bff.service.DashboardService} is where that shape
 * gets decided independently of whatever transaction-service happens to return.
 *
 * @param totalTransactions total number of transactions recorded for the tenant
 * @param fraudRate fraction of transactions classified as high or critical risk
 * @param averageScore mean ensemble fraud score across scored transactions
 * @param flaggedCount number of transactions flagged as high or critical risk
 * @param totalAmountProcessed sum of transaction amounts processed for the tenant
 */
public record OverviewResponse(
    @JsonProperty("total_transactions") long totalTransactions,
    @JsonProperty("fraud_rate") double fraudRate,
    @JsonProperty("average_score") double averageScore,
    @JsonProperty("flagged_count") long flaggedCount,
    @JsonProperty("total_amount_processed") BigDecimal totalAmountProcessed) {}
