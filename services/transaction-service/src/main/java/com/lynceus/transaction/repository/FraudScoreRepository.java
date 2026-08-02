package com.lynceus.transaction.repository;

import com.lynceus.transaction.model.entity.FraudScore;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Not called out explicitly in the task's file tree (only {@code TransactionRepository} was), but
 * {@code fraud_scores} is a tenant-owned table in its own right (AGENTS.md's hard multi-tenancy
 * rule applies here too), and {@code TransactionService} needs a way to look up a transaction's
 * score — both for a single {@code findById} and, batched via {@link
 * #findByTenantIdAndTransactionIdIn}, to avoid an N+1 query per row when rendering a page of {@code
 * TransactionSummaryDto}s.
 */
public interface FraudScoreRepository extends JpaRepository<FraudScore, UUID> {

  Optional<FraudScore> findByTenantIdAndTransactionId(String tenantId, UUID transactionId);

  List<FraudScore> findByTenantIdAndTransactionIdIn(String tenantId, List<UUID> transactionIds);
}
