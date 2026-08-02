package com.lynceus.transaction.repository;

import com.lynceus.transaction.model.entity.FraudScore;
import com.lynceus.transaction.repository.projection.RiskLevelCountProjection;
import com.lynceus.transaction.repository.projection.ScoreBucketProjection;
import com.lynceus.transaction.repository.projection.TimelineBucketProjection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Not called out explicitly in the task's file tree (only {@code TransactionRepository} was), but
 * {@code fraud_scores} is a tenant-owned table in its own right (AGENTS.md's hard multi-tenancy
 * rule applies here too), and {@code TransactionService} needs a way to look up a transaction's
 * score — both for a single {@code findById} and, batched via {@link
 * #findByTenantIdAndTransactionIdIn}, to avoid an N+1 query per row when rendering a page of {@code
 * TransactionSummaryDto}s.
 *
 * <p>Task 8 (Dashboard BFF) adds three more aggregate queries here rather than on {@link
 * TransactionRepository}: score distribution, risk breakdown, and the score timeline are all
 * fundamentally properties of scored transactions (they group by columns that only exist on {@code
 * fraud_scores}), and none of them need {@code transactions} joined in at all — unlike {@code
 * TransactionRepository.aggregateStats}, which does need the join because unscored transactions
 * still count toward {@code total_transactions}/{@code total_amount_processed}.
 */
public interface FraudScoreRepository extends JpaRepository<FraudScore, UUID> {

  Optional<FraudScore> findByTenantIdAndTransactionId(String tenantId, UUID transactionId);

  List<FraudScore> findByTenantIdAndTransactionIdIn(String tenantId, List<UUID> transactionIds);

  // Backs GET /api/v1/transactions/stats/score-distribution. bucket_index is computed as
  // LEAST(FLOOR(ensemble_score * 10), 9) rather than Postgres's built-in width_bucket(...):
  // width_bucket(score, 0, 1, 10) maps a score of exactly 1.0000 (a legal value — ensemble_score
  // has no upper-bound constraint tighter than the DECIMAL(5,4) column type, and a perfect
  // anomaly score is plausible) to bucket 11, one past the last of the 10 requested buckets,
  // since width_bucket treats its upper bound as exclusive. LEAST(..., 9) instead clamps that
  // edge case into the last bucket (0.9-1.0 inclusive of 1.0), matching the API contract's
  // documented "inclusive-lower, exclusive-upper" ranges with the one deliberate exception of the
  // final bucket's upper edge.
  //
  // Grouped by the expression itself (not the "bucketIndex" alias) — see
  // TransactionRepository.aggregateStats's Javadoc for why aliases are quoted camelCase; grouping
  // by the raw expression sidesteps any question of whether GROUP BY can see a SELECT-list alias
  // at all (Postgres allows it, but this avoids relying on that engine-specific behavior).
  @Query(
      value =
          """
          SELECT
            LEAST(FLOOR(fs.ensemble_score * 10)::int, 9) AS "bucketIndex",
            COUNT(*) AS "bucketCount"
          FROM fraud_scores fs
          WHERE fs.tenant_id = :tenantId
          GROUP BY LEAST(FLOOR(fs.ensemble_score * 10)::int, 9)
          ORDER BY LEAST(FLOOR(fs.ensemble_score * 10)::int, 9)
          """,
      nativeQuery = true)
  List<ScoreBucketProjection> scoreDistribution(@Param("tenantId") String tenantId);

  // Backs GET /api/v1/transactions/stats/risk-breakdown. Only risk levels with at least one
  // scored transaction come back — the caller (TransactionStatsService) fills in zero counts for
  // any of the four levels missing from this result, rather than this query doing it (a GROUP BY
  // can't produce rows for values that aren't present in the data without a separate seed table
  // to LEFT JOIN against, which isn't worth it for four well-known constant values).
  @Query(
      value =
          """
          SELECT fs.risk_level AS "riskLevel", COUNT(*) AS "riskCount"
          FROM fraud_scores fs
          WHERE fs.tenant_id = :tenantId
          GROUP BY fs.risk_level
          """,
      nativeQuery = true)
  List<RiskLevelCountProjection> riskBreakdown(@Param("tenantId") String tenantId);

  // Backs GET /api/v1/transactions/stats/timeline. Buckets by fs.scored_at (not
  // transactions.created_at) so this query never needs to join transactions at all — Phase 1
  // scores synchronously within the same request as ingestion (see TransactionService.create),
  // so the two timestamps are for all practical purposes identical anyway.
  //
  // :interval is bound as an ordinary parameter (never string-concatenated), so there's no SQL
  // injection surface here regardless — TimeInterval's closed enum at the controller layer exists
  // to turn an invalid value into a 400 before it ever reaches this query, not to guard against
  // injection.
  //
  // GROUP BY / ORDER BY reference the "bucketTimestamp" output alias rather than repeating
  // date_trunc(:interval, fs.scored_at) verbatim (unlike scoreDistribution's bucket_index, which
  // deliberately groups by the raw expression) — Hibernate compiles each of :interval's three
  // occurrences into its own separate JDBC "?" placeholder, so even though all three are bound to
  // the identical runtime value, Postgres's planner sees three syntactically distinct,
  // independently-unbound parameters at parse time and can't prove the SELECT list and GROUP BY
  // expressions are the same, failing with "column fs.scored_at must appear in the GROUP BY
  // clause" (confirmed against a real Postgres 17 instance via the Testcontainers integration
  // test). Grouping/ordering by the SELECT list's own output alias sidesteps the problem
  // entirely by only ever binding :interval once.
  @Query(
      value =
          """
          SELECT
            date_trunc(:interval, fs.scored_at) AS "bucketTimestamp",
            AVG(fs.ensemble_score) AS "avgScore"
          FROM fraud_scores fs
          WHERE fs.tenant_id = :tenantId
          GROUP BY "bucketTimestamp"
          ORDER BY "bucketTimestamp"
          """,
      nativeQuery = true)
  List<TimelineBucketProjection> scoreTimeline(
      @Param("tenantId") String tenantId, @Param("interval") String interval);
}
