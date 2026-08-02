package com.lynceus.transaction.service;

import com.lynceus.shared.dto.CreateTransactionRequest;
import com.lynceus.shared.dto.MerchantCategory;
import com.lynceus.shared.dto.PagedResponse;
import com.lynceus.shared.dto.RiskLevel;
import com.lynceus.shared.dto.ScoreTransactionRequest;
import com.lynceus.shared.dto.ScoreTransactionResponse;
import com.lynceus.shared.dto.TransactionDto;
import com.lynceus.shared.dto.TransactionSummaryDto;
import com.lynceus.shared.exception.ResourceNotFoundException;
import com.lynceus.shared.util.TenantContext;
import com.lynceus.transaction.model.entity.FraudScore;
import com.lynceus.transaction.model.entity.Transaction;
import com.lynceus.transaction.model.mapper.TransactionMapper;
import com.lynceus.transaction.repository.FraudScoreRepository;
import com.lynceus.transaction.repository.TransactionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Core business logic for ingesting and retrieving transactions. Controllers stay thin (AGENTS.md
 * convention) and delegate everything here.
 */
@Slf4j
@Service
public class TransactionService {

  private static final Duration CACHE_TTL = Duration.ofMinutes(5);
  private static final String CACHE_KEY_PREFIX = "txn:";

  // Mirrors the transactions.currency column's DB DEFAULT 'USD'. That DB default never
  // actually fires in practice: Hibernate always sends an explicit value for every mapped
  // column on INSERT, so a null here would hit the NOT NULL constraint instead of falling
  // back to the default. CreateTransactionRequest.currency is optional at the API layer, so
  // this is where that "optional at the edge, mandatory in the DB" gap gets closed.
  private static final String DEFAULT_CURRENCY = "USD";

  private final TransactionRepository transactionRepository;
  private final FraudScoreRepository fraudScoreRepository;
  private final InferenceClient inferenceClient;
  private final TransactionMapper transactionMapper;
  private final RedisTemplate<String, Object> redisTemplate;

  public TransactionService(
      TransactionRepository transactionRepository,
      FraudScoreRepository fraudScoreRepository,
      InferenceClient inferenceClient,
      TransactionMapper transactionMapper,
      RedisTemplate<String, Object> redisTemplate) {
    this.transactionRepository = transactionRepository;
    this.fraudScoreRepository = fraudScoreRepository;
    this.inferenceClient = inferenceClient;
    this.transactionMapper = transactionMapper;
    this.redisTemplate = redisTemplate;
  }

  // Phase 1: Synchronous scoring within the request. Phase 2 replaces this with Kafka event
  // publishing (fraud.scored topic) for true async processing — the transaction write and the
  // scoring call will no longer happen in the same request/transaction.
  //
  // Deliberately NOT @Transactional at this level: inferenceClient.score() is a blocking HTTP
  // call (2s connect / 10s read timeout — see RestClientConfig) sitting between the two writes
  // below. Wrapping the whole method in one transaction would hold a checked-out HikariCP
  // connection (and an open DB transaction) for up to ~12s per request; under load, or
  // whenever the inference service is slow/degraded, that exhausts the connection pool and
  // cascades into failures on completely unrelated endpoints, not just ingestion. Each
  // repository .save() below is transactional on its own (Spring Data's SimpleJpaRepository
  // methods are @Transactional per-method by default), so splitting the writes around the HTTP
  // call keeps every DB transaction short and scoped to a single insert.
  public TransactionDto create(CreateTransactionRequest request) {
    String tenantId = requireTenantId();

    Transaction transaction = transactionMapper.toEntity(request);
    transaction.setTenantId(tenantId);
    if (transaction.getCurrency() == null) {
      transaction.setCurrency(DEFAULT_CURRENCY);
    }
    transaction = transactionRepository.save(transaction);

    ScoreTransactionRequest scoreRequest =
        new ScoreTransactionRequest(
            transaction.getId(),
            tenantId,
            transaction.getCustomerId(),
            transaction.getAmount(),
            transaction.getMerchantCategory(),
            transaction.isOnline(),
            transaction.isForeign(),
            transaction.getChannel(),
            transaction.getCreatedAt());

    // No open transaction/connection is held across this call — see the Javadoc above.
    FraudScore fraudScore = null;
    Optional<ScoreTransactionResponse> scoreResponse =
        inferenceClient.score(scoreRequest, tenantId);
    if (scoreResponse.isPresent()) {
      fraudScore = transactionMapper.toEntity(scoreResponse.get());
      fraudScore.setTenantId(tenantId);
      fraudScore.setTransaction(transaction);
      fraudScore = fraudScoreRepository.save(fraudScore);
    } else {
      // Graceful degradation: the transaction is already persisted and is still returned to
      // the caller below, just without risk_level/fraud_score populated.
      log.warn(
          "Transaction {} for tenant {} persisted without a fraud score; inference service was"
              + " unreachable or returned an error",
          transaction.getId(),
          tenantId);
    }

    TransactionDto dto = transactionMapper.toDto(transaction, fraudScore);
    cache(tenantId, dto);
    return dto;
  }

