import type { JSX } from "react";
import { FraudDistributionChart } from "@/components/dashboard/fraud-distribution-chart";
import { KpiCard } from "@/components/dashboard/kpi-card";
import { RecentTransactions } from "@/components/dashboard/recent-transactions";
import { RiskBreakdownChart } from "@/components/dashboard/risk-breakdown-chart";
import { TimelineChart } from "@/components/dashboard/timeline-chart";
import {
  getFraudDistribution,
  getOverview,
  getRecentTransactions,
  getRiskBreakdown,
  getTimeline,
} from "@/lib/api";
import { formatPercent, formatScore } from "@/lib/utils";
import styles from "./page.module.css";

/**
 * Overview KPIs + charts + recent activity — the dashboard's landing view.
 * A Server Component that fetches all five BFF endpoints in parallel; each
 * fetch's own `next.revalidate`/`cache` setting (see lib/api.ts) matches
 * that endpoint's actual Redis cache TTL on the BFF.
 */
export default async function OverviewPage(): Promise<JSX.Element> {
  const [overview, recent, distribution, breakdown, timeline] = await Promise.all([
    getOverview(),
    getRecentTransactions(1, 20),
    getFraudDistribution(),
    getRiskBreakdown(),
    getTimeline(),
  ]);

  return (
    <div className={styles.page}>
      <section className={styles.kpiGrid}>
        <KpiCard
          label="Total Transactions"
          value={overview.total_transactions.toLocaleString("en-US")}
        />
        <KpiCard label="Fraud Rate" value={formatPercent(overview.fraud_rate)} />
        <KpiCard label="Average Score" value={formatScore(overview.average_score)} />
        <KpiCard
          label="Flagged Count"
          value={overview.flagged_count.toLocaleString("en-US")}
        />
      </section>

      <section className={styles.chartsGrid}>
        <FraudDistributionChart buckets={distribution.buckets} />
        <RiskBreakdownChart entries={breakdown} />
      </section>

      <section>
        <TimelineChart points={timeline} />
      </section>

      <RecentTransactions transactions={recent.items} />
    </div>
  );
}
