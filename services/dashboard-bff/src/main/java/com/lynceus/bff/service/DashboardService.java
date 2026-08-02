package com.lynceus.bff.service;

import com.lynceus.bff.model.dto.FraudDistributionResponse;
import com.lynceus.bff.model.dto.OverviewResponse;
import com.lynceus.bff.model.dto.RiskBreakdownResponse;
import com.lynceus.bff.model.dto.TimelineResponse;
import com.lynceus.shared.dto.PagedResponse;
import com.lynceus.shared.dto.RiskLevelCount;
import com.lynceus.shared.dto.ScoreDistributionResponse;
import com.lynceus.shared.dto.TimelineSeriesPoint;
import com.lynceus.shared.dto.TransactionStatsResponse;
import com.lynceus.shared.dto.TransactionSummaryDto;
import com.lynceus.shared.util.TenantContext;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Fetches aggregate data from transaction-service via {@link TransactionClient} and transforms it
 * into the BFF's own response DTOs matching {@code api-specs/dashboard-bff-api.yaml} exactly.
 *
 * <p><b>Caching:</b> {@code overview} is cached at Redis key {@code dashboard:overview:{tenantId}}
 * for 30s; {@code fraud-distribution} and {@code risk-breakdown} at {@code
 * dashboard:fraud-dist:{tenantId}} / {@code dashboard:risk-breakdown:{tenantId}} for 60s each — a
 * fraud analyst's dashboard doesn't need up-to-the-second aggregate KPIs, and these are exactly the
 * queries that would otherwise hit transaction-service's full-table aggregation on every page
 * load/refresh. {@code timeline} and {@code transactions/recent} are deliberately NOT cached:
 * {@code transactions/recent} must always reflect the truly latest transactions (an analyst
 * investigating a live incident needs the real list, not a stale one), and {@code timeline} is a
 * chart series over the tenant's full history that changes far less per-request than it would cost
 * to reason about a sensible TTL for relative to the other three — simplest to just not cache it in
 * Phase 1 and revisit if it shows up as a real hot path.
 */
@Slf4j
@Service
public class DashboardService {

  private static final Duration OVERVIEW_TTL = Duration.ofSeconds(30);
  private static final Duration FRAUD_DISTRIBUTION_TTL = Duration.ofSeconds(60);
  private static final Duration RISK_BREAKDOWN_TTL = Duration.ofSeconds(60);

  private static final String OVERVIEW_KEY_PREFIX = "dashboard:overview:";
  private static final String FRAUD_DISTRIBUTION_KEY_PREFIX = "dashboard:fraud-dist:";
  private static final String RISK_BREAKDOWN_KEY_PREFIX = "dashboard:risk-breakdown:";

  private final TransactionClient transactionClient;
  private final RedisTemplate<String, OverviewResponse> overviewRedisTemplate;
  private final RedisTemplate<String, FraudDistributionResponse> fraudDistributionRedisTemplate;
  private final RedisTemplate<String, RiskBreakdownResponse> riskBreakdownRedisTemplate;

  public DashboardService(
      TransactionClient transactionClient,
      RedisTemplate<String, OverviewResponse> overviewRedisTemplate,
      RedisTemplate<String, FraudDistributionResponse> fraudDistributionRedisTemplate,
      RedisTemplate<String, RiskBreakdownResponse> riskBreakdownRedisTemplate) {
    this.transactionClient = transactionClient;
    this.overviewRedisTemplate = overviewRedisTemplate;
    this.fraudDistributionRedisTemplate = fraudDistributionRedisTemplate;
    this.riskBreakdownRedisTemplate = riskBreakdownRedisTemplate;
  }

  public OverviewResponse overview() {
    String tenantId = requireTenantId();
    String cacheKey = OVERVIEW_KEY_PREFIX + tenantId;

    OverviewResponse cached = overviewRedisTemplate.opsForValue().get(cacheKey);
    if (cached != null) {
      return cached;
    }

    TransactionStatsResponse stats = transactionClient.getOverview(tenantId);
    OverviewResponse response =
        new OverviewResponse(
            stats.totalTransactions(),
            stats.fraudRate(),
            stats.averageScore(),
            stats.flaggedCount(),
            stats.totalAmountProcessed());
    overviewRedisTemplate.opsForValue().set(cacheKey, response, OVERVIEW_TTL);
    return response;
  }

  public FraudDistributionResponse fraudDistribution() {
    String tenantId = requireTenantId();
    String cacheKey = FRAUD_DISTRIBUTION_KEY_PREFIX + tenantId;

    FraudDistributionResponse cached = fraudDistributionRedisTemplate.opsForValue().get(cacheKey);
    if (cached != null) {
      return cached;
    }

    ScoreDistributionResponse distribution = transactionClient.getScoreDistribution(tenantId);
    List<FraudDistributionResponse.Bucket> buckets =
        distribution.buckets().stream()
            .map(bucket -> new FraudDistributionResponse.Bucket(bucket.range(), bucket.count()))
            .toList();
    FraudDistributionResponse response = new FraudDistributionResponse(buckets);
    fraudDistributionRedisTemplate.opsForValue().set(cacheKey, response, FRAUD_DISTRIBUTION_TTL);
    return response;
  }

  public RiskBreakdownResponse riskBreakdown() {
    String tenantId = requireTenantId();
    String cacheKey = RISK_BREAKDOWN_KEY_PREFIX + tenantId;

    RiskBreakdownResponse cached = riskBreakdownRedisTemplate.opsForValue().get(cacheKey);
    if (cached != null) {
      return cached;
    }

    List<RiskLevelCount> counts = transactionClient.getRiskBreakdown(tenantId);
    List<RiskBreakdownResponse.Entry> entries =
        counts.stream()
            .map(count -> new RiskBreakdownResponse.Entry(count.riskLevel(), count.count()))
            .toList();
    RiskBreakdownResponse response = new RiskBreakdownResponse(entries);
    riskBreakdownRedisTemplate.opsForValue().set(cacheKey, response, RISK_BREAKDOWN_TTL);
    return response;
  }

  public TimelineResponse timeline() {
    String tenantId = requireTenantId();
    List<TimelineSeriesPoint> points = transactionClient.getTimeline(tenantId);
    List<TimelineResponse.Point> mapped =
        points.stream()
            .map(point -> new TimelineResponse.Point(point.timestamp(), point.averageScore()))
            .toList();
    return new TimelineResponse(mapped);
  }

  public PagedResponse<TransactionSummaryDto> recentTransactions(int page, int pageSize) {
    String tenantId = requireTenantId();
    // Straight pass-through, matching TransactionSummaryDto's shape exactly — no
    // transformation needed (see this class's Javadoc on why transactions/recent isn't cached).
    return transactionClient.getRecentTransactions(tenantId, page, pageSize);
  }

  private String requireTenantId() {
    String tenantId = TenantContext.get();
    if (tenantId == null) {
      // Same invariant transaction-service's TransactionService.requireTenantId documents:
      // TenantFilter always populates this for every /api/* request, so null here means the
      // filter isn't wired up at all, not something a caller triggered.
      throw new IllegalStateException("TenantContext not populated; is TenantFilter registered?");
    }
    return tenantId;
  }
}
