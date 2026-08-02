package com.lynceus.transaction.service;

import com.lynceus.shared.config.TenantFilter;
import com.lynceus.shared.dto.ScoreTransactionRequest;
import com.lynceus.shared.dto.ScoreTransactionResponse;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls the ML Inference Service synchronously to score a single transaction.
 *
 * <p>Phase 1: Synchronous scoring within the request. Phase 2 replaces this with Kafka event
 * publishing (fraud.scored topic) for true async processing, at which point this class's
 * responsibility moves from "call and wait" to "publish and forget."
 *
 * <p>Every failure mode (connection refused, timeout, 5xx / 503 model-not-loaded) is caught here
 * and turned into an empty {@link Optional} rather than propagated — a transaction must still be
 * persisted and returned to the caller even when scoring can't happen (graceful degradation).
 * {@code TransactionService} is responsible for deciding what "unscored" means to its callers.
 */
@Slf4j
@Component
public class InferenceClient {

  private static final String SCORE_PATH = "/api/v1/scoring/score";

  // Phase 1 stopgap, NOT a real credential: there is no Keycloak/JWT issuance yet in this
  // phase, and the inference service's own auth stub (require_bearer_token in
  // ml-services/inference-service/src/inference_service/api/deps.py) only checks that a
  // Bearer token is present and non-empty — it does not validate signature or claims. This
  // placeholder satisfies that stub without pretending to build a real JWT. Phase 2 replaces
  // it with a genuine service-to-service token once Keycloak client-credentials flow lands.
  private static final String PHASE_1_STUB_BEARER_TOKEN = "phase1-stopgap-token";

  private final RestClient inferenceRestClient;

  public InferenceClient(RestClient inferenceRestClient) {
    this.inferenceRestClient = inferenceRestClient;
  }

  public Optional<ScoreTransactionResponse> score(
      ScoreTransactionRequest request, String tenantId) {
    try {
      ScoreTransactionResponse response =
          inferenceRestClient
              .post()
              .uri(SCORE_PATH)
              .header(TenantFilter.TENANT_HEADER, tenantId)
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + PHASE_1_STUB_BEARER_TOKEN)
              .body(request)
              .retrieve()
              .body(ScoreTransactionResponse.class);
      return Optional.ofNullable(response);
    } catch (ResourceAccessException ex) {
      // Connection refused, DNS failure, or connect/read timeout — the inference service is
      // unreachable or too slow.
      log.warn(
          "Inference service unreachable while scoring transaction {} for tenant {}: {}",
          request.transactionId(),
          tenantId,
          ex.getMessage());
      return Optional.empty();
    } catch (HttpServerErrorException ex) {
      // 5xx from the inference service, including 503 when its model isn't loaded yet.
      log.warn(
          "Inference service returned {} while scoring transaction {} for tenant {}: {}",
          ex.getStatusCode(),
          request.transactionId(),
          tenantId,
          ex.getMessage());
      return Optional.empty();
    } catch (RestClientException ex) {
      // Catch-all for any other client-side failure talking to the inference service (e.g. a
      // 4xx indicating our own request no longer matches its contract). Still degrades
      // gracefully rather than failing the transaction write — an unscored transaction beats
      // a lost one.
      log.warn(
          "Unexpected error calling inference service for transaction {} for tenant {}: {}",
          request.transactionId(),
          tenantId,
          ex.getMessage());
      return Optional.empty();
    }
  }
}
