import Link from "next/link";
import type { JSX } from "react";
import { Badge } from "@/components/ui/badge";
import type { DataTableColumn } from "@/components/ui/data-table";
import { DataTable } from "@/components/ui/data-table";
import { formatCurrency, formatDateTime, truncateId } from "@/lib/utils";
import type { TransactionSummary } from "@/types";
import styles from "./recent-transactions.module.css";

export interface RecentTransactionsPagination {
  page: number;
  pageSize: number;
  total: number;
  /** Route to link pagination controls against, e.g. "/transactions". */
  basePath: string;
}

export interface RecentTransactionsProps {
  transactions: TransactionSummary[];
  /** Omit on the overview page, which shows a fixed last-20 slice with no paging. */
  pagination?: RecentTransactionsPagination;
  showHeading?: boolean;
}

/**
 * Columns intentionally match TransactionSummary exactly (id, merchant_name,
 * amount, risk_level, created_at) — NOT the full Transaction schema.
 * dashboard-bff-api.yaml documents why: /dashboard/transactions/recent is a
 * pass-through of transaction-service's list endpoint, which only ever
 * returns this slim summary projection. There is no merchant_category or
 * numeric fraud_score on this endpoint, so this table doesn't render
 * columns for them rather than inventing data the API doesn't provide.
 */
const COLUMNS: Array<DataTableColumn<TransactionSummary>> = [
  {
    key: "id",
    header: "ID",
    render: (transaction) => <code className={styles.id}>{truncateId(transaction.id)}</code>,
  },
  {
    key: "merchant_name",
    header: "Merchant",
    render: (transaction) => transaction.merchant_name,
  },
  {
    key: "amount",
    header: "Amount",
    align: "right",
    render: (transaction) => formatCurrency(transaction.amount),
  },
  {
    key: "risk_level",
    header: "Risk Level",
    render: (transaction) => <Badge riskLevel={transaction.risk_level} />,
  },
  {
    key: "created_at",
    header: "Date",
    render: (transaction) => formatDateTime(transaction.created_at),
  },
];

/** Server-rendered pagination — plain links to `?page=n`, no client JS. */
function Pagination({ page, pageSize, total, basePath }: RecentTransactionsPagination): JSX.Element {
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const hasPrev = page > 1;
  const hasNext = page < totalPages;
  const rangeStart = total === 0 ? 0 : (page - 1) * pageSize + 1;
  const rangeEnd = Math.min(page * pageSize, total);

  return (
    <div className={styles.pagination}>
      <span className={styles.paginationSummary}>
        Showing {rangeStart}-{rangeEnd} of {total.toLocaleString("en-US")}
      </span>
      <div className={styles.paginationControls}>
        {hasPrev ? (
          <Link
            href={`${basePath}?page=${page - 1}&page_size=${pageSize}`}
            className={styles.paginationLink}
          >
            Previous
          </Link>
        ) : (
          <span className={styles.paginationLinkDisabled} aria-disabled="true">
            Previous
          </span>
        )}
        <span className={styles.paginationPage}>
          Page {page} of {totalPages}
        </span>
        {hasNext ? (
          <Link
            href={`${basePath}?page=${page + 1}&page_size=${pageSize}`}
            className={styles.paginationLink}
          >
            Next
          </Link>
        ) : (
          <span className={styles.paginationLinkDisabled} aria-disabled="true">
            Next
          </span>
        )}
      </div>
    </div>
  );
}

export function RecentTransactions({
  transactions,
  pagination,
  showHeading = true,
}: RecentTransactionsProps): JSX.Element {
  return (
    <section className={styles.section}>
      {showHeading ? <h2 className={styles.title}>Recent Transactions</h2> : null}
      <DataTable
        columns={COLUMNS}
        rows={transactions}
        getRowKey={(transaction) => transaction.id}
        emptyMessage="No transactions recorded yet."
      />
      {pagination ? <Pagination {...pagination} /> : null}
    </section>
  );
}
