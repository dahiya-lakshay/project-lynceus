"use client";

import { usePathname } from "next/navigation";
import type { JSX } from "react";
import styles from "./header.module.css";

const PAGE_TITLES: Record<string, string> = {
  "/overview": "Overview",
  "/transactions": "Transactions",
};

/**
 * Top bar showing the current page's title and the active tenant. Derives
 * the title from the pathname (via `usePathname`) rather than each page
 * passing a prop down, so `AppShell`/`layout.tsx` stay simple Server
 * Components that don't need to know about routing.
 */
export function Header(): JSX.Element {
  const pathname = usePathname();
  const title = PAGE_TITLES[pathname] ?? "Dashboard";

  return (
    <header className={styles.header}>
      <h1 className={styles.title}>{title}</h1>
      {/* Phase 1 hardcodes a single tenant (see lib/api.ts) — this pill
          becomes a real tenant switcher once Keycloak/multi-tenancy lands
          in Phase 2. */}
      <span className={styles.tenantPill}>
        <span className={styles.tenantDot} aria-hidden="true" />
        Tenant: default
      </span>
    </header>
  );
}
