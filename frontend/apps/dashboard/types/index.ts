/**
 * TypeScript types mirroring api-specs/dashboard-bff-api.yaml and
 * api-specs/shared/errors.yaml field-for-field.
 *
 * Keys are kept in snake_case on purpose: the Dashboard BFF (Java/Spring
 * Boot) serializes its DTOs verbatim in snake_case (see
 * services/dashboard-bff), so camelCasing these types would silently break
 * JSON parsing at runtime — there is no mapping layer between the wire
 * format and these types. Per AGENTS.md, these are hand-maintained against
 * the OpenAPI spec rather than generated; if a code generator is introduced
 * later, it must be able to produce this exact shape.
 */

/** Risk tier assigned by the fraud scoring pipeline. */
export type RiskLevel = "low" | "medium" | "high" | "critical";

/**
 * Lightweight transaction projection used in list/dashboard views.
 * Mirrors api-specs/shared/schemas/transaction.yaml#/TransactionSummary.
 *
 * Deliberately does NOT include merchant_category or a numeric fraud_score
 * — the BFF's recent-transactions endpoint is a pass-through of
 * transaction-service's list endpoint, which only ever returns this slim
 * projection (see the comment on TransactionSummary in
 * dashboard-bff-api.yaml). Adding those fields here would not match what
 * the backend actually serializes.
 */
export interface TransactionSummary {
  id: string;
  amount: number;
  merchant_name: string;
  risk_level: RiskLevel | null;
  created_at: string;
}

/** GET /api/v1/dashboard/overview response. */
export interface DashboardOverview {
  total_transactions: number;
  fraud_rate: number;
  average_score: number;
  flagged_count: number;
  total_amount_processed: number;
}

/** GET /api/v1/dashboard/transactions/recent response. */
export interface RecentTransactionsResponse {
  items: TransactionSummary[];
  total: number;
  page: number;
  page_size: number;
}

/** One 0.1-wide bucket of the fraud score histogram. */
export interface FraudDistributionBucket {
  /** Inclusive-lower, exclusive-upper score range, e.g. "0.0-0.1". */
  range: string;
  count: number;
}

/** GET /api/v1/dashboard/fraud-distribution response. */
export interface FraudDistributionResponse {
  buckets: FraudDistributionBucket[];
}

/** One entry of GET /api/v1/dashboard/risk-breakdown's response array. */
export interface RiskBreakdownEntry {
  risk_level: RiskLevel;
  count: number;
}

/** One point of GET /api/v1/dashboard/timeline's response array. */
export interface TimelinePoint {
  timestamp: string;
  average_score: number;
}

/**
 * Shared error envelope every Lynceus service returns for non-2xx
 * responses. Mirrors api-specs/shared/errors.yaml#/ErrorResponse.
 */
export interface ErrorResponse {
  error: {
    code: string;
    message: string;
    details?: Record<string, unknown>;
    trace_id: string;
    timestamp: string;
  };
}
