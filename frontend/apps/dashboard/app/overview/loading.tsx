import type { JSX } from "react";
import { Skeleton } from "@/components/ui/skeleton";
import styles from "./page.module.css";

export default function OverviewLoading(): JSX.Element {
  return (
    <div className={styles.page}>
      <section className={styles.kpiGrid}>
        {Array.from({ length: 4 }).map((_, index) => (
          <Skeleton key={index} height="96px" />
        ))}
      </section>
      <section className={styles.chartsGrid}>
        <Skeleton height="320px" />
        <Skeleton height="320px" />
      </section>
      <section>
        <Skeleton height="320px" />
      </section>
      <Skeleton height="360px" />
    </div>
  );
}
