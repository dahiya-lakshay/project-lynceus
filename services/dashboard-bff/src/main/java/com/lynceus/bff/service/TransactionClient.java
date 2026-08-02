package com.lynceus.bff.service;

import com.lynceus.shared.config.TenantFilter;
import com.lynceus.shared.dto.PagedResponse;
import com.lynceus.shared.dto.RiskLevelCount;
import com.lynceus.shared.dto.ScoreDistributionResponse;
import com.lynceus.shared.dto.TimelineSeriesPoint;
import com.lynceus.shared.dto.TransactionStatsResponse;
import com.lynceus.shared.dto.TransactionSummaryDto;
import com.lynceus.shared.exception.ServiceUnavailableException;
import java.util.List;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Calls transaction-service's {@code /api/v1/transactions/*} endpoints — both the aggregate {@code
 * /stats/*} endpoints backing most of the dashboard, and the plain list endpoint {@code
 * transactions/recent} delegates straight through to.
 *
 * <p>Deliberately the mirror image of {@code transaction-service}'s {@code InferenceClient} in how
 * it handles failure: {@code InferenceClient} catches every downstream failure and degrades
 * gracefully (an unscored transaction still beats a lost one). Here, every downstream failure is
 * instead wrapped in a {@link ServiceUnavailableException} and allowed to propagate — shared-lib's
 * {@code GlobalExceptionHandler} maps that to a 503 with the standard {@code ErrorResponse}
 * envelope. There is no honest "degraded" value for a dashboard KPI: silently returning 0
 * transactions, a flat score distribution, or an empty risk breakdown during a transaction-service
 * outage would look like a genuinely quiet, fraud-free tenant to an analyst, which is actively
 * misleading on a fraud detection platform. An explicit 503 is the more honest failure mode.
 */
@Slf4j
@Component
public class TransactionClient {

  private static final String STATS_OVERVIEW_PATH = "/api/v1/transactions/stats/overview";
  private static final String STATS_SCORE_DISTRIBUTION_PATH =
      "/api/v1/transactions/stats/score-distribution";
  private static final String STATS_RISK_BREAKDOWN_PATH =
      "/api/v1/transactions/stats/risk-breakdown";
  private static final String STATS_TIMELINE_PATH = "/api/v1/transactions/stats/timeline";
  private static final String TRANSACTIONS_PATH = "/api/v1/transactions";

  private final RestClient restClient;

  public TransactionClient(RestClient transactionServiceRestClient) {
    this.restClient = transactionServiceRestClient;
  }

  public TransactionStatsResponse getOverview(String tenantId) {
    return get(STATS_OVERVIEW_PATH, tenantId, spec -> spec.body(TransactionStatsResponse.class));
  }

  public ScoreDistributionResponse getScoreDistribution(String tenantId) {
    return get(
        STATS_SCORE_DISTRIBUTION_PATH,
        tenantId,
        spec -> spec.body(ScoreDistributionResponse.class));
  }

  public List<RiskLevelCount> getRiskBreakdown(String tenantId) {
    return get(
        STATS_RISK_BREAKDOWN_PATH,
        tenantId,
        spec -> spec.body(new ParameterizedTypeReference<List<RiskLevelCount>>() {}));
  }

  public List<TimelineSeriesPoint> getTimeline(String tenantId) {
    return get(
        STATS_TIMELINE_PATH,
        tenantId,
        spec -> spec.body(new ParameterizedTypeReference<List<TimelineSeriesPoint>>() {}));
  }

  // No risk_level/merchant_category/date_from/date_to forwarded here — the BFF's own
  // GET /api/v1/dashboard/transactions/recent endpoint only ever accepts page/page_size (see
  // api-specs/dashboard-bff-api.yaml), matching this being a plain pass-through with no
  // BFF-side filtering logic of its own.
  public PagedResponse<TransactionSummaryDto> getRecentTransactions(
      String tenantId, int page, int pageSize) {
    String uri =
        UriComponentsBuilder.fromPath(TRANSACTIONS_PATH)
            .queryParam("page", page)
            .queryParam("page_size", pageSize)
            .build()
            .toUriString();
    return get(
        uri,
        tenantId,
        spec ->
            spec.body(new ParameterizedTypeReference<PagedResponse<TransactionSummaryDto>>() {}));
  }

  private <T> T get(String path, String tenantId, Function<RestClient.ResponseSpec, T> extractor) {
    try {
      T result =
          extractor.apply(
              restClient.get().uri(path).header(TenantFilter.TENANT_HEADER, tenantId).retrieve());
      if (result == null) {
        // Shouldn't happen for a 2xx response on any of these endpoints (all return either an
        // object or a JSON array, never a genuinely empty body) — treated the same as any other
        // unexpected response shape from transaction-service.
        throw new ServiceUnavailableException(
            "Transaction service returned an empty response body for " + path);
      }
      return result;
    } catch (ResourceAccessException ex) {
      // Connection refused, DNS failure, or connect/read timeout — the transaction service is
      // unreachable or too slow.
      log.error(
          "Transaction service unreachable calling {} for tenant {}: {}",
          path,
          tenantId,
          ex.getMessage());
      throw new ServiceUnavailableException("Transaction service is unreachable", ex);
    } catch (HttpServerErrorException ex) {
      // 5xx from the transaction service.
      log.error(
          "Transaction service returned {} calling {} for tenant {}: {}",
          ex.getStatusCode(),
          path,
          tenantId,
          ex.getMessage());
      throw new ServiceUnavailableException("Transaction service returned an error", ex);
    } catch (RestClientException ex) {
      // Catch-all for any other failure talking to the transaction service (e.g. a 4xx
      // indicating our own request no longer matches its contract, or a response body that
      // doesn't deserialize into the expected shape).
      log.error(
          "Unexpected error calling transaction service at {} for tenant {}: {}",
          path,
          tenantId,
          ex.getMessage());
      throw new ServiceUnavailableException("Unexpected error calling transaction service", ex);
    }
  }
}
