import type { JSX } from "react";
import { Skeleton } from "@/components/ui/skeleton";
import styles from "./page.module.css";

export default function TransactionsLoading(): JSX.Element {
  return (
    <div className={styles.page}>
      <Skeleton height="44px" />
      <Skeleton height="720px" />
    </div>
  );
}
