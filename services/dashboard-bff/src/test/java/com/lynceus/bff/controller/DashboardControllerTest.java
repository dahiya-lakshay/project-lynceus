package com.lynceus.bff.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lynceus.bff.exception.BffExceptionHandler;
import com.lynceus.bff.model.dto.FraudDistributionResponse;
import com.lynceus.bff.model.dto.OverviewResponse;
import com.lynceus.bff.model.dto.RiskBreakdownResponse;
import com.lynceus.bff.model.dto.TimelineResponse;
import com.lynceus.bff.service.DashboardService;
import com.lynceus.shared.dto.PagedResponse;
import com.lynceus.shared.dto.RiskLevel;
import com.lynceus.shared.dto.TransactionSummaryDto;
import com.lynceus.shared.exception.GlobalExceptionHandler;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code @WebMvcTest} slice for {@link DashboardController} — the service layer is mocked, this
 * only exercises request/response mapping, validation, and status codes. Both shared-lib's {@link
 * GlobalExceptionHandler} and dashboard-bff-local {@link BffExceptionHandler} are imported for the
 * same reasons documented on {@code transaction-service}'s {@code TransactionControllerTest}.
 */
@WebMvcTest(DashboardController.class)
@Import({GlobalExceptionHandler.class, BffExceptionHandler.class})
class DashboardControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private DashboardService dashboardService;

  @Test
  void overview_returnsKpis() throws Exception {
    when(dashboardService.overview())
        .thenReturn(new OverviewResponse(100L, 0.12, 0.31, 12L, new BigDecimal("4200.50")));

    mockMvc
        .perform(get("/api/v1/dashboard/overview").header("X-Tenant-Id", "default"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total_transactions").value(100))
        .andExpect(jsonPath("$.fraud_rate").value(0.12))
        .andExpect(jsonPath("$.total_amount_processed").value(4200.50));
  }

  @Test
  void recentTransactions_returnsPagedResponse() throws Exception {
    TransactionSummaryDto summary =
        new TransactionSummaryDto(
            UUID.randomUUID(), new BigDecimal("99.99"), "Test Store", "low", Instant.now());
    when(dashboardService.recentTransactions(1, 20))
        .thenReturn(new PagedResponse<>(List.of(summary), 1, 1, 20));

    mockMvc
        .perform(get("/api/v1/dashboard/transactions/recent").header("X-Tenant-Id", "default"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].merchant_name").value("Test Store"));
  }

  @Test
  void recentTransactions_withPageSizeAboveMaximum_returnsBadRequest() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/dashboard/transactions/recent")
                .header("X-Tenant-Id", "default")
                .param("page_size", "101"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
  }

  @Test
  void recentTransactions_withPageBelowMinimum_returnsBadRequest() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/dashboard/transactions/recent")
                .header("X-Tenant-Id", "default")
                .param("page", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
  }

  @Test
  void fraudDistribution_returnsBuckets() throws Exception {
    when(dashboardService.fraudDistribution())
        .thenReturn(
            new FraudDistributionResponse(
                List.of(new FraudDistributionResponse.Bucket("0.0-0.1", 5L))));

    mockMvc
        .perform(get("/api/v1/dashboard/fraud-distribution").header("X-Tenant-Id", "default"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.buckets[0].range").value("0.0-0.1"))
        .andExpect(jsonPath("$.buckets[0].count").value(5));
  }

  @Test
  void riskBreakdown_returnsBareArray() throws Exception {
    when(dashboardService.riskBreakdown())
        .thenReturn(
            new RiskBreakdownResponse(
                List.of(new RiskBreakdownResponse.Entry(RiskLevel.HIGH, 9L))));

    mockMvc
        .perform(get("/api/v1/dashboard/risk-breakdown").header("X-Tenant-Id", "default"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].risk_level").value("high"))
        .andExpect(jsonPath("$[0].count").value(9));
  }

  @Test
  void timeline_returnsBareArray() throws Exception {
    Instant bucket = Instant.parse("2026-06-01T00:00:00Z");
    when(dashboardService.timeline())
        .thenReturn(new TimelineResponse(List.of(new TimelineResponse.Point(bucket, 0.5))));

    mockMvc
        .perform(get("/api/v1/dashboard/timeline").header("X-Tenant-Id", "default"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].average_score").value(0.5));
  }
}
