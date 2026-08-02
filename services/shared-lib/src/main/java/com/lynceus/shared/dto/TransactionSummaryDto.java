package com.lynceus.shared.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight transaction projection used in list/dashboard views. Mirrors the {@code
 * TransactionSummary} schema in {@code api-specs/shared/schemas/transaction.yaml}.
 *
 * @param id unique transaction identifier
 * @param amount transaction amount
 * @param merchantName name of the merchant as reported by the payment network
 * @param riskLevel risk tier assigned by the fraud scoring pipeline, null until scored
 * @param createdAt timestamp the transaction record was created
 */
public record TransactionSummaryDto(
    UUID id,
    BigDecimal amount,
    @JsonProperty("merchant_name") String merchantName,
    @JsonProperty("risk_level") String riskLevel,
    @JsonProperty("created_at") Instant createdAt) {}
