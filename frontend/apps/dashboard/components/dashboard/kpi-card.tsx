import type { JSX } from "react";
import styles from "./kpi-card.module.css";

export interface KpiCardProps {
  label: string;
  value: string;
  description?: string;
}

/** A single top-line KPI stat tile (Total Transactions, Fraud Rate, etc.). */
export function KpiCard({ label, value, description }: KpiCardProps): JSX.Element {
  return (
    <div className={styles.card}>
      <p className={styles.label}>{label}</p>
      <p className={styles.value}>{value}</p>
      {description ? <p className={styles.description}>{description}</p> : null}
    </div>
  );
}
