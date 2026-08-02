package com.lynceus.shared.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ScoreTransactionRequestTest {

  private static ValidatorFactory validatorFactory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    validatorFactory = Validation.buildDefaultValidatorFactory();
    validator = validatorFactory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    validatorFactory.close();
  }

  @Test
  void validate_withValidRequest_hasNoViolations() {
    var request =
        new ScoreTransactionRequest(
            UUID.randomUUID(),
            "tenant-acme",
            UUID.randomUUID(),
            new BigDecimal("42.50"),
            MerchantCategory.GROCERY,
            true,
            false,
            Channel.ONLINE,
            Instant.now());

    Set<ConstraintViolation<ScoreTransactionRequest>> violations = validator.validate(request);

    assertTrue(violations.isEmpty());
  }

  // The spec's amount constraint is `minimum: 0` — a $0 authorization/verification
  // transaction is a legitimate real-world case, so zero must NOT be rejected.
  @Test
  void validate_withZeroAmount_isAccepted() {
    var request =
        new ScoreTransactionRequest(
            UUID.randomUUID(),
            "tenant-acme",
            UUID.randomUUID(),
            BigDecimal.ZERO,
            MerchantCategory.GROCERY,
            true,
            false,
            Channel.ONLINE,
            Instant.now());

    Set<ConstraintViolation<ScoreTransactionRequest>> violations = validator.validate(request);

    assertTrue(violations.isEmpty());
  }

  @Test
  void validate_withNegativeAmount_rejectsAmount() {
    var request =
        new ScoreTransactionRequest(
            UUID.randomUUID(),
            "tenant-acme",
            UUID.randomUUID(),
            new BigDecimal("-10.00"),
            MerchantCategory.GROCERY,
            true,
            false,
            Channel.ONLINE,
            Instant.now());

    Set<ConstraintViolation<ScoreTransactionRequest>> violations = validator.validate(request);

    assertFalse(violations.isEmpty());
    assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("amount")));
  }
}
