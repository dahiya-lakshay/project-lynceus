package com.lynceus.shared.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Persisted fraud scoring result for a transaction, combining the Isolation Forest and Autoencoder
 * model outputs into an ensemble score. Mirrors the {@code FraudScore} schema in {@code
 * api-specs/shared/schemas/fraud-score.yaml}.
 *
 * @param id unique identifier of this scoring result
 * @param transactionId identifier of the transaction that was scored
 * @param tenantId identifier of the owning tenant (financial institution)
 * @param isolationForestScore anomaly probability from the Isolation Forest model
 * @param autoencoderScore reconstruction-error-derived anomaly probability from the Autoencoder
 *     model; null when the autoencoder model was not available at scoring time
 * @param ensembleScore combined fraud probability derived from all contributing models
 * @param riskLevel risk tier derived from the ensemble score
 * @param modelVersion version identifier of the model ensemble used to produce this score
 * @param featureVector engineered feature values used as model input, for auditability
 * @param explanation optional model explainability payload; null when not computed
 * @param scoredAt timestamp the transaction was scored
 */
public record FraudScoreDto(
    UUID id,
    @JsonProperty("transaction_id") UUID transactionId,
    @JsonProperty("tenant_id") String tenantId,
    @JsonProperty("isolation_forest_score") double isolationForestScore,
    @JsonProperty("autoencoder_score") Double autoencoderScore,
    @JsonProperty("ensemble_score") double ensembleScore,
    @JsonProperty("risk_level") String riskLevel,
    @JsonProperty("model_version") String modelVersion,
    @JsonProperty("feature_vector") Map<String, Object> featureVector,
    Map<String, Object> explanation,
    @JsonProperty("scored_at") Instant scoredAt) {}
