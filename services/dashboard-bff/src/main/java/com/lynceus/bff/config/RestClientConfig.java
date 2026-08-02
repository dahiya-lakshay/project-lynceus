package com.lynceus.bff.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Provides the {@link RestClient} used by {@code TransactionClient} to call the Transaction
 * Service's {@code /api/v1/transactions/*} endpoints. Identical timeout shape to {@code
 * transaction-service}'s {@code RestClientConfig} (which configures its own {@code RestClient} for
 * calling the inference service) — 2s connect / 10s read, fixed here on the request factory so
 * every caller of this bean gets the same bound automatically.
 *
 * <p>Connect timeout is short (2s) — a hung TCP handshake to the transaction service should fail
 * fast rather than blocking a dashboard request. Read timeout is longer (10s) to allow for a slow
 * aggregate query over the tenant's full transaction set (see {@code
 * TransactionRepository.aggregateStats} and friends on the transaction-service side) without being
 * needlessly aggressive about it.
 */
@Configuration
public class RestClientConfig {

  private static final int CONNECT_TIMEOUT_MS = 2_000;
  private static final int READ_TIMEOUT_MS = 10_000;

  private final String transactionServiceBaseUrl;

  public RestClientConfig(
      @Value("${lynceus.transaction-service.url}") String transactionServiceBaseUrl) {
    this.transactionServiceBaseUrl = transactionServiceBaseUrl;
  }

  @Bean
  public RestClient transactionServiceRestClient() {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
    requestFactory.setReadTimeout(READ_TIMEOUT_MS);

    return RestClient.builder()
        .baseUrl(transactionServiceBaseUrl)
        .requestFactory(requestFactory)
        .build();
  }
}
