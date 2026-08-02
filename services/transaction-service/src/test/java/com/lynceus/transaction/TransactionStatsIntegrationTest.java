package com.lynceus.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.lynceus.shared.dto.Channel;
import com.lynceus.shared.dto.MerchantCategory;
import com.lynceus.shared.dto.RiskLevelCount;
import com.lynceus.shared.dto.ScoreDistributionResponse;
import com.lynceus.shared.dto.TimelineSeriesPoint;
import com.lynceus.shared.dto.TransactionStatsResponse;
import com.lynceus.transaction.model.entity.FraudScore;
import com.lynceus.transaction.model.entity.Transaction;
import com.lynceus.transaction.repository.FraudScoreRepository;
import com.lynceus.transaction.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Full-stack integration test (real Postgres via Testcontainers, same container setup as {@code
 * TransactionServiceIntegrationTest}) for the {@code /api/v1/transactions/stats/*} endpoints added
 * by Task 8 (Dashboard BFF). Unlike that class, this one inserts {@link Transaction}/{@link
 * FraudScore} rows directly via the JPA repositories rather than through the HTTP ingestion
 * endpoint — the point here is to prove the native SQL aggregation in {@code TransactionRepository}
 * / {@code FraudScoreRepository} produces exactly the right numbers against known, hand-placed
 * data, and that it's correctly scoped per tenant; it isn't exercising the synchronous-scoring
 * ingestion flow that class already covers. No inference-service stub is needed as a result.
 *
 * <p>Every test method generates its own fresh, random tenant ID (via {@link #uniqueTenantId})
 * rather than sharing a fixed constant across the class — the Postgres container is {@code static}
 * and therefore shared across every test method in this class (starting it per-method would be far
 * slower), so a fixed tenant ID would silently accumulate rows across methods and make each test's
 * assertions depend on run order. {@code TransactionServiceIntegrationTest} follows the same
 * per-method-distinct-tenant convention for the same reason.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionStatsIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("lynceus")
          .withUsername("lynceus")
          .withPassword("lynceus_test_password");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private TransactionRepository transactionRepository;
  @Autowired private FraudScoreRepository fraudScoreRepository;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    registry.add("spring.data.redis.password", () -> "");
    // Never actually called in this test (no InferenceClient interaction), but the property is
    // required for the application context to start.
    registry.add("lynceus.inference-service.url", () -> "http://localhost:1");
  }

  @Test
  void overview_aggregatesOnlyTheRequestingTenantsTransactions() {
    String tenantA = uniqueTenantId("stats-tenant-a");
    String tenantB = uniqueTenantId("stats-tenant-b");
    seedFullDataset(tenantA);
    seedScoredTransaction(tenantB, new BigDecimal("50.00"), new BigDecimal("0.5000"), "medium");

    TransactionStatsResponse stats = getOverview(tenantA);
    assertThat(stats.totalTransactions()).isEqualTo(5);
    assertThat(stats.averageScore()).isEqualTo(0.6); // avg(0.05, 0.55, 0.85, 0.95)
    assertThat(stats.flaggedCount()).isEqualTo(2); // high + critical
    assertThat(stats.fraudRate()).isEqualTo(0.4); // 2 / 5
    assertThat(stats.totalAmountProcessed()).isEqualByComparingTo(new BigDecimal("1500.00"));

    TransactionStatsResponse tenantBStats = getOverview(tenantB);
    assertThat(tenantBStats.totalTransactions()).isEqualTo(1);
    assertThat(tenantBStats.totalAmountProcessed()).isEqualByComparingTo(new BigDecimal("50.00"));
  }

  @Test
  void overview_forTenantWithNoTransactions_returnsAllZeroesNotNulls() {
    TransactionStatsResponse stats = getOverview(uniqueTenantId("stats-tenant-empty"));

    assertThat(stats.totalTransactions()).isZero();
    assertThat(stats.averageScore()).isZero();
    assertThat(stats.flaggedCount()).isZero();
    assertThat(stats.fraudRate()).isZero();
    assertThat(stats.totalAmountProcessed()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void scoreDistribution_returnsAllTenBucketsWithCorrectCounts() {
    String tenantId = uniqueTenantId("stats-tenant-dist");
    seedFullDataset(tenantId);

    ResponseEntity<ScoreDistributionResponse> response =
        exchange(
            "/api/v1/transactions/stats/score-distribution",
            tenantId,
            new ParameterizedTypeReference<ScoreDistributionResponse>() {});

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<ScoreDistributionResponse.Bucket> buckets = response.getBody().buckets();
    assertThat(buckets).hasSize(10);
    assertThat(buckets)
        .extracting(
            ScoreDistributionResponse.Bucket::range, ScoreDistributionResponse.Bucket::count)
        .containsExactly(
            tuple("0.0-0.1", 1L),
            tuple("0.1-0.2", 0L),
            tuple("0.2-0.3", 0L),
            tuple("0.3-0.4", 0L),
            tuple("0.4-0.5", 0L),
            tuple("0.5-0.6", 1L),
            tuple("0.6-0.7", 0L),
            tuple("0.7-0.8", 0L),
            tuple("0.8-0.9", 1L),
            tuple("0.9-1.0", 1L));
  }

  @Test
  void riskBreakdown_returnsOneEntryPerScoredRiskLevel() {
    String tenantId = uniqueTenantId("stats-tenant-risk");
    seedFullDataset(tenantId);

    ResponseEntity<List<RiskLevelCount>> response =
        exchange(
            "/api/v1/transactions/stats/risk-breakdown",
            tenantId,
            new ParameterizedTypeReference<List<RiskLevelCount>>() {});

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).extracting(RiskLevelCount::count).containsOnly(1L);
    assertThat(response.getBody()).hasSize(4);
  }

  @Test
  void timeline_averagesScoresWithinTheSameDayBucket() {
    String tenantId = uniqueTenantId("stats-tenant-timeline");
    seedFullDataset(tenantId);

    ResponseEntity<List<TimelineSeriesPoint>> response =
        exchange(
            "/api/v1/transactions/stats/timeline",
            tenantId,
            new ParameterizedTypeReference<List<TimelineSeriesPoint>>() {});

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    // All four scored transactions were seeded with scored_at = now, so day-bucketing (the
    // default) must collapse them into exactly one point averaging all four scores.
    assertThat(response.getBody()).hasSize(1);
    assertThat(response.getBody().get(0).averageScore()).isEqualTo(0.6);
  }

  @Test
  void tenantIsolation_riskBreakdownForTenantBDoesNotIncludeTenantAsData() {
    String tenantA = uniqueTenantId("stats-tenant-iso-a");
    String tenantB = uniqueTenantId("stats-tenant-iso-b");
    seedFullDataset(tenantA);
    seedScoredTransaction(tenantB, new BigDecimal("50.00"), new BigDecimal("0.5000"), "medium");

    ResponseEntity<List<RiskLevelCount>> response =
        exchange(
            "/api/v1/transactions/stats/risk-breakdown",
            tenantB,
            new ParameterizedTypeReference<List<RiskLevelCount>>() {});

    assertThat(response.getBody()).hasSize(1);
    assertThat(response.getBody().get(0).count()).isEqualTo(1L);
  }

  // transactions.tenant_id is VARCHAR(50) (see the create-transactions-table changelog) — an
  // 8-character UUID suffix keeps every prefix used below comfortably under that limit, unlike a
  // full 36-character UUID.toString() which overflowed it for the longer prefixes.
  private static String uniqueTenantId(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  // Five transactions, four scored spanning all four risk levels plus one deliberately unscored
  // transaction — total_transactions/total_amount_processed must count it,
  // average_score/flagged_count/fraud_rate/the histogram/risk-breakdown/timeline must not.
  private void seedFullDataset(String tenantId) {
    seedScoredTransaction(tenantId, new BigDecimal("100.00"), new BigDecimal("0.0500"), "low");
    seedScoredTransaction(tenantId, new BigDecimal("200.00"), new BigDecimal("0.5500"), "medium");
    seedScoredTransaction(tenantId, new BigDecimal("300.00"), new BigDecimal("0.8500"), "high");
    seedScoredTransaction(tenantId, new BigDecimal("400.00"), new BigDecimal("0.9500"), "critical");
    seedUnscoredTransaction(tenantId, new BigDecimal("500.00"));
  }

  private TransactionStatsResponse getOverview(String tenantId) {
    ResponseEntity<TransactionStatsResponse> response =
        exchange(
            "/api/v1/transactions/stats/overview",
            tenantId,
            new ParameterizedTypeReference<TransactionStatsResponse>() {});
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return response.getBody();
  }

  private <T> ResponseEntity<T> exchange(
      String path, String tenantId, ParameterizedTypeReference<T> responseType) {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Tenant-Id", tenantId);
    return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), responseType);
  }

  private void seedScoredTransaction(
      String tenantId, BigDecimal amount, BigDecimal ensembleScore, String riskLevel) {
    Transaction transaction = seedUnscoredTransaction(tenantId, amount);
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    FraudScore fraudScore =
        FraudScore.builder()
            .tenantId(tenantId)
            .transaction(transaction)
            .isolationForestScore(ensembleScore)
            .ensembleScore(ensembleScore)
            .riskLevel(riskLevel)
            .modelVersion("isolation-forest-v1")
            .featureVector(Map.of("velocity_1h", 1))
            .scoredAt(now)
            .build();
    fraudScoreRepository.save(fraudScore);
  }

  private Transaction seedUnscoredTransaction(String tenantId, BigDecimal amount) {
    Instant now = Instant.now();
    Transaction transaction =
        Transaction.builder()
            .tenantId(tenantId)
            .customerId(UUID.randomUUID())
            .amount(amount)
            .currency("USD")
            .merchantName("Stats Test Merchant")
            .merchantCategory(MerchantCategory.ELECTRONICS)
            .isOnline(true)
            .isForeign(false)
            .channel(Channel.ONLINE)
            .metadata(Map.of())
            .createdAt(now)
            .updatedAt(now)
            .build();
    return transactionRepository.save(transaction);
  }
}
