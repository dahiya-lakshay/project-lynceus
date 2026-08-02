package com.lynceus.shared.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Payload required to ingest a new transaction. Mirrors the {@code CreateTransactionRequest} schema
 * in {@code api-specs/shared/schemas/transaction.yaml}.
 *
 * <p>{@code tenant_id} is intentionally absent — Phase 1 has no auth yet, so the owning tenant is
 * resolved from the {@code X-Tenant-Id} header via {@link com.lynceus.shared.util.TenantContext},
 * not from the request body.
 *
 * @param customerId identifier of the customer the transaction belongs to
 * @param amount transaction amount; matches the spec's {@code minimum: 0} bound exactly — zero is a
 *     legitimate value (e.g. a $0 authorization/verification transaction)
 * @param currency ISO 4217 currency code; defaults to USD when omitted
 * @param merchantName name of the merchant as reported by the payment network
 * @param merchantCategory merchant category classification used for feature engineering
 * @param isOnline whether the transaction was performed online (card-not-present)
 * @param isForeign whether the transaction originated outside the customer's home country
 * @param channel channel through which the transaction was initiated
 * @param metadata optional free-form passthrough attributes; MUST NOT contain PII or full
 *     card/account numbers
 */
public record CreateTransactionRequest(
    @NotNull @JsonProperty("customer_id") UUID customerId,
    @NotNull @PositiveOrZero BigDecimal amount,
    String currency,
    @NotNull @JsonProperty("merchant_name") String merchantName,
    @NotNull @JsonProperty("merchant_category") MerchantCategory merchantCategory,
    @NotNull @JsonProperty("is_online") Boolean isOnline,
    @NotNull @JsonProperty("is_foreign") Boolean isForeign,
    @NotNull Channel channel,
    Map<String, Object> metadata) {}
