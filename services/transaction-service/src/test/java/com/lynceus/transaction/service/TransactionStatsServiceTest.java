package com.lynceus.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.lynceus.shared.dto.RiskLevel;
import com.lynceus.shared.dto.RiskLevelCount;
import com.lynceus.shared.dto.ScoreDistributionResponse;
import com.lynceus.shared.dto.TimelineSeriesPoint;
import com.lynceus.shared.dto.TransactionStatsResponse;
import com.lynceus.shared.util.TenantContext;
import com.lynceus.transaction.model.dto.TimeInterval;
import com.lynceus.transaction.repository.FraudScoreRepository;
import com.lynceus.transaction.repository.TransactionRepository;
import com.lynceus.transaction.repository.projection.RiskLevelCountProjection;
import com.lynceus.transaction.repository.projection.ScoreBucketProjection;
import com.lynceus.transaction.repository.projection.TimelineBucketProjection;
import com.lynceus.transaction.repository.projection.TransactionStatsProjection;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link TransactionStatsService} with the repositories mocked — the projections
 * returned here are hand-rolled anonymous implementations of the plain getter interfaces (Mockito's
 * {@code mock(...)} would work too, but a real implementation is simpler for a four-getter
 * interface and avoids stubbing each getter individually).
 */
@ExtendWith(MockitoExtension.class)
class TransactionStatsServiceTest {

  private static final String TENANT_ID = "tenant-stats";

  @Mock private TransactionRepository transactionRepository;
  @Mock private FraudScoreRepository fraudScoreRepository;

  private TransactionStatsService transactionStatsService;

  @BeforeEach
  void setUp() {
    transactionStatsService =
        new TransactionStatsService(transactionRepository, fraudScoreRepository);
    TenantContext.set(TENANT_ID);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void overview_computesFraudRateFromFlaggedAndTotal() {
    when(transactionRepository.aggregateStats(eq(TENANT_ID)))
        .thenReturn(statsProjection(100L, 0.25, 20L, new BigDecimal("5000.00")));

    TransactionStatsResponse response = transactionStatsService.overview();

    assertThat(response.totalTransactions()).isEqualTo(100L);
    assertThat(response.averageScore()).isEqualTo(0.25);
    assertThat(response.flaggedCount()).isEqualTo(20L);
    assertThat(response.fraudRate()).isEqualTo(0.2);
    assertThat(response.totalAmountProcessed()).isEqualTo(new BigDecimal("5000.00"));
  }

  @Test
  void overview_withNoTransactions_returnsZeroFraudRateNotDivideByZero() {
    when(transactionRepository.aggregateStats(eq(TENANT_ID)))
        .thenReturn(statsProjection(0L, 0.0, 0L, BigDecimal.ZERO));

    TransactionStatsResponse response = transactionStatsService.overview();

    assertThat(response.fraudRate()).isZero();
  }

  @Test
  void scoreDistribution_zeroFillsBucketsWithNoScoredTransactions() {
    when(fraudScoreRepository.scoreDistribution(eq(TENANT_ID)))
        .thenReturn(List.of(bucketProjection(0, 3L), bucketProjection(9, 7L)));

    ScoreDistributionResponse response = transactionStatsService.scoreDistribution();

    assertThat(response.buckets()).hasSize(10);
    assertThat(response.buckets().get(0))
        .isEqualTo(new ScoreDistributionResponse.Bucket("0.0-0.1", 3L));
    assertThat(response.buckets().get(9))
        .isEqualTo(new ScoreDistributionResponse.Bucket("0.9-1.0", 7L));
    // Every bucket in between must still be present with a zero count, not omitted.
    assertThat(response.buckets().get(5))
        .isEqualTo(new ScoreDistributionResponse.Bucket("0.5-0.6", 0L));
  }

  @Test
  void riskBreakdown_mapsWireValuesToRiskLevelEnum() {
    when(fraudScoreRepository.riskBreakdown(eq(TENANT_ID)))
        .thenReturn(List.of(riskProjection("low", 40L), riskProjection("critical", 3L)));

    List<RiskLevelCount> breakdown = transactionStatsService.riskBreakdown();

    assertThat(breakdown)
        .containsExactly(
            new RiskLevelCount(RiskLevel.LOW, 40L), new RiskLevelCount(RiskLevel.CRITICAL, 3L));
  }

  @Test
  void timeline_mapsProjectionRowsToTimelineSeriesPoints() {
    Instant bucket = Instant.parse("2026-06-01T00:00:00Z");
    when(fraudScoreRepository.scoreTimeline(eq(TENANT_ID), eq("day")))
        .thenReturn(List.of(timelineProjection(bucket, 0.42)));

    List<TimelineSeriesPoint> timeline = transactionStatsService.timeline(TimeInterval.DAY);

    assertThat(timeline).containsExactly(new TimelineSeriesPoint(bucket, 0.42));
  }

  private static TransactionStatsProjection statsProjection(
      long total, double avg, long flagged, BigDecimal totalAmount) {
    return new TransactionStatsProjection() {
      @Override
      public long getTotalTransactions() {
        return total;
      }

      @Override
      public double getAverageScore() {
        return avg;
      }

      @Override
      public long getFlaggedCount() {
        return flagged;
      }

      @Override
      public BigDecimal getTotalAmountProcessed() {
        return totalAmount;
      }
    };
  }

  private static ScoreBucketProjection bucketProjection(int index, long count) {
    return new ScoreBucketProjection() {
      @Override
      public int getBucketIndex() {
        return index;
      }

      @Override
      public long getBucketCount() {
        return count;
      }
    };
  }

  private static RiskLevelCountProjection riskProjection(String riskLevel, long count) {
    return new RiskLevelCountProjection() {
      @Override
      public String getRiskLevel() {
        return riskLevel;
      }

      @Override
      public long getRiskCount() {
        return count;
      }
    };
  }

  private static TimelineBucketProjection timelineProjection(Instant timestamp, double avgScore) {
    return new TimelineBucketProjection() {
      @Override
      public Instant getBucketTimestamp() {
        return timestamp;
      }

      @Override
      public double getAvgScore() {
        return avgScore;
      }
    };
  }
}
