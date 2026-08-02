package com.lynceus.shared.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Fraud score computed synchronously for a single transaction. Mirrors the {@code
 * ScoreTransactionResponse} schema in {@code api-specs/shared/schemas/fraud-score.yaml}.
 *
 * @param transactionId identifier of the transaction that was scored
 * @param tenantId identifier of the owning tenant this score was computed for
 * @param isolationForestScore anomaly probability from the Isolation Forest model
 * @param ensembleScore combined fraud probability derived from all contributing models
 * @param riskLevel risk tier derived from the ensemble score
 * @param modelVersion version identifier of the model ensemble used to produce this score
 * @param featureVector engineered feature values used as model input, for auditability
 * @param scoredAt timestamp the transaction was scored
 */
public record ScoreTransactionResponse(
    @JsonProperty("transaction_id") UUID transactionId,
    @JsonProperty("tenant_id") String tenantId,
    @JsonProperty("isolation_forest_score") double isolationForestScore,
    @JsonProperty("ensemble_score") double ensembleScore,
    @JsonProperty("risk_level") String riskLevel,
    @JsonProperty("model_version") String modelVersion,
    @JsonProperty("feature_vector") Map<String, Object> featureVector,
    @JsonProperty("scored_at") Instant scoredAt) {}
