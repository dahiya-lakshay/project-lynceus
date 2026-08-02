import type { JSX, ReactNode } from "react";
import { Header } from "./header";
import { Sidebar } from "./sidebar";
import styles from "./app-shell.module.css";

export interface AppShellProps {
  children: ReactNode;
}

/**
 * Persistent dashboard chrome (sidebar + header) wrapping every route.
 * Stays a Server Component itself — the interactivity (active nav link,
 * pathname-derived title) is isolated inside Sidebar/Header so the rest of
 * the shell ships zero extra client JS.
 */
export function AppShell({ children }: AppShellProps): JSX.Element {
  return (
    <div className={styles.shell}>
      <Sidebar />
      <div className={styles.content}>
        <Header />
        <main className={styles.main}>{children}</main>
      </div>
    </div>
  );
}
