package com.lynceus.shared.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Channel through which a transaction was initiated, matching the closed enum in the {@code
 * channel} field of {@code Transaction}, {@code CreateTransactionRequest}, and {@code
 * ScoreTransactionRequest} in {@code api-specs}.
 *
 * <p>A closed Java enum (rather than a plain {@code String}) rejects an invalid channel with a 400
 * at the API boundary instead of letting it flow downstream into feature engineering.
 *
 * <p>{@link #wireValue()}/{@link #fromWireValue(String)} pin JSON (de)serialization to the spec's
 * exact lowercase snake_case strings (e.g. {@code in_store}) instead of Jackson's default enum
 * handling, which would serialize the constant name verbatim (e.g. {@code IN_STORE}).
 */
public enum Channel {
  ONLINE("online"),
  IN_STORE("in_store"),
  ATM("atm"),
  MOBILE("mobile");

  private final String wireValue;

  Channel(String wireValue) {
    this.wireValue = wireValue;
  }

  @JsonValue
  public String wireValue() {
    return wireValue;
  }

  @JsonCreator
  public static Channel fromWireValue(String value) {
    for (Channel channel : values()) {
      if (channel.wireValue.equals(value)) {
        return channel;
      }
    }
    throw new IllegalArgumentException("Unknown channel: " + value);
  }
}
