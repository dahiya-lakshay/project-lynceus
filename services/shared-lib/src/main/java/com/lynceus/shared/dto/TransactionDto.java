package com.lynceus.shared.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Full transaction representation, including fraud scoring results once the inference pipeline has
 * scored it. Mirrors the {@code Transaction} schema in {@code
 * api-specs/shared/schemas/transaction.yaml}.
 *
 * <p>Field names carry explicit {@link JsonProperty} mappings to snake_case so the wire format
 * matches the OpenAPI contract exactly, independent of any per-service Jackson naming-strategy
 * configuration.
 *
 * @param id unique transaction identifier
 * @param tenantId identifier of the owning tenant (financial institution)
 * @param customerId identifier of the customer the transaction belongs to
 * @param amount transaction amount in the given currency's minor-agnostic decimal form
 * @param currency ISO 4217 currency code
 * @param merchantName name of the merchant as reported by the payment network
 * @param merchantCategory merchant category classification used for feature engineering
 * @param isOnline whether the transaction was performed online (card-not-present)
 * @param isForeign whether the transaction originated outside the customer's home country
 * @param channel channel through which the transaction was initiated
 * @param metadata free-form passthrough attributes captured at ingestion time; MUST NOT contain PII
 *     or full card/account numbers
 * @param riskLevel risk tier assigned by the fraud scoring pipeline, null until scored
 * @param fraudScore ensemble fraud probability in [0, 1], null until scored
 * @param createdAt timestamp the transaction record was created
 * @param updatedAt timestamp the transaction record was last updated
 */
public record TransactionDto(
    UUID id,
    @JsonProperty("tenant_id") String tenantId,
    @JsonProperty("customer_id") UUID customerId,
    BigDecimal amount,
    String currency,
    @JsonProperty("merchant_name") String merchantName,
    @JsonProperty("merchant_category") String merchantCategory,
    @JsonProperty("is_online") boolean isOnline,
    @JsonProperty("is_foreign") boolean isForeign,
    String channel,
    Map<String, Object> metadata,
    @JsonProperty("risk_level") String riskLevel,
    @JsonProperty("fraud_score") Double fraudScore,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("updated_at") Instant updatedAt) {}
