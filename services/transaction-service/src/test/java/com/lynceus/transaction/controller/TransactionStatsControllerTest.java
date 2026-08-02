package com.lynceus.transaction.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lynceus.shared.dto.RiskLevel;
import com.lynceus.shared.dto.RiskLevelCount;
import com.lynceus.shared.dto.ScoreDistributionResponse;
import com.lynceus.shared.dto.TimelineSeriesPoint;
import com.lynceus.shared.dto.TransactionStatsResponse;
import com.lynceus.shared.exception.GlobalExceptionHandler;
import com.lynceus.transaction.config.WebConfig;
import com.lynceus.transaction.exception.TransactionExceptionHandler;
import com.lynceus.transaction.model.dto.TimeInterval;
import com.lynceus.transaction.service.TransactionStatsService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code @WebMvcTest} slice for {@link TransactionStatsController} — same shape as {@code
 * TransactionControllerTest}: the service layer is mocked, this only exercises request/response
 * mapping and status codes. Both shared-lib's {@link GlobalExceptionHandler} and
 * transaction-service-local {@link TransactionExceptionHandler} are imported for the same reasons
 * documented on {@code TransactionControllerTest}. {@link WebConfig} is imported too — unlike
 * {@code TransactionControllerTest}, this controller has a query parameter ({@code interval}) that
 * depends on {@code WebConfig}'s {@code timeIntervalConverter} bean, which {@code @WebMvcTest}'s
 * slicing does not auto-detect (it only auto-detects beans declared on classes recognized as
 * Controller/ControllerAdvice/Converter/Filter/etc. stereotypes, not arbitrary
 * {@code @Configuration} classes that happen to declare a {@code Converter} {@code @Bean} inside
 * them).
 */
@WebMvcTest(TransactionStatsController.class)
@Import({GlobalExceptionHandler.class, TransactionExceptionHandler.class, WebConfig.class})
class TransactionStatsControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private TransactionStatsService transactionStatsService;

  @Test
  void overview_returnsAggregateStats() throws Exception {
    when(transactionStatsService.overview())
        .thenReturn(new TransactionStatsResponse(100L, 0.31, 12L, 0.12, new BigDecimal("4200.50")));

    mockMvc
        .perform(get("/api/v1/transactions/stats/overview").header("X-Tenant-Id", "default"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total_transactions").value(100))
        .andExpect(jsonPath("$.fraud_rate").value(0.12))
        .andExpect(jsonPath("$.total_amount_processed").value(4200.50));
  }

  @Test
  void scoreDistribution_returnsTenBuckets() throws Exception {
    List<ScoreDistributionResponse.Bucket> buckets =
        List.of(new ScoreDistributionResponse.Bucket("0.0-0.1", 5L));
    when(transactionStatsService.scoreDistribution())
        .thenReturn(new ScoreDistributionResponse(buckets));

    mockMvc
        .perform(
            get("/api/v1/transactions/stats/score-distribution").header("X-Tenant-Id", "default"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.buckets[0].range").value("0.0-0.1"))
        .andExpect(jsonPath("$.buckets[0].count").value(5));
  }

  @Test
  void riskBreakdown_returnsCountsPerRiskLevel() throws Exception {
    when(transactionStatsService.riskBreakdown())
        .thenReturn(List.of(new RiskLevelCount(RiskLevel.HIGH, 9L)));

    mockMvc
        .perform(get("/api/v1/transactions/stats/risk-breakdown").header("X-Tenant-Id", "default"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].risk_level").value("high"))
        .andExpect(jsonPath("$[0].count").value(9));
  }

  @Test
  void timeline_defaultsToDayInterval() throws Exception {
    Instant bucket = Instant.parse("2026-06-01T00:00:00Z");
    when(transactionStatsService.timeline(eq(TimeInterval.DAY)))
        .thenReturn(List.of(new TimelineSeriesPoint(bucket, 0.5)));

    mockMvc
        .perform(get("/api/v1/transactions/stats/timeline").header("X-Tenant-Id", "default"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].average_score").value(0.5));
  }

  @Test
  void timeline_withInvalidInterval_returnsBadRequest() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/transactions/stats/timeline")
                .header("X-Tenant-Id", "default")
                .param("interval", "fortnight"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
  }
}
