import type { CSSProperties, JSX } from "react";
import { cn } from "@/lib/utils";
import styles from "./skeleton.module.css";

export interface SkeletonProps {
  width?: string;
  height?: string;
  className?: string;
}

/** Shimmering placeholder block used in route `loading.tsx` files. */
export function Skeleton({ width = "100%", height = "1rem", className }: SkeletonProps): JSX.Element {
  const style: CSSProperties = { width, height };
  return <span className={cn(styles.skeleton, className)} style={style} aria-hidden="true" />;
}
