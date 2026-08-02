import type { JSX } from "react";
import type { RiskLevel } from "@/types";
import { formatRiskLevel } from "@/lib/utils";
import styles from "./badge.module.css";

export interface BadgeProps {
  riskLevel: RiskLevel | null;
}

// Next.js's ambient `*.module.css` typing declares the default export as a
// generic index signature (`{ [key: string]: string }`), so under this
// workspace's `noUncheckedIndexedAccess` every lookup widens to
// `string | undefined` even though these four classes are statically
// defined in the co-located badge.module.css right above. The non-null
// assertions here are that known-safe case, not a guess.
const RISK_CLASS: Record<RiskLevel, string> = {
  low: styles.low!,
  medium: styles.medium!,
  high: styles.high!,
  critical: styles.critical!,
};

/** Color-coded risk-level pill. Null (not yet scored) renders as "Pending". */
export function Badge({ riskLevel }: BadgeProps): JSX.Element {
  const className = riskLevel === null ? styles.pending : RISK_CLASS[riskLevel];
  return <span className={`${styles.badge} ${className}`}>{formatRiskLevel(riskLevel)}</span>;
}
