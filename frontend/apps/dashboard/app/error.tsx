"use client";

import { useEffect } from "react";
import type { JSX } from "react";
import styles from "./error.module.css";

export interface ErrorPageProps {
  error: Error & { digest?: string };
  reset: () => void;
}

/**
 * Root error boundary. Catches anything not already caught by a more
 * specific route's error.tsx (e.g. a failure in layout.tsx itself). Next.js
 * strips custom error properties (like our ApiError's .code/.traceId) in
 * production builds for security, so this only ever renders the message
 * Next.js actually forwards — the full detail still lands in server logs
 * via the console.error below (visible with a `digest` correlating back to
 * the server-side stack trace).
 */
export default function GlobalError({ error, reset }: ErrorPageProps): JSX.Element {
  useEffect(() => {
    console.error(error);
  }, [error]);

  return (
    <div className={styles.container} role="alert">
      <h2 className={styles.title}>Something went wrong</h2>
      <p className={styles.message}>
        {error.message || "An unexpected error occurred while loading this page."}
        {error.digest ? ` (ref: ${error.digest})` : null}
      </p>
      <button type="button" className={styles.retry} onClick={() => reset()}>
        Try again
      </button>
    </div>
  );
}