  public TransactionDto findById(UUID id) {
    String tenantId = requireTenantId();
    String cacheKey = cacheKey(tenantId, id);

    Object cached = redisTemplate.opsForValue().get(cacheKey);
    if (cached instanceof TransactionDto dto) {
      return dto;
    }

    Transaction transaction =
        transactionRepository
            .findByTenantIdAndId(tenantId, id)
            .orElseThrow(() -> new ResourceNotFoundException("Transaction " + id + " not found"));
    FraudScore fraudScore =
        fraudScoreRepository.findByTenantIdAndTransactionId(tenantId, id).orElse(null);

    TransactionDto dto = transactionMapper.toDto(transaction, fraudScore);
    cache(tenantId, dto);
    return dto;
  }

  public PagedResponse<TransactionSummaryDto> list(
      RiskLevel riskLevel,
      MerchantCategory merchantCategory,
      Instant dateFrom,
      Instant dateTo,
      Pageable pageable) {
    String tenantId = requireTenantId();

    // The repository's search query is native SQL (see TransactionRepository for why), which
    // doesn't apply JPA AttributeConverters to bind parameters — pass each enum's own wire
    // value through directly rather than duplicating that mapping here.
    String riskLevelValue = riskLevel == null ? null : riskLevel.wireValue();
    String merchantCategoryValue = merchantCategory == null ? null : merchantCategory.wireValue();
    Page<Transaction> page =
        transactionRepository.search(
            tenantId, riskLevelValue, merchantCategoryValue, dateFrom, dateTo, pageable);

    List<UUID> transactionIds = page.getContent().stream().map(Transaction::getId).toList();
    // Batched lookup instead of one fraud-score query per row, to avoid N+1 queries when
    // rendering a page of summaries.
    Map<UUID, FraudScore> scoresByTransactionId =
        fraudScoreRepository.findByTenantIdAndTransactionIdIn(tenantId, transactionIds).stream()
            .collect(Collectors.toMap(fs -> fs.getTransaction().getId(), fs -> fs));

    List<TransactionSummaryDto> items =
        page.getContent().stream()
            .map(t -> transactionMapper.toSummaryDto(t, scoresByTransactionId.get(t.getId())))
            .toList();

    return new PagedResponse<>(
        items, (int) page.getTotalElements(), pageable.getPageNumber() + 1, pageable.getPageSize());
  }

  private void cache(String tenantId, TransactionDto dto) {
    redisTemplate.opsForValue().set(cacheKey(tenantId, dto.id()), dto, CACHE_TTL);
  }

  private String cacheKey(String tenantId, UUID id) {
    return CACHE_KEY_PREFIX + tenantId + ":" + id;
  }

  private String requireTenantId() {
    String tenantId = TenantContext.get();
    if (tenantId == null) {
      // TenantFilter (registered in WebConfig) always populates this, defaulting to
      // "default" when the X-Tenant-Id header is absent. A null here means the filter isn't
      // wired up at all — a startup/config bug, not something a caller triggered.
      throw new IllegalStateException("TenantContext not populated; is TenantFilter registered?");
    }
    return tenantId;
  }
}
