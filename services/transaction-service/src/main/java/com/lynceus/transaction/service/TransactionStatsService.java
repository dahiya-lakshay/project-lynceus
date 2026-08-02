package com.lynceus.transaction.service;

import com.lynceus.shared.dto.RiskLevel;
import com.lynceus.shared.dto.RiskLevelCount;
import com.lynceus.shared.dto.ScoreDistributionResponse;
import com.lynceus.shared.dto.TimelineSeriesPoint;
import com.lynceus.shared.dto.TransactionStatsResponse;
import com.lynceus.shared.util.TenantContext;
import com.lynceus.transaction.model.dto.TimeInterval;
import com.lynceus.transaction.repository.FraudScoreRepository;
import com.lynceus.transaction.repository.TransactionRepository;
import com.lynceus.transaction.repository.projection.ScoreBucketProjection;
import com.lynceus.transaction.repository.projection.TransactionStatsProjection;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Aggregate KPI/chart computations backing the {@code /api/v1/transactions/stats/*} endpoints (Task
 * 8, Dashboard BFF). Deliberately its own service rather than added to {@link TransactionService}:
 * that class already handles ingestion and single-record retrieval, and these four read-only
 * aggregate queries are a distinct concern with no shared state — keeping them separate means this
 * addition never has to touch (or risk regressing) the already-reviewed {@link TransactionService}.
 */
@Slf4j
@Service
public class TransactionStatsService {

  // The ten 0.1-wide buckets ScoreDistributionResponse always returns, in order — precomputed
  // once rather than string-built per request.
  private static final List<String> BUCKET_RANGES =
      List.of(
          "0.0-0.1", "0.1-0.2", "0.2-0.3", "0.3-0.4", "0.4-0.5", "0.5-0.6", "0.6-0.7", "0.7-0.8",
          "0.8-0.9", "0.9-1.0");

  private final TransactionRepository transactionRepository;
  private final FraudScoreRepository fraudScoreRepository;

  public TransactionStatsService(
      TransactionRepository transactionRepository, FraudScoreRepository fraudScoreRepository) {
    this.transactionRepository = transactionRepository;
    this.fraudScoreRepository = fraudScoreRepository;
  }

  public TransactionStatsResponse overview() {
    String tenantId = requireTenantId();
    log.debug("Computing dashboard overview stats for tenant {}", tenantId);
    TransactionStatsProjection stats = transactionRepository.aggregateStats(tenantId);

    // fraud_rate is derived here rather than in SQL: it's a simple ratio of two values the
    // query already computes, and computing it in Java avoids a second division expression
    // (and the divide-by-zero case) inside the aggregate query itself.
    double fraudRate =
        stats.getTotalTransactions() == 0
            ? 0.0
            : (double) stats.getFlaggedCount() / stats.getTotalTransactions();

    return new TransactionStatsResponse(
        stats.getTotalTransactions(),
        stats.getAverageScore(),
        stats.getFlaggedCount(),
        fraudRate,
        stats.getTotalAmountProcessed());
  }

  public ScoreDistributionResponse scoreDistribution() {
    String tenantId = requireTenantId();
    log.debug("Computing score distribution for tenant {}", tenantId);
    List<ScoreBucketProjection> rows = fraudScoreRepository.scoreDistribution(tenantId);

    // The query only returns rows for buckets that actually have at least one scored
    // transaction (see its Javadoc) — every one of the ten buckets must still appear in the
    // response with a count of 0 rather than being silently omitted, so the dashboard's
    // histogram always renders all ten bars.
    long[] counts = new long[BUCKET_RANGES.size()];
    for (ScoreBucketProjection row : rows) {
      counts[row.getBucketIndex()] = row.getBucketCount();
    }

    List<ScoreDistributionResponse.Bucket> buckets = new ArrayList<>(BUCKET_RANGES.size());
    for (int i = 0; i < BUCKET_RANGES.size(); i++) {
      buckets.add(new ScoreDistributionResponse.Bucket(BUCKET_RANGES.get(i), counts[i]));
    }
    return new ScoreDistributionResponse(buckets);
  }

  public List<RiskLevelCount> riskBreakdown() {
    String tenantId = requireTenantId();
    log.debug("Computing risk breakdown for tenant {}", tenantId);

    // Same zero-fill reasoning as scoreDistribution: the query only returns levels that have at
    // least one scored transaction, but the API contract documents that a level with zero
    // scored transactions is simply omitted (see the spec) rather than zero-filled — so here,
    // unlike the histogram, the raw query result is returned as-is once mapped to RiskLevel.
    return fraudScoreRepository.riskBreakdown(tenantId).stream()
        .map(
            row ->
                new RiskLevelCount(RiskLevel.fromWireValue(row.getRiskLevel()), row.getRiskCount()))
        .toList();
  }

  public List<TimelineSeriesPoint> timeline(TimeInterval interval) {
    String tenantId = requireTenantId();
    log.debug("Computing score timeline for tenant {} at interval {}", tenantId, interval);
    return fraudScoreRepository.scoreTimeline(tenantId, interval.wireValue()).stream()
        .map(row -> new TimelineSeriesPoint(row.getBucketTimestamp(), row.getAvgScore()))
        .toList();
  }

  private String requireTenantId() {
    String tenantId = TenantContext.get();
    if (tenantId == null) {
      // Same invariant TransactionService.requireTenantId documents: TenantFilter always
      // populates this for every /api/* request, so null here means the filter isn't wired up
      // at all, not something a caller triggered.
      throw new IllegalStateException("TenantContext not populated; is TenantFilter registered?");
    }
    return tenantId;
  }
}
