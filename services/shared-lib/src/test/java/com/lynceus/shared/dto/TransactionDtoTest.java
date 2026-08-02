package com.lynceus.shared.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves the {@link com.fasterxml.jackson.annotation.JsonProperty} snake_case mappings on {@link
 * TransactionDto} actually produce (and round-trip) the wire format {@code
 * api-specs/shared/schemas/transaction.yaml}'s {@code Transaction} schema expects, rather than
 * leaving that as a manually-verified assumption.
 */
class TransactionDtoTest {

  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
  }

  @Test
  void serialize_usesSnakeCaseKeysMatchingSpec() throws Exception {
    var dto =
        new TransactionDto(
            UUID.randomUUID(),
            "tenant-acme",
            UUID.randomUUID(),
            new BigDecimal("42.50"),
            "USD",
            "Acme Store",
            MerchantCategory.GAS_STATION,
            true,
            false,
            Channel.IN_STORE,
            Map.of("note", "test"),
            "high",
            0.87,
            Instant.parse("2026-08-02T02:00:00Z"),
            Instant.parse("2026-08-02T02:05:00Z"));

    JsonNode json = objectMapper.valueToTree(dto);

    assertTrue(json.has("tenant_id"));
    assertTrue(json.has("customer_id"));
    assertTrue(json.has("merchant_name"));
    assertTrue(json.has("merchant_category"));
    assertTrue(json.has("is_online"));
    assertTrue(json.has("is_foreign"));
    assertTrue(json.has("risk_level"));
    assertTrue(json.has("fraud_score"));
    assertTrue(json.has("created_at"));
    assertTrue(json.has("updated_at"));

    // camelCase Java field names must never leak onto the wire.
    assertFalse(json.has("tenantId"));
    assertFalse(json.has("customerId"));
    assertFalse(json.has("merchantName"));

    // Enum wire values must be the spec's exact lowercase snake_case strings, not the
    // Java constant names (GAS_STATION / IN_STORE).
    assertEquals("gas_station", json.get("merchant_category").asText());
    assertEquals("in_store", json.get("channel").asText());
  }

  @Test
  void roundTrip_deserializesBackToEquivalentDto() throws Exception {
    var original =
        new TransactionDto(
            UUID.randomUUID(),
            "tenant-acme",
            UUID.randomUUID(),
            new BigDecimal("99.99"),
            "USD",
            "Acme Store",
            MerchantCategory.ONLINE_SHOPPING,
            true,
            true,
            Channel.MOBILE,
            null,
            null,
            null,
            Instant.parse("2026-08-02T02:00:00Z"),
            Instant.parse("2026-08-02T02:00:00Z"));

    String json = objectMapper.writeValueAsString(original);
    TransactionDto roundTripped = objectMapper.readValue(json, TransactionDto.class);

    assertEquals(original, roundTripped);
  }
}
