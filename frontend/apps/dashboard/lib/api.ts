/**
 * Typed fetch client for the Dashboard BFF, matching
 * api-specs/dashboard-bff-api.yaml's five endpoints exactly.
 */
import type {
  DashboardOverview,
  ErrorResponse,
  FraudDistributionResponse,
  RecentTransactionsResponse,
  RiskBreakdownEntry,
  TimelinePoint,
} from "@/types";

// Routed through NGINX — infrastructure/nginx/nginx.conf's
// `/api/v1/dashboard` location block proxies to the dashboard-bff upstream.
// The BFF's own port (8083) is only reachable container-to-container on the
// compose network, not from the browser or from `next dev` on the host, so
// this must stay the gateway URL, not the BFF's port directly.
//
// INTERNAL_API_URL (deliberately unprefixed, unlike NEXT_PUBLIC_API_URL) is
// read live at request time on the server and is never inlined into the
// browser bundle — it takes priority here because every current caller of
// this module (the overview/transactions Server Components) runs
// server-side, inside the frontend container, where the NGINX gateway URL
// above doesn't resolve to anything (see docker-compose.yml's frontend
// service comment for why routing SSR fetches through NGINX would
// deadlock). A future client component importing this module still falls
// back to NEXT_PUBLIC_API_URL, since INTERNAL_API_URL is never shipped to
// the browser.
const API_BASE_URL =
  process.env.INTERNAL_API_URL ??
  process.env.NEXT_PUBLIC_API_URL ??
  "http://localhost:8080/api/v1";

// Phase 1 ships with no auth or tenant-selection UI — Keycloak and real
// multi-tenancy land in Phase 2 (see docs/implementation_plan_phase-1.md's
// "Not in scope for Phase 1" list). Every request is scoped to this single
// hardcoded tenant until a real tenant selector (reading the JWT's tenant_id
// claim) replaces it.
const TENANT_ID = "default";

/** Subset of Next.js's fetch cache-control extension this client uses. */
interface NextFetchOptions {
  revalidate?: number | false;
  tags?: string[];
}

/**
 * Thrown for any non-2xx BFF response. Carries the parsed
 * api-specs/shared/errors.yaml#/ErrorResponse fields so callers (route
 * error.tsx boundaries, etc.) can surface the actual backend error code and
 * trace ID instead of a generic "fetch failed" message.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly traceId: string;
  readonly details: Record<string, unknown> | undefined;

  constructor(status: number, body: ErrorResponse) {
    super(body.error.message);
    this.name = "ApiError";
    this.status = status;
    this.code = body.error.code;
    this.traceId = body.error.trace_id;
    this.details = body.error.details;
  }
}

async function apiFetch<T>(
  path: string,
  init?: RequestInit & { next?: NextFetchOptions },
): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers: {
      "X-Tenant-Id": TENANT_ID,
      ...init?.headers,
    },
  });

  if (!response.ok) {
    // Every Lynceus service (Java and Python alike) returns this same
    // envelope for non-2xx responses per AGENTS.md's Error Handling section
    // — parse it rather than throwing a generic Error so the failure is
    // actionable (code + trace_id for correlating with backend logs).
    const body = (await response.json()) as ErrorResponse;
    throw new ApiError(response.status, body);
  }

  return response.json() as Promise<T>;
}

/**
 * Summary KPIs for the tenant's transaction and fraud activity.
 *
 * revalidate: 30 matches DashboardService's OVERVIEW_TTL (dashboard-bff
 * caches this response in Redis for 30s) — fetching more often than that
 * would just re-request data the BFF hasn't refreshed yet.
 */
export async function getOverview(): Promise<DashboardOverview> {
  return apiFetch<DashboardOverview>("/dashboard/overview", {
    next: { revalidate: 30 },
  });
}

/**
 * Paginated list of the tenant's most recent transactions with fraud
 * scores. Per DashboardService, this endpoint is never cached server-side
 * (it's a direct pass-through of transaction-service's list endpoint), so
 * this client doesn't cache it either — `cache: "no-store"` always hits the
 * BFF fresh.
 */
export async function getRecentTransactions(
  page: number,
  pageSize: number,
): Promise<RecentTransactionsResponse> {
  const params = new URLSearchParams({
    page: String(page),
    page_size: String(pageSize),
  });
  return apiFetch<RecentTransactionsResponse>(
    `/dashboard/transactions/recent?${params.toString()}`,
    { cache: "no-store" },
  );
}

/**
 * Histogram of ensemble fraud scores across ten 0.1-wide buckets.
 *
 * revalidate: 60 matches DashboardService's FRAUD_DISTRIBUTION_TTL.
 */
export async function getFraudDistribution(): Promise<FraudDistributionResponse> {
  return apiFetch<FraudDistributionResponse>("/dashboard/fraud-distribution", {
    next: { revalidate: 60 },
  });
}

/**
 * Count of transactions per risk level.
 *
 * revalidate: 60 matches DashboardService's RISK_BREAKDOWN_TTL.
 */
export async function getRiskBreakdown(): Promise<RiskBreakdownEntry[]> {
  return apiFetch<RiskBreakdownEntry[]>("/dashboard/risk-breakdown", {
    next: { revalidate: 60 },
  });
}

/**
 * Average fraud score over time. Per DashboardService this is never
 * cached server-side, so this client always hits the BFF fresh too.
 */
export async function getTimeline(): Promise<TimelinePoint[]> {
  return apiFetch<TimelinePoint[]>("/dashboard/timeline", {
    cache: "no-store",
  });
}
