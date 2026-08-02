package com.lynceus.shared.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves the {@link com.fasterxml.jackson.annotation.JsonProperty} snake_case mappings on {@link
 * FraudScoreDto} actually produce (and round-trip) the wire format {@code
 * api-specs/shared/schemas/fraud-score.yaml}'s {@code FraudScore} schema expects.
 */
class FraudScoreDtoTest {

  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
  }

  @Test
  void serialize_usesSnakeCaseKeysMatchingSpec() {
    var dto =
        new FraudScoreDto(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "tenant-acme",
            0.42,
            0.31,
            0.55,
            "medium",
            "ensemble-v1.2.0",
            Map.of("velocity_24h", 3),
            Map.of("top_feature", "velocity_24h"),
            Instant.parse("2026-08-02T02:00:00Z"));

    JsonNode json = objectMapper.valueToTree(dto);

    assertTrue(json.has("transaction_id"));
    assertTrue(json.has("tenant_id"));
    assertTrue(json.has("isolation_forest_score"));
    assertTrue(json.has("autoencoder_score"));
    assertTrue(json.has("ensemble_score"));
    assertTrue(json.has("risk_level"));
    assertTrue(json.has("model_version"));
    assertTrue(json.has("feature_vector"));
    assertTrue(json.has("scored_at"));

    // camelCase Java field names must never leak onto the wire.
    assertFalse(json.has("transactionId"));
    assertFalse(json.has("isolationForestScore"));
    assertFalse(json.has("modelVersion"));
  }

  @Test
  void roundTrip_deserializesBackToEquivalentDto() throws Exception {
    var original =
        new FraudScoreDto(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "tenant-acme",
            0.1,
            null,
            0.15,
            "low",
            "ensemble-v1.2.0",
            Map.of("velocity_24h", 1),
            null,
            Instant.parse("2026-08-02T02:00:00Z"));

    String json = objectMapper.writeValueAsString(original);
    FraudScoreDto roundTripped = objectMapper.readValue(json, FraudScoreDto.class);

    assertEquals(original, roundTripped);
  }
}
