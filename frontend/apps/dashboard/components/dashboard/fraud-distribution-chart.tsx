"use client";

import type { JSX } from "react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { FraudDistributionBucket } from "@/types";
import styles from "./chart-card.module.css";

export interface FraudDistributionChartProps {
  buckets: FraudDistributionBucket[];
}

// Recharts renders these as literal SVG attribute values, which its own
// internal color handling can't resolve through CSS custom properties
// reliably — so the design tokens from app/globals.css are duplicated here
// as plain hex. Keep these in sync with --color-accent / --color-gridline /
// --color-text-muted / --color-bg-elevated / --color-border if those change.
// This is a single ordered magnitude (score-range buckets), not a set of
// distinct categories, so it gets one sequential hue per the dataviz
// skill's "sequential = one hue" rule rather than a rainbow per bar.
const BAR_COLOR = "#3987e5";
const GRID_COLOR = "#2c2c2a";
const AXIS_COLOR = "#898781";
const TOOLTIP_BG = "#1a1a19";
const TOOLTIP_BORDER = "rgba(255, 255, 255, 0.1)";

/** Histogram of ensemble fraud scores across ten 0.1-wide buckets. */
export function FraudDistributionChart({ buckets }: FraudDistributionChartProps): JSX.Element {
  return (
    <div className={styles.card}>
      <h2 className={styles.title}>Fraud Score Distribution</h2>
      <ResponsiveContainer width="100%" height={280}>
        <BarChart data={buckets} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
          <CartesianGrid stroke={GRID_COLOR} vertical={false} />
          <XAxis
            dataKey="range"
            stroke={AXIS_COLOR}
            tick={{ fontSize: 12, fill: AXIS_COLOR }}
            tickLine={false}
            axisLine={{ stroke: GRID_COLOR }}
          />
          <YAxis
            stroke={AXIS_COLOR}
            tick={{ fontSize: 12, fill: AXIS_COLOR }}
            tickLine={false}
            axisLine={false}
            allowDecimals={false}
            width={40}
          />
          <Tooltip
            cursor={{ fill: "rgba(255, 255, 255, 0.04)" }}
            contentStyle={{
              backgroundColor: TOOLTIP_BG,
              border: `1px solid ${TOOLTIP_BORDER}`,
              borderRadius: 8,
              fontSize: 12,
            }}
            labelStyle={{ color: "#ffffff" }}
            itemStyle={{ color: "#c3c2b7" }}
            formatter={(value) => [value, "Transactions"]}
            labelFormatter={(label) => `Score ${label}`}
          />
          <Bar dataKey="count" fill={BAR_COLOR} radius={[4, 4, 0, 0]} maxBarSize={40} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
