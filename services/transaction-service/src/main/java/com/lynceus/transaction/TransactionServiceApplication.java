package com.lynceus.transaction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Transaction Service — ingests transactions, persists them, and (Phase 1,
 * synchronously) calls the ML Inference Service for fraud scoring.
 *
 * <p>{@code scanBasePackages} is widened to {@code com.lynceus} (rather than the default
 * component-scan of just {@code com.lynceus.transaction} and its subpackages) so shared-lib's
 * {@code GlobalExceptionHandler} ({@code @RestControllerAdvice} in {@code
 * com.lynceus.shared.exception}) is picked up as a bean — it lives outside this service's own
 * package tree.
 */
@SpringBootApplication(scanBasePackages = "com.lynceus")
public class TransactionServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(TransactionServiceApplication.class, args);
  }
}
