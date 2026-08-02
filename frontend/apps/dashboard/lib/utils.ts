/**
 * Small, dependency-free formatting/display helpers shared across
 * dashboard components. Kept framework-agnostic (no React imports) so they
 * can run in both Server and Client Components.
 */
import type { RiskLevel } from "@/types";

/** Combines conditional class names, skipping falsy values. */
export function cn(...classes: Array<string | false | null | undefined>): string {
  return classes.filter(Boolean).join(" ");
}

/** Formats a decimal amount as USD currency, e.g. 1234.5 -> "$1,234.50". */
export function formatCurrency(amount: number): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
  }).format(amount);
}

/** Formats a [0, 1] fraction as a percentage, e.g. 0.0523 -> "5.23%". */
export function formatPercent(fraction: number): string {
  return new Intl.NumberFormat("en-US", {
    style: "percent",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(fraction);
}

/** Formats a [0, 1] ensemble fraud score to two decimal places, e.g. "0.73". */
export function formatScore(score: number): string {
  return score.toFixed(2);
}

/** Formats an ISO 8601 timestamp for display in the tenant's local time. */
export function formatDateTime(isoTimestamp: string): string {
  return new Intl.DateTimeFormat("en-US", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(isoTimestamp));
}

/** Formats an ISO 8601 timestamp as a short date, e.g. for chart axis ticks. */
export function formatDateShort(isoTimestamp: string): string {
  return new Intl.DateTimeFormat("en-US", {
    month: "short",
    day: "numeric",
  }).format(new Date(isoTimestamp));
}

/** Shortens a UUID to its first segment for compact table display. */
export function truncateId(id: string): string {
  return id.split("-")[0] ?? id;
}

/** Human-readable label for a risk level, e.g. "high" -> "High". */
export function formatRiskLevel(riskLevel: RiskLevel | null): string {
  if (riskLevel === null) {
    return "Pending";
  }
  return riskLevel.charAt(0).toUpperCase() + riskLevel.slice(1);
}
