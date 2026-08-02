package com.lynceus.transaction.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Provides the {@link RestClient} used by {@code InferenceClient} to call the ML inference service
 * synchronously (see {@code TransactionService.create} for why this is synchronous in Phase 1).
 * Timeouts are fixed here on the client/request-factory rather than per-call so every caller of
 * this bean gets the same bound automatically instead of having to remember to set one.
 *
 * <p>Connect timeout is short (2s) — a hung TCP handshake to the inference service should fail fast
 * so the transaction write path isn't blocked. Read timeout is longer (10s) to allow for actual
 * model inference latency once the request is in flight.
 */
@Configuration
public class RestClientConfig {

  private static final int CONNECT_TIMEOUT_MS = 2_000;
  private static final int READ_TIMEOUT_MS = 10_000;

  private final String inferenceServiceBaseUrl;

  public RestClientConfig(
      @Value("${lynceus.inference-service.url}") String inferenceServiceBaseUrl) {
    this.inferenceServiceBaseUrl = inferenceServiceBaseUrl;
  }

  @Bean
  public RestClient inferenceRestClient() {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
    requestFactory.setReadTimeout(READ_TIMEOUT_MS);

    return RestClient.builder()
        .baseUrl(inferenceServiceBaseUrl)
        .requestFactory(requestFactory)
        .build();
  }
}
