package com.lynceus.transaction.controller;

import com.lynceus.shared.dto.CreateTransactionRequest;
import com.lynceus.shared.dto.MerchantCategory;
import com.lynceus.shared.dto.PagedResponse;
import com.lynceus.shared.dto.RiskLevel;
import com.lynceus.shared.dto.TransactionDto;
import com.lynceus.shared.dto.TransactionSummaryDto;
import com.lynceus.transaction.service.TransactionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST layer for transaction ingestion and retrieval — all business logic lives in {@link
 * TransactionService} (AGENTS.md convention: controllers validate input, call the service, and
 * return the response, nothing more).
 *
 * <p>None of the three endpoints re-declares {@code @RequestHeader("X-Tenant-Id")}: {@code
 * TenantFilter} (registered in {@code WebConfig}) already extracts it into {@code TenantContext}
 * for every request under {@code /api/*}, defaulting to {@code "default"} when absent. Declaring it
 * again here as a required header would reintroduce a 400-on-missing-header behavior that
 * contradicts TenantFilter's deliberate default-tenant fallback, so the service layer is the single
 * source of truth for tenant resolution instead.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/transactions")
@Validated
public class TransactionController {

  private final TransactionService transactionService;

  public TransactionController(TransactionService transactionService) {
    this.transactionService = transactionService;
  }

  // Accepted (not Created): matches the API spec's documented async-accept semantics — Phase 1
  // happens to execute scoring synchronously before responding, but the response contract is
  // written for Phase 2's real async Kafka flow, where risk_level/fraud_score genuinely won't
  // be known yet at response time.
  @PostMapping
  public ResponseEntity<TransactionDto> createTransaction(
      @Valid @RequestBody CreateTransactionRequest request) {
    TransactionDto created = transactionService.create(request);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(created);
  }

  @GetMapping("/{id}")
  public ResponseEntity<TransactionDto> getTransaction(@PathVariable UUID id) {
    return ResponseEntity.ok(transactionService.findById(id));
  }

  @GetMapping
  public ResponseEntity<PagedResponse<TransactionSummaryDto>> listTransactions(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize,
      @RequestParam(name = "risk_level", required = false) RiskLevel riskLevel,
      @RequestParam(name = "merchant_category", required = false) MerchantCategory merchantCategory,
      @RequestParam(name = "date_from", required = false) Instant dateFrom,
      @RequestParam(name = "date_to", required = false) Instant dateTo) {
    // API spec's `page` is 1-indexed; Spring Data's Pageable is 0-indexed. No Sort is attached
    // here — TransactionRepository.search's underlying query is native SQL with its own fixed
    // "ORDER BY created_at DESC" (see its Javadoc for why), so a Pageable-driven Sort would be
    // both redundant and unsupported by that query shape.
    Pageable pageable = PageRequest.of(page - 1, pageSize);
    PagedResponse<TransactionSummaryDto> response =
        transactionService.list(riskLevel, merchantCategory, dateFrom, dateTo, pageable);
    return ResponseEntity.ok(response);
  }
}
