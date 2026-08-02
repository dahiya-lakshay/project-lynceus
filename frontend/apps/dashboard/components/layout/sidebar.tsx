"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { JSX } from "react";
import { cn } from "@/lib/utils";
import styles from "./sidebar.module.css";

interface NavItem {
  href: string;
  label: string;
}

const NAV_ITEMS: readonly NavItem[] = [
  { href: "/overview", label: "Overview" },
  { href: "/transactions", label: "Transactions" },
];

/**
 * Primary navigation. Needs the current pathname to highlight the active
 * link, which requires the `usePathname` client hook — the one piece of
 * this dashboard's chrome that can't be a Server Component.
 */
export function Sidebar(): JSX.Element {
  const pathname = usePathname();

  return (
    <nav className={styles.sidebar} aria-label="Primary">
      <div className={styles.brand}>
        <span className={styles.brandMark} aria-hidden="true">
          L
        </span>
        <span className={styles.brandName}>Lynceus</span>
      </div>
      <div className={styles.nav}>
        {NAV_ITEMS.map((item) => {
          const isActive = pathname.startsWith(item.href);
          return (
            <Link
              key={item.href}
              href={item.href}
              className={cn(styles.navLink, isActive && styles.navLinkActive)}
              aria-current={isActive ? "page" : undefined}
            >
              {item.label}
            </Link>
          );
        })}
      </div>
    </nav>
  );
}
