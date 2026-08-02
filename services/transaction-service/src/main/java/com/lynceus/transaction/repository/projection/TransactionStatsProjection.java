package com.lynceus.transaction.repository.projection;

import java.math.BigDecimal;

/**
 * Interface-based projection for {@code TransactionRepository.aggregateStats}'s native aggregate
 * query. Every getter name here must match the query's quoted column alias exactly (see the query's
 * Javadoc for why aliases are quoted camelCase rather than relaxed-matched snake_case) — Spring
 * Data resolves each getter against the {@link java.sql.ResultSetMetaData} column label of the
 * native query's result set.
 */
public interface TransactionStatsProjection {

  long getTotalTransactions();

  double getAverageScore();

  long getFlaggedCount();

  BigDecimal getTotalAmountProcessed();
}
