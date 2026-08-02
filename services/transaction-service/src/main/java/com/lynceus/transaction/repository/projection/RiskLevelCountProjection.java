package com.lynceus.transaction.repository.projection;

/**
 * Interface-based projection for {@code FraudScoreRepository.riskBreakdown}'s native aggregate
 * query.
 */
public interface RiskLevelCountProjection {

  String getRiskLevel();

  long getRiskCount();
}
