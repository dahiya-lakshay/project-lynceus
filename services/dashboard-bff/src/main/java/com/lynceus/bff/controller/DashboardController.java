package com.lynceus.bff.controller;

import com.lynceus.bff.model.dto.FraudDistributionResponse;
import com.lynceus.bff.model.dto.OverviewResponse;
import com.lynceus.bff.model.dto.RiskBreakdownResponse;
import com.lynceus.bff.model.dto.TimelineResponse;
import com.lynceus.bff.service.DashboardService;
import com.lynceus.shared.dto.PagedResponse;
import com.lynceus.shared.dto.TransactionSummaryDto;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST layer for the fraud analyst dashboard's aggregated views — all business logic lives in
 * {@link DashboardService} (AGENTS.md convention: controllers validate input, call the service, and
 * return the response, nothing more).
 *
 * <p>Like {@code transaction-service}'s {@code TransactionController}, none of these endpoints
 * re-declares {@code @RequestHeader("X-Tenant-Id")} — {@code TenantFilter} (registered in {@code
 * WebConfig}) already extracts it into {@code TenantContext} for every request under {@code
 * /api/*}, defaulting to {@code "default"} when absent.
 *
 * <p>{@code risk-breakdown} and {@code timeline} unwrap {@link DashboardService}'s wrapper response
 * records ({@code RiskBreakdownResponse}/{@code TimelineResponse}) into bare JSON arrays here,
 * matching {@code api-specs/dashboard-bff-api.yaml}'s documented response shape for those two
 * endpoints exactly — the wrapper only exists on the service/cache side (see those DTOs' Javadoc).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/dashboard")
@Validated
public class DashboardController {

  private final DashboardService dashboardService;

  public DashboardController(DashboardService dashboardService) {
    this.dashboardService = dashboardService;
  }

  @GetMapping("/overview")
  public ResponseEntity<OverviewResponse> overview() {
    return ResponseEntity.ok(dashboardService.overview());
  }

  @GetMapping("/transactions/recent")
  public ResponseEntity<PagedResponse<TransactionSummaryDto>> recentTransactions(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize) {
    return ResponseEntity.ok(dashboardService.recentTransactions(page, pageSize));
  }

  @GetMapping("/fraud-distribution")
  public ResponseEntity<FraudDistributionResponse> fraudDistribution() {
    return ResponseEntity.ok(dashboardService.fraudDistribution());
  }

  @GetMapping("/risk-breakdown")
  public ResponseEntity<List<RiskBreakdownResponse.Entry>> riskBreakdown() {
    return ResponseEntity.ok(dashboardService.riskBreakdown().entries());
  }

  @GetMapping("/timeline")
  public ResponseEntity<List<TimelineResponse.Point>> timeline() {
    return ResponseEntity.ok(dashboardService.timeline().points());
  }
}
