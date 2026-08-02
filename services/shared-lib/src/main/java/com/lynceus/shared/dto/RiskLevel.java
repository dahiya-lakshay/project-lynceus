package com.lynceus.shared.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Risk tier assigned by the fraud scoring pipeline, matching the closed enum declared on the {@code
 * risk_level} query parameter of {@code GET /api/v1/transactions} in {@code
 * api-specs/transaction-api.yaml}.
 *
 * <p>{@code FraudScore}/{@code FraudScoreDto}/{@code TransactionDto}'s own {@code risk_level} field
 * stays a plain {@code String} (it's assigned by the inference service, which owns that value — see
 * {@code ScoreTransactionResponse}), this enum exists specifically so a caller-supplied {@code
 * risk_level} filter value can be validated at the API boundary the same way {@link
 * MerchantCategory} and {@link Channel} are: an invalid value rejected with a 400 instead of
 * silently producing an always-empty result set.
 *
 * <p>{@link #wireValue()}/{@link #fromWireValue(String)} pin JSON/query-param (de)serialization to
 * the spec's exact lowercase strings (e.g. {@code critical}) instead of Jackson's default enum
 * handling, which would serialize the constant name verbatim (e.g. {@code CRITICAL}).
 */
public enum RiskLevel {
  LOW("low"),
  MEDIUM("medium"),
  HIGH("high"),
  CRITICAL("critical");

  private final String wireValue;

  RiskLevel(String wireValue) {
    this.wireValue = wireValue;
  }

  @JsonValue
  public String wireValue() {
    return wireValue;
  }

  @JsonCreator
  public static RiskLevel fromWireValue(String value) {
    for (RiskLevel riskLevel : values()) {
      if (riskLevel.wireValue.equals(value)) {
        return riskLevel;
      }
    }
    throw new IllegalArgumentException("Unknown risk_level: " + value);
  }
}
