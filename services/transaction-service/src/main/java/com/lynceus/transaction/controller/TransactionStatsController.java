package com.lynceus.transaction.controller;

import com.lynceus.shared.dto.RiskLevelCount;
import com.lynceus.shared.dto.ScoreDistributionResponse;
import com.lynceus.shared.dto.TimelineSeriesPoint;
import com.lynceus.shared.dto.TransactionStatsResponse;
import com.lynceus.transaction.model.dto.TimeInterval;
import com.lynceus.transaction.service.TransactionStatsService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Aggregate KPI/chart endpoints backing the Dashboard BFF (Task 8). Thin per AGENTS.md's convention
 * — all aggregation happens in {@link TransactionStatsService} and, below it, real SQL {@code
 * COUNT}/{@code AVG}/{@code SUM}/{@code GROUP BY} in {@code TransactionRepository}/{@code
 * FraudScoreRepository}, not here and not client-side in the BFF (see the architectural rationale
 * in {@code api-specs/transaction-api.yaml}'s comment on this path group).
 *
 * <p>Deliberately a separate controller from {@link TransactionController} rather than adding
 * methods to it: same reasoning as {@link TransactionStatsService} vs {@link
 * com.lynceus.transaction.service.TransactionService} — distinct concern, zero risk of touching the
 * already-reviewed ingestion/retrieval controller.
 *
 * <p>Like {@link TransactionController}, none of these endpoints re-declares
 * {@code @RequestHeader("X-Tenant-Id")} — {@code TenantFilter} already populates {@code
 * TenantContext} for every request under {@code /api/*}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/transactions/stats")
public class TransactionStatsController {

  private final TransactionStatsService transactionStatsService;

  public TransactionStatsController(TransactionStatsService transactionStatsService) {
    this.transactionStatsService = transactionStatsService;
  }

  @GetMapping("/overview")
  public ResponseEntity<TransactionStatsResponse> overview() {
    return ResponseEntity.ok(transactionStatsService.overview());
  }

  @GetMapping("/score-distribution")
  public ResponseEntity<ScoreDistributionResponse> scoreDistribution() {
    return ResponseEntity.ok(transactionStatsService.scoreDistribution());
  }

  @GetMapping("/risk-breakdown")
  public ResponseEntity<List<RiskLevelCount>> riskBreakdown() {
    return ResponseEntity.ok(transactionStatsService.riskBreakdown());
  }

  // interval isn't @RequestParam(required = true)-declared as TimeInterval directly bound with a
  // default here on purpose: Spring's Enum conversion runs before this method is invoked either
  // way, so `defaultValue = "day"` is sufficient — an invalid value still fails type conversion
  // and is handled as a 400 by TransactionExceptionHandler.handleTypeMismatch, exactly like
  // risk_level/merchant_category on TransactionController.listTransactions.
  @GetMapping("/timeline")
  public ResponseEntity<List<TimelineSeriesPoint>> timeline(
      @RequestParam(defaultValue = "day") TimeInterval interval) {
    return ResponseEntity.ok(transactionStatsService.timeline(interval));
  }
}
