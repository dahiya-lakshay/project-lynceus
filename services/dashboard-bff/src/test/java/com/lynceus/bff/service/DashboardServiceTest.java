package com.lynceus.bff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynceus.bff.model.dto.FraudDistributionResponse;
import com.lynceus.bff.model.dto.OverviewResponse;
import com.lynceus.bff.model.dto.RiskBreakdownResponse;
import com.lynceus.bff.model.dto.TimelineResponse;
import com.lynceus.shared.dto.PagedResponse;
import com.lynceus.shared.dto.RiskLevel;
import com.lynceus.shared.dto.RiskLevelCount;
import com.lynceus.shared.dto.ScoreDistributionResponse;
import com.lynceus.shared.dto.TimelineSeriesPoint;
import com.lynceus.shared.dto.TransactionStatsResponse;
import com.lynceus.shared.dto.TransactionSummaryDto;
import com.lynceus.shared.util.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Unit tests for {@link DashboardService} with {@link TransactionClient} and every Redis template
 * mocked — mirrors {@code transaction-service}'s {@code TransactionServiceTest} pattern for mocking
 * {@code RedisTemplate}/{@code ValueOperations} directly rather than pulling in a real Redis
 * (Testcontainers or embedded).
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

  private static final String TENANT_ID = "tenant-dashboard";

  @Mock private TransactionClient transactionClient;

  @SuppressWarnings("unchecked")
  private final RedisTemplate<String, OverviewResponse> overviewRedisTemplate =
      mock(RedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, OverviewResponse> overviewValueOperations =
      mock(ValueOperations.class);

  @SuppressWarnings("unchecked")
  private final RedisTemplate<String, FraudDistributionResponse> fraudDistributionRedisTemplate =
      mock(RedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, FraudDistributionResponse>
      fraudDistributionValueOperations = mock(ValueOperations.class);

  @SuppressWarnings("unchecked")
  private final RedisTemplate<String, RiskBreakdownResponse> riskBreakdownRedisTemplate =
      mock(RedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, RiskBreakdownResponse> riskBreakdownValueOperations =
      mock(ValueOperations.class);

  private DashboardService dashboardService;

  @BeforeEach
  void setUp() {
    when(overviewRedisTemplate.opsForValue()).thenReturn(overviewValueOperations);
    when(fraudDistributionRedisTemplate.opsForValue()).thenReturn(fraudDistributionValueOperations);
    when(riskBreakdownRedisTemplate.opsForValue()).thenReturn(riskBreakdownValueOperations);
    dashboardService =
        new DashboardService(
            transactionClient,
            overviewRedisTemplate,
            fraudDistributionRedisTemplate,
            riskBreakdownRedisTemplate);
    TenantContext.set(TENANT_ID);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void overview_withCacheMiss_fetchesFromClientAndCachesResult() {
    when(overviewValueOperations.get(anyString())).thenReturn(null);
    when(transactionClient.getOverview(eq(TENANT_ID)))
        .thenReturn(new TransactionStatsResponse(100L, 0.31, 12L, 0.12, new BigDecimal("4200.50")));

    OverviewResponse response = dashboardService.overview();

    assertThat(response.totalTransactions()).isEqualTo(100L);
    assertThat(response.fraudRate()).isEqualTo(0.12);
    assertThat(response.averageScore()).isEqualTo(0.31);
    assertThat(response.flaggedCount()).isEqualTo(12L);
    assertThat(response.totalAmountProcessed()).isEqualByComparingTo(new BigDecimal("4200.50"));

    verify(overviewValueOperations).set(eq("dashboard:overview:" + TENANT_ID), eq(response), any());
  }

  @Test
  void overview_withCacheHit_returnsCachedValueWithoutCallingClient() {
    OverviewResponse cached = new OverviewResponse(5L, 0.0, 0.0, 0L, BigDecimal.ZERO);
    when(overviewValueOperations.get("dashboard:overview:" + TENANT_ID)).thenReturn(cached);

    OverviewResponse response = dashboardService.overview();

    assertThat(response).isEqualTo(cached);
    verify(transactionClient, never()).getOverview(anyString());
  }

  @Test
  void fraudDistribution_mapsSharedLibBucketsToBffBuckets() {
    when(fraudDistributionValueOperations.get(anyString())).thenReturn(null);
    ScoreDistributionResponse upstream =
        new ScoreDistributionResponse(List.of(new ScoreDistributionResponse.Bucket("0.0-0.1", 3L)));
    when(transactionClient.getScoreDistribution(eq(TENANT_ID))).thenReturn(upstream);

    FraudDistributionResponse response = dashboardService.fraudDistribution();

    assertThat(response.buckets())
        .containsExactly(new FraudDistributionResponse.Bucket("0.0-0.1", 3L));
    verify(fraudDistributionValueOperations)
        .set(eq("dashboard:fraud-dist:" + TENANT_ID), eq(response), any());
  }

  @Test
  void riskBreakdown_mapsSharedLibEntriesToBffEntries() {
    when(riskBreakdownValueOperations.get(anyString())).thenReturn(null);
    when(transactionClient.getRiskBreakdown(eq(TENANT_ID)))
        .thenReturn(List.of(new RiskLevelCount(RiskLevel.HIGH, 9L)));

    RiskBreakdownResponse response = dashboardService.riskBreakdown();

    assertThat(response.entries())
        .containsExactly(new RiskBreakdownResponse.Entry(RiskLevel.HIGH, 9L));
    verify(riskBreakdownValueOperations)
        .set(eq("dashboard:risk-breakdown:" + TENANT_ID), eq(response), any());
  }

  @Test
  void timeline_isNeverCached() {
    Instant bucket = Instant.parse("2026-06-01T00:00:00Z");
    when(transactionClient.getTimeline(eq(TENANT_ID)))
        .thenReturn(List.of(new TimelineSeriesPoint(bucket, 0.42)));

    TimelineResponse response = dashboardService.timeline();

    assertThat(response.points()).containsExactly(new TimelineResponse.Point(bucket, 0.42));
    // No cache interaction at all for timeline — verified implicitly: no Redis template is even
    // injected for it (see DashboardService's constructor), so there's nothing to verify a
    // non-interaction against beyond the fact this compiles/runs without one.
  }

  @Test
  void recentTransactions_delegatesStraightToClientWithoutTransformation() {
    PagedResponse<TransactionSummaryDto> upstream =
        new PagedResponse<>(
            List.of(
                new TransactionSummaryDto(
                    java.util.UUID.randomUUID(),
                    new BigDecimal("42.00"),
                    "Test Merchant",
                    "low",
                    Instant.now())),
            1,
            1,
            20);
    when(transactionClient.getRecentTransactions(eq(TENANT_ID), eq(1), eq(20)))
        .thenReturn(upstream);

    PagedResponse<TransactionSummaryDto> response = dashboardService.recentTransactions(1, 20);

    assertThat(response).isSameAs(upstream);
  }
}
