package com.lynceus.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lynceus.shared.dto.Channel;
import com.lynceus.shared.dto.CreateTransactionRequest;
import com.lynceus.shared.dto.MerchantCategory;
import com.lynceus.shared.dto.ScoreTransactionResponse;
import com.lynceus.shared.dto.TransactionDto;
import com.lynceus.shared.exception.ResourceNotFoundException;
import com.lynceus.shared.util.TenantContext;
import com.lynceus.transaction.model.entity.FraudScore;
import com.lynceus.transaction.model.entity.Transaction;
import com.lynceus.transaction.model.mapper.TransactionMapperImpl;
import com.lynceus.transaction.repository.FraudScoreRepository;
import com.lynceus.transaction.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Unit tests for {@link TransactionService}, run with the repositories and {@link InferenceClient}
 * mocked. Uses the real MapStruct-generated {@link TransactionMapperImpl} rather than mocking it —
 * the mapping logic (especially the isOnline/isForeign property-name subtlety documented in {@code
 * TransactionMapper}) is exactly the kind of thing worth exercising for real instead of assuming it
 * away with a mock.
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

  private static final String TENANT_ID = "tenant-a";

  @Mock private TransactionRepository transactionRepository;
  @Mock private FraudScoreRepository fraudScoreRepository;
  @Mock private InferenceClient inferenceClient;

  @SuppressWarnings("unchecked")
  private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);

  private TransactionService transactionService;

  @BeforeEach
  void setUp() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    transactionService =
        new TransactionService(
            transactionRepository,
            fraudScoreRepository,
            inferenceClient,
            new TransactionMapperImpl(),
            redisTemplate);
    TenantContext.set(TENANT_ID);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void createTransaction_withScoringSuccess_persistsScoreAndReturnsRiskLevel() {
    CreateTransactionRequest request = validRequest();
    Transaction saved = savedTransactionFor(request);
    when(transactionRepository.save(any(Transaction.class))).thenReturn(saved);

    ScoreTransactionResponse scoreResponse =
        new ScoreTransactionResponse(
            saved.getId(),
            TENANT_ID,
            0.12,
            0.34,
            "medium",
            "isolation-forest-v1",
            Map.of("velocity_1h", 3),
            Instant.now());
    when(inferenceClient.score(any(), anyString())).thenReturn(Optional.of(scoreResponse));
    when(fraudScoreRepository.save(any(FraudScore.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    TransactionDto dto = transactionService.create(request);

    assertThat(dto.riskLevel()).isEqualTo("medium");
    assertThat(dto.fraudScore()).isEqualTo(0.34);

    ArgumentCaptor<FraudScore> fraudScoreCaptor = ArgumentCaptor.forClass(FraudScore.class);
    verify(fraudScoreRepository).save(fraudScoreCaptor.capture());
    assertThat(fraudScoreCaptor.getValue().getTenantId()).isEqualTo(TENANT_ID);
    assertThat(fraudScoreCaptor.getValue().getTransaction()).isSameAs(saved);

    verify(valueOperations).set(anyString(), any(TransactionDto.class), any());
  }

  @Test
  void createTransaction_withInferenceServiceUnreachable_persistsUnscoredTransaction() {
    CreateTransactionRequest request = validRequest();
    Transaction saved = savedTransactionFor(request);
    when(transactionRepository.save(any(Transaction.class))).thenReturn(saved);
    // Graceful degradation: InferenceClient itself never throws, it returns empty.
    when(inferenceClient.score(any(), anyString())).thenReturn(Optional.empty());

    TransactionDto dto = transactionService.create(request);

    assertThat(dto.riskLevel()).isNull();
    assertThat(dto.fraudScore()).isNull();
    assertThat(dto.id()).isEqualTo(saved.getId());
    verifyNoInteractions(fraudScoreRepository);
  }

  @Test
  void createTransaction_withCurrencyOmitted_defaultsToUsd() {
    CreateTransactionRequest request =
        new CreateTransactionRequest(
            UUID.randomUUID(),
            new BigDecimal("42.00"),
            null,
            "Test Merchant",
            MerchantCategory.GROCERY,
            true,
            false,
            Channel.ONLINE,
            null);
    when(transactionRepository.save(any(Transaction.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(inferenceClient.score(any(), anyString())).thenReturn(Optional.empty());

    transactionService.create(request);

    ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
    verify(transactionRepository).save(captor.capture());
    assertThat(captor.getValue().getCurrency()).isEqualTo("USD");
  }

  @Test
  void findById_withCacheHit_returnsCachedDtoWithoutHittingRepository() {
    UUID id = UUID.randomUUID();
    TransactionDto cached =
        new TransactionDto(
            id,
            TENANT_ID,
            UUID.randomUUID(),
            new BigDecimal("10.00"),
            "USD",
            "Cached Merchant",
            MerchantCategory.GROCERY,
            true,
            false,
            Channel.ONLINE,
            null,
            "low",
            0.05,
            Instant.now(),
            Instant.now());
    when(valueOperations.get(anyString())).thenReturn(cached);

    TransactionDto dto = transactionService.findById(id);

    assertThat(dto).isEqualTo(cached);
    verifyNoInteractions(transactionRepository);
  }

  @Test
  void findById_withUnknownId_throwsResourceNotFoundException() {
    UUID id = UUID.randomUUID();
    when(valueOperations.get(anyString())).thenReturn(null);
    when(transactionRepository.findByTenantIdAndId(TENANT_ID, id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> transactionService.findById(id))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  private CreateTransactionRequest validRequest() {
    return new CreateTransactionRequest(
        UUID.randomUUID(),
        new BigDecimal("150.00"),
        "USD",
        "Test Store",
        MerchantCategory.ELECTRONICS,
        true,
        false,
        Channel.ONLINE,
        Map.of());
  }

  private Transaction savedTransactionFor(CreateTransactionRequest request) {
    Instant now = Instant.now();
    return Transaction.builder()
        .id(UUID.randomUUID())
        .tenantId(TENANT_ID)
        .customerId(request.customerId())
        .amount(request.amount())
        .currency(request.currency())
        .merchantName(request.merchantName())
        .merchantCategory(request.merchantCategory())
        .isOnline(request.isOnline())
        .isForeign(request.isForeign())
        .channel(request.channel())
        .metadata(request.metadata())
        .createdAt(now)
        .updatedAt(now)
        .build();
  }
}
