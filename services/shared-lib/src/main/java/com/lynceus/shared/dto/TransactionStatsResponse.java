package com.lynceus.shared.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Aggregate KPIs computed server-side by the Transaction Service over a tenant's full transaction
 * set. Mirrors the {@code TransactionStats} schema in {@code api-specs/transaction-api.yaml}.
 *
 * <p>Produced by {@code transaction-service}'s {@code GET /api/v1/transactions/stats/overview} and
 * consumed by {@code dashboard-bff}'s {@code TransactionClient} (analogous to how {@link
 * ScoreTransactionResponse} is produced by the inference service and consumed by {@code
 * transaction-service}'s {@code InferenceClient}) — this DTO is the wire contract for that
 * service-to-service boundary, not just an internal query result.
 *
 * @param totalTransactions total number of transactions recorded for the tenant
 * @param averageScore mean ensemble fraud score across scored transactions; 0 when none are scored
 *     yet
 * @param flaggedCount number of scored transactions with risk_level high or critical
 * @param fraudRate flaggedCount divided by totalTransactions; 0 when the tenant has no transactions
 * @param totalAmountProcessed sum of transaction amounts processed for the tenant
 */
public record TransactionStatsResponse(
    @JsonProperty("total_transactions") long totalTransactions,
    @JsonProperty("average_score") double averageScore,
    @JsonProperty("flagged_count") long flaggedCount,
    @JsonProperty("fraud_rate") double fraudRate,
    @JsonProperty("total_amount_processed") BigDecimal totalAmountProcessed) {}
