package com.lynceus.shared.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Raw transaction attributes submitted to the inference service so it can compute features and
 * produce a fraud score. Mirrors the {@code ScoreTransactionRequest} schema in {@code
 * api-specs/shared/schemas/fraud-score.yaml}.
 *
 * <p>{@code tenantId} is required here (unlike {@link CreateTransactionRequest}) so the inference
 * service can populate the {@code tenant_id} column on the persisted {@link FraudScoreDto} record —
 * this field was added to the spec in a review fix.
 *
 * @param transactionId identifier of the transaction being scored
 * @param tenantId identifier of the owning tenant
 * @param customerId identifier of the customer the transaction belongs to
 * @param amount transaction amount
 * @param merchantCategory merchant category classification used for feature engineering
 * @param isOnline whether the transaction was performed online (card-not-present)
 * @param isForeign whether the transaction originated outside the customer's home country
 * @param channel channel through which the transaction was initiated
 * @param transactionTimestamp timestamp the transaction occurred, as reported by the source system
 */
public record ScoreTransactionRequest(
    @NotNull @JsonProperty("transaction_id") UUID transactionId,
    @NotNull @JsonProperty("tenant_id") String tenantId,
    @NotNull @JsonProperty("customer_id") UUID customerId,
    @NotNull @Positive BigDecimal amount,
    @NotNull @JsonProperty("merchant_category") String merchantCategory,
    @NotNull @JsonProperty("is_online") Boolean isOnline,
    @NotNull @JsonProperty("is_foreign") Boolean isForeign,
    @NotNull String channel,
    @NotNull @JsonProperty("transaction_timestamp") Instant transactionTimestamp) {}
