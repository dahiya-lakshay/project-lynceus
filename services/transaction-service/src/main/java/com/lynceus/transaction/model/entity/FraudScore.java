package com.lynceus.transaction.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
 * Persisted fraud-scoring result for a {@link Transaction}, produced by the inference service. Maps
 * exactly to the {@code fraud_scores} table defined in {@code
 * infrastructure/db/migrations/changelogs/20260802-02-create-fraud-scores-table.yaml}.
 *
 * <p>Deliberately does not use Lombok's {@code @Data} (see CLAUDE.md's "DON'T" list) — see {@link
 * Transaction}'s class Javadoc for why. No {@code @ToString}/{@code @EqualsAndHashCode} either,
 * which also sidesteps a recursive-reference concern were {@link Transaction} ever given a
 * back-reference collection to its scores.
 */
@Entity
@Table(name = "fraud_scores")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FraudScore {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false, length = 50)
  private String tenantId;

  // onDelete deliberately left at Postgres's default NO ACTION at the DB level (see the
  // migration's comment) — fraud_scores is an auditable record, a deleted transaction must
  // never silently cascade-delete the score computed against it. FetchType.LAZY so listing
  // transactions never accidentally drags the full Transaction graph along with each score.
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "transaction_id", nullable = false)
  private Transaction transaction;

  @Column(name = "isolation_forest_score", nullable = false, precision = 5, scale = 4)
  private BigDecimal isolationForestScore;

  // NULLABLE — Phase 1 only trains the Isolation Forest model; filled in once the
  // autoencoder model ships in Phase 2.
  @Column(name = "autoencoder_score", precision = 5, scale = 4)
  private BigDecimal autoencoderScore;

  @Column(name = "ensemble_score", nullable = false, precision = 5, scale = 4)
  private BigDecimal ensembleScore;

  // 'low', 'medium', 'high', 'critical' — see the migration's column comment.
  @Column(name = "risk_level", nullable = false, length = 20)
  private String riskLevel;

  @Column(name = "model_version", nullable = false, length = 50)
  private String modelVersion;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "feature_vector", nullable = false, columnDefinition = "jsonb")
  private Map<String, Object> featureVector;

  // NULLABLE — SHAP values, added in Phase 3.
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "explanation", columnDefinition = "jsonb")
  private Map<String, Object> explanation;

  @Column(name = "scored_at", nullable = false)
  private Instant scoredAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

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
