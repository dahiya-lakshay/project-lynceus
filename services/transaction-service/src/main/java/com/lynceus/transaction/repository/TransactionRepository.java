package com.lynceus.transaction.repository;

import com.lynceus.transaction.model.entity.Transaction;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every query here is scoped by {@code tenantId} (AGENTS.md's hard multi-tenancy rule — a
 * transaction-service reader must never be able to see another tenant's transactions, even
 * accidentally via a missing WHERE clause).
 */
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

  Optional<Transaction> findByTenantIdAndId(String tenantId, UUID id);

  Page<Transaction> findAllByTenantId(String tenantId, Pageable pageable);

  // risk_level lives on FraudScore, not Transaction, so filtering by it requires a join. The
  // "(:param IS NULL OR field = :param)" idiom keeps this a single query supporting any
  // combination of optional filters instead of a Specification/QueryDSL setup that Phase 1's
  // scope doesn't otherwise need — a LEFT JOIN so unscored transactions still show up in an
  // unfiltered listing.
  //
  // nativeQuery + explicit CAST(...) (rather than JPQL): Postgres's extended query protocol
  // can't infer a bind parameter's type when its only appearance on one branch of an "OR" is a
  // bare "? IS NULL" with nothing else in that expression to pin a type to — it fails at
  // execution time with "could not determine data type of parameter $N" for the
  // date_from/date_to params specifically (confirmed against a real Postgres 17 instance via
  // the Testcontainers integration test). Casting each parameter explicitly sidesteps that
  // ambiguity. ANSI CAST(:param AS type) is used instead of Postgres's terser "::type" — a
  // "::" glued directly onto a ":paramName" trips up Hibernate's own named-parameter tokenizer
  // (it misreads "::type" as part of the parameter token, failing with "No argument for named
  // parameter ':paramName::type'"), which CAST(...) sidesteps entirely since nothing follows
  // the parameter name but whitespace. merchantCategory is bound as its raw VARCHAR wire value
  // (see TransactionService) rather than the MerchantCategory enum, since native queries don't
  // apply entity attribute converters to bind parameters the way JPQL does.
  //
  // ORDER BY is fixed here (not driven by the passed Pageable's Sort) — native queries don't
  // reliably support Spring Data's automatic Sort-to-SQL translation, and Phase 1 only ever
  // needs "most recent first" for this endpoint (see TransactionController).
  @Query(
      value =
          """
          SELECT t.* FROM transactions t
          LEFT JOIN fraud_scores fs ON fs.transaction_id = t.id
          WHERE t.tenant_id = :tenantId
            AND (CAST(:riskLevel AS varchar) IS NULL OR fs.risk_level = :riskLevel)
            AND (CAST(:merchantCategory AS varchar) IS NULL OR t.merchant_category = :merchantCategory)
            AND (CAST(:dateFrom AS timestamptz) IS NULL OR t.created_at >= :dateFrom)
            AND (CAST(:dateTo AS timestamptz) IS NULL OR t.created_at <= :dateTo)
          ORDER BY t.created_at DESC
          """,
      countQuery =
          """
          SELECT count(*) FROM transactions t
          LEFT JOIN fraud_scores fs ON fs.transaction_id = t.id
          WHERE t.tenant_id = :tenantId
            AND (CAST(:riskLevel AS varchar) IS NULL OR fs.risk_level = :riskLevel)
            AND (CAST(:merchantCategory AS varchar) IS NULL OR t.merchant_category = :merchantCategory)
            AND (CAST(:dateFrom AS timestamptz) IS NULL OR t.created_at >= :dateFrom)
            AND (CAST(:dateTo AS timestamptz) IS NULL OR t.created_at <= :dateTo)
          """,
      nativeQuery = true)
  Page<Transaction> search(
      @Param("tenantId") String tenantId,
      @Param("riskLevel") String riskLevel,
      @Param("merchantCategory") String merchantCategory,
      @Param("dateFrom") Instant dateFrom,
      @Param("dateTo") Instant dateTo,
      Pageable pageable);
}
