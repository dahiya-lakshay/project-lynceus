"use client";

import { useEffect } from "react";
import type { JSX } from "react";
import styles from "../error.module.css";

export interface TransactionsErrorProps {
  error: Error & { digest?: string };
  reset: () => void;
}

export default function TransactionsError({ error, reset }: TransactionsErrorProps): JSX.Element {
  useEffect(() => {
    console.error(error);
  }, [error]);

  return (
    <div className={styles.container} role="alert">
      <h2 className={styles.title}>Couldn&apos;t load transactions</h2>
      <p className={styles.message}>
        {error.message ||
          "The Dashboard BFF didn't respond. Check that it's running and reachable through NGINX."}
      </p>
      <button type="button" className={styles.retry} onClick={() => reset()}>
        Try again
      </button>
    </div>
  );
}
