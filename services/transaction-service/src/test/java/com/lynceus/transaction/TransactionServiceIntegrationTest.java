package com.lynceus.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.lynceus.shared.dto.Channel;
import com.lynceus.shared.dto.CreateTransactionRequest;
import com.lynceus.shared.dto.MerchantCategory;
import com.lynceus.shared.dto.PagedResponse;
import com.lynceus.shared.dto.TransactionDto;
import com.lynceus.shared.dto.TransactionSummaryDto;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.RedisTemplate;
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
 * Full-stack integration test: real Postgres and Redis (via Testcontainers, not the dev-compose
 * instances), a real HTTP call to a stubbed inference service, exercised through the actual REST
 * API.
 *
 * <p>The inference service stub is a plain {@code com.sun.net.httpserver.HttpServer} rather than
 * WireMock or a Mockito-based double: it needs no new test dependency (the JDK ships it), and
 * routing an actual HTTP request through {@code InferenceClient}'s real {@code RestClient} bean
 * exercises the real network/timeout/deserialization path end-to-end, not just a
 * mocked-at-the-Spring-bean-level stand-in for it.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionServiceIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("lynceus")
          .withUsername("lynceus")
          .withPassword("lynceus_test_password");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private static HttpServer inferenceStub;
  private static final String STUB_RISK_LEVEL = "medium";
  private static final double STUB_ENSEMBLE_SCORE = 0.42;

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private RedisTemplate<String, Object> redisTemplate;

  @BeforeAll
  static void startInferenceStub() throws IOException {
    inferenceStub = HttpServer.create(new InetSocketAddress(0), 0);
    inferenceStub.createContext(
        "/api/v1/scoring/score", TransactionServiceIntegrationTest::handleScoreRequest);
    inferenceStub.start();
  }

  @AfterAll
  static void stopInferenceStub() {
    inferenceStub.stop(0);
  }

  private static void handleScoreRequest(HttpExchange exchange) throws IOException {
    // Drain the request body; its content isn't asserted on here, only that a well-formed
    // ScoreTransactionRequest was POSTed (a malformed one would have failed the caller's own
    // serialization before reaching this stub).
    exchange.getRequestBody().readAllBytes();

    UUID transactionId = newScoredTransactionId();
    String body =
        """
        {
          "transaction_id": "%s",
          "tenant_id": "%s",
          "isolation_forest_score": 0.55,
          "ensemble_score": %s,
          "risk_level": "%s",
          "model_version": "isolation-forest-v1",
          "feature_vector": {"velocity_1h": 2},
          "scored_at": "%s"
        }
        """
            .formatted(
                transactionId,
                exchange.getRequestHeaders().getFirst("X-Tenant-Id"),
                STUB_ENSEMBLE_SCORE,
                STUB_RISK_LEVEL,
                Instant.now());
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  // The stub doesn't actually need the transaction ID from the request body — it's simplest to
  // synthesize a fresh one for the response, since ScoreTransactionResponse.transactionId isn't
  // asserted on by any test below (riskLevel/fraudScore are). Kept as a named helper rather than
  // inlined to make that "we don't parse the request body" decision explicit.
  private static UUID newScoredTransactionId() {
    return UUID.randomUUID();
  }

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    // The redis:7-alpine image has no requirepass configured; overriding the dev-default
    // password here avoids Lettuce sending an AUTH the test container never asked for.
    registry.add("spring.data.redis.password", () -> "");
    registry.add(
        "lynceus.inference-service.url",
        () -> "http://localhost:" + inferenceStub.getAddress().getPort());
  }

  @Test
  void createTransaction_thenRetrieve_returnsScoredTransaction() {
    String tenantId = "tenant-integration-a";
    CreateTransactionRequest request =
        new CreateTransactionRequest(
            UUID.randomUUID(),
            new BigDecimal("250.00"),
            "USD",
            "Integration Test Store",
            MerchantCategory.ELECTRONICS,
            true,
            false,
            Channel.ONLINE,
            Map.of("note", "integration-test"));

    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Tenant-Id", tenantId);
    ResponseEntity<TransactionDto> createResponse =
        restTemplate.postForEntity(
            "/api/v1/transactions", new HttpEntity<>(request, headers), TransactionDto.class);

    assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    TransactionDto created = createResponse.getBody();
    assertThat(created).isNotNull();
    assertThat(created.riskLevel()).isEqualTo(STUB_RISK_LEVEL);
    assertThat(created.fraudScore()).isEqualTo(STUB_ENSEMBLE_SCORE);

    // The create path caches the combined result — assert the cache was actually populated,
    // not just that a second read happens to return the same thing.
    Object cached = redisTemplate.opsForValue().get("txn:" + tenantId + ":" + created.id());
    assertThat(cached).isInstanceOf(TransactionDto.class);

    ResponseEntity<TransactionDto> getResponse =
        restTemplate.exchange(
            "/api/v1/transactions/{id}",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            TransactionDto.class,
            created.id());

    assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(getResponse.getBody()).isNotNull();
    assertThat(getResponse.getBody().id()).isEqualTo(created.id());
    assertThat(getResponse.getBody().riskLevel()).isEqualTo(STUB_RISK_LEVEL);
  }

  @Test
  void listTransactions_returnsCreatedTransactionInSummary() {
    String tenantId = "tenant-integration-list";
    CreateTransactionRequest request =
        new CreateTransactionRequest(
            UUID.randomUUID(),
            new BigDecimal("75.50"),
            "USD",
            "List Test Store",
            MerchantCategory.GROCERY,
            false,
            false,
            Channel.IN_STORE,
            null);

    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Tenant-Id", tenantId);
    restTemplate.postForEntity(
        "/api/v1/transactions", new HttpEntity<>(request, headers), TransactionDto.class);

    ResponseEntity<PagedResponse<TransactionSummaryDto>> listResponse =
        restTemplate.exchange(
            "/api/v1/transactions",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            new org.springframework.core.ParameterizedTypeReference<>() {});

    assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    PagedResponse<TransactionSummaryDto> page = listResponse.getBody();
    assertThat(page).isNotNull();
    assertThat(page.items())
        .extracting(TransactionSummaryDto::merchantName)
        .contains("List Test Store");
  }

  @Test
  void tenantIsolation_transactionCreatedForOneTenant_notVisibleToAnotherTenant() {
    String tenantA = "tenant-isolation-a";
    String tenantB = "tenant-isolation-b";
    CreateTransactionRequest request =
        new CreateTransactionRequest(
            UUID.randomUUID(),
            new BigDecimal("500.00"),
            "USD",
            "Isolation Test Store",
            MerchantCategory.TRAVEL,
            true,
            true,
            Channel.ONLINE,
            null);

    HttpHeaders tenantAHeaders = new HttpHeaders();
    tenantAHeaders.set("X-Tenant-Id", tenantA);
    ResponseEntity<TransactionDto> createResponse =
        restTemplate.postForEntity(
            "/api/v1/transactions",
            new HttpEntity<>(request, tenantAHeaders),
            TransactionDto.class);
    UUID transactionId = createResponse.getBody().id();

    HttpHeaders tenantBHeaders = new HttpHeaders();
    tenantBHeaders.set("X-Tenant-Id", tenantB);
    ResponseEntity<String> getAsTenantB =
        restTemplate.exchange(
            "/api/v1/transactions/{id}",
            HttpMethod.GET,
            new HttpEntity<>(tenantBHeaders),
            String.class,
            transactionId);

    assertThat(getAsTenantB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    // Confirm tenant A can still see its own transaction — proves the 404 above is genuine
    // tenant isolation, not a broken create/get path in general.
    ResponseEntity<TransactionDto> getAsTenantA =
        restTemplate.exchange(
            "/api/v1/transactions/{id}",
            HttpMethod.GET,
            new HttpEntity<>(tenantAHeaders),
            TransactionDto.class,
            transactionId);
    assertThat(getAsTenantA.getStatusCode()).isEqualTo(HttpStatus.OK);
  }
}
