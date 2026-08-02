package com.lynceus.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Dashboard BFF (backend-for-frontend). Aggregates transaction and
 * fraud-scoring data from the Transaction Service into the KPIs/charts the fraud-analyst dashboard
 * needs, per {@code api-specs/dashboard-bff-api.yaml}. Owns no primary data of its own.
 */
@SpringBootApplication
public class DashboardBffApplication {

  public static void main(String[] args) {
    SpringApplication.run(DashboardBffApplication.class, args);
  }
}
