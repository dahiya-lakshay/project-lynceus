package com.lynceus.shared.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Merchant category classification used for feature engineering, matching the closed enum in the
 * {@code merchant_category} field of {@code Transaction}, {@code CreateTransactionRequest}, and
 * {@code ScoreTransactionRequest} in {@code api-specs}.
 *
 * <p>A closed Java enum (rather than a plain {@code String}) turns an invalid category into a 400
 * at the API boundary instead of silently reaching the inference service's feature engineering
 * pipeline, which hard-codes this exact category list (see Task 7's {@code feature_engineer.py})
 * and would otherwise produce garbage features for an unrecognized value.
 *
 * <p>{@link #wireValue()}/{@link #fromWireValue(String)} pin JSON (de)serialization to the spec's
 * exact lowercase snake_case strings (e.g. {@code gas_station}) instead of Jackson's default enum
 * handling, which would serialize the constant name verbatim (e.g. {@code GAS_STATION}).
 */
public enum MerchantCategory {
  GROCERY("grocery"),
  ELECTRONICS("electronics"),
  GAS_STATION("gas_station"),
  RESTAURANT("restaurant"),
  ONLINE_SHOPPING("online_shopping"),
  TRAVEL("travel"),
  ENTERTAINMENT("entertainment"),
  HEALTHCARE("healthcare"),
  UTILITIES("utilities"),
  CLOTHING("clothing");

  private final String wireValue;

  MerchantCategory(String wireValue) {
    this.wireValue = wireValue;
  }

  @JsonValue
  public String wireValue() {
    return wireValue;
  }

  @JsonCreator
  public static MerchantCategory fromWireValue(String value) {
    for (MerchantCategory category : values()) {
      if (category.wireValue.equals(value)) {
        return category;
      }
    }
    throw new IllegalArgumentException("Unknown merchant_category: " + value);
  }
}
