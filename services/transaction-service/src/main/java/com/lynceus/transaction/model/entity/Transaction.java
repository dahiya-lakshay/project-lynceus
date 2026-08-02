package com.lynceus.transaction.model.entity;

import com.lynceus.shared.dto.Channel;
import com.lynceus.shared.dto.MerchantCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A single financial transaction submitted for fraud scoring. Maps exactly to the {@code
 * transactions} table defined in {@code
 * infrastructure/db/migrations/changelogs/20260802-01-create-transactions-table.yaml}.
 *
 * <p>Deliberately does not use Lombok's {@code @Data} (see CLAUDE.md's "DON'T" list) —
 * {@code @Data} generates {@code equals}/{@code hashCode} over every field, which for a JPA entity
 * means pulling in lazy-loaded associations and producing identity semantics that break inside
 * collections/Hibernate's session cache. Entities here intentionally fall back to the JVM default
 * (reference) {@code equals}/{@code hashCode} instead.
 */
@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false, length = 50)
  private String tenantId;

  @Column(name = "customer_id", nullable = false)
  private UUID customerId;

  @Column(name = "amount", nullable = false, precision = 15, scale = 2)
  private BigDecimal amount;

  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Column(name = "merchant_name", nullable = false)
  private String merchantName;

  @Column(name = "merchant_category", nullable = false, length = 100)
  private MerchantCategory merchantCategory;

  @Column(name = "merchant_id", length = 100)
  private String merchantId;

  @Column(name = "location_lat", precision = 10, scale = 7)
  private BigDecimal locationLat;

  @Column(name = "location_lng", precision = 10, scale = 7)
  private BigDecimal locationLng;

  @Column(name = "country_code", length = 3)
  private String countryCode;

  @Column(name = "is_online", nullable = false)
  private boolean isOnline;

  @Column(name = "is_foreign", nullable = false)
  private boolean isForeign;

  @Column(name = "channel", nullable = false, length = 50)
  private Channel channel;

  @Column(name = "device_id", length = 100)
  private String deviceId;

  // Plain String rather than java.net.InetAddress, but explicitly typed via @JdbcTypeCode
  // (SqlTypes.INET) rather than left to Hibernate's default VARCHAR mapping: a plain
  // @Column(columnDefinition = "inet") still binds the JDBC parameter as VARCHAR, which
  // Postgres rejects even for a NULL value with "column ip_address is of type inet but
  // expression is of type character varying" — confirmed against a real Postgres 17 instance
  // via the Testcontainers integration test, since every INSERT includes every mapped column
  // regardless of whether the Java value is null. SqlTypes.INET (Hibernate 6.2+) binds it with
  // the correct PGobject/inet type instead. Never populated via the API in Phase 1
  // (CreateTransactionRequest has no ip_address field), so this column is always NULL through
  // the normal ingestion path — but it still has to bind correctly as NULL.
  @JdbcTypeCode(SqlTypes.INET)
  @Column(name = "ip_address", columnDefinition = "inet")
  private String ipAddress;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata", columnDefinition = "jsonb")
  private Map<String, Object> metadata;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  // DB-generated (GENERATED ALWAYS AS ... STORED) — Postgres computes this on every
  // insert/update, so it must never be written by the application, only read back.
  @Column(name = "amount_bucket", insertable = false, updatable = false, length = 20)
  private String amountBucket;

  @PrePersist
  protected void onCreate() {
    Instant now = Instant.now();
    this.createdAt = now;
    this.updatedAt = now;
  }

  @PreUpdate
  protected void onUpdate() {
    this.updatedAt = Instant.now();
  }
}
