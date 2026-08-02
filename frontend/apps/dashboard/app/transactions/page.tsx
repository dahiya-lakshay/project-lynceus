import type { JSX } from "react";
import { RecentTransactions } from "@/components/dashboard/recent-transactions";
import { getRecentTransactions } from "@/lib/api";
import styles from "./page.module.css";

export interface TransactionsPageProps {
  searchParams: Promise<{ page?: string; page_size?: string }>;
}

const DEFAULT_PAGE_SIZE = 20;
const MAX_PAGE_SIZE = 100;

function parsePositiveInt(value: string | undefined, fallback: number, max: number): number {
  const parsed = value === undefined ? NaN : Number.parseInt(value, 10);
  if (Number.isNaN(parsed) || parsed < 1) {
    return fallback;
  }
  return Math.min(parsed, max);
}

/**
 * Full transaction history, paginated via `?page=`/`?page_size=` search
 * params (a Server Component reading them server-side, per AGENTS.md's
 * "URL search params for server state" convention — no client JS needed
 * for pagination).
 *
 * NOTE on filters: the BFF's GET /api/v1/dashboard/transactions/recent
 * (api-specs/dashboard-bff-api.yaml) only accepts `page` and `page_size` —
 * it does not support risk_level/merchant_category/date_from/date_to query
 * params, even though transaction-service's own GET /api/v1/transactions
 * does. Rather than build filter dropdowns that silently no-op against the
 * real BFF, this page surfaces the gap directly instead (see the notice
 * below the heading).
 */
export default async function TransactionsPage({
  searchParams,
}: TransactionsPageProps): Promise<JSX.Element> {
  const params = await searchParams;
  const page = parsePositiveInt(params.page, 1, Number.MAX_SAFE_INTEGER);
  const pageSize = parsePositiveInt(params.page_size, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);

  const data = await getRecentTransactions(page, pageSize);

  return (
    <div className={styles.page}>
      <p className={styles.filterNote}>
        Filtering by risk level, merchant category, or date range isn&apos;t available
        yet — the Dashboard BFF&apos;s transactions/recent endpoint only supports
        pagination today. This table shows the tenant&apos;s full transaction
        history, most recent first.
      </p>
      <RecentTransactions
        transactions={data.items}
        pagination={{
          page: data.page,
          pageSize: data.page_size,
          total: data.total,
          basePath: "/transactions",
        }}
        showHeading={false}
      />
    </div>
  );
}
