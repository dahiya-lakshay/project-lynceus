"use client";

import type { JSX } from "react";
import { Cell, Legend, Pie, PieChart, ResponsiveContainer, Tooltip } from "recharts";
import type { RiskBreakdownEntry, RiskLevel } from "@/types";
import { formatRiskLevel } from "@/lib/utils";
import styles from "./chart-card.module.css";

export interface RiskBreakdownChartProps {
  entries: RiskBreakdownEntry[];
}

// Same status palette as --color-risk-* in app/globals.css, duplicated as
// plain hex because Recharts' Cell/Pie fills are literal SVG attributes
// (see fraud-distribution-chart.tsx for the same tradeoff). This is a
// fixed, never-themed status palette (not a generic categorical one) — the
// four risk levels always map to these exact colors everywhere in the app.
const RISK_COLOR: Record<RiskLevel, string> = {
  low: "#0ca30c",
  medium: "#fab219",
  high: "#ec835a",
  critical: "#d03b3b",
};

const TOOLTIP_BG = "#1a1a19";
const TOOLTIP_BORDER = "rgba(255, 255, 255, 0.1)";

/**
 * Count of transactions per risk level. With only 4 slices, each gets a
 * direct label (name + count) in addition to the legend, per the dataviz
 * skill's rule that identity is never color-alone for <= 4 series.
 */
export function RiskBreakdownChart({ entries }: RiskBreakdownChartProps): JSX.Element {
  return (
    <div className={styles.card}>
      <h2 className={styles.title}>Risk Breakdown</h2>
      <ResponsiveContainer width="100%" height={280}>
        <PieChart>
          <Pie
            data={entries}
            dataKey="count"
            nameKey="risk_level"
            innerRadius={55}
            outerRadius={90}
            paddingAngle={2}
            label={({ name, value }) => `${formatRiskLevel(name as RiskLevel)} (${value})`}
            labelLine={false}
          >
            {entries.map((entry) => (
              <Cell key={entry.risk_level} fill={RISK_COLOR[entry.risk_level]} stroke="none" />
            ))}
          </Pie>
          <Legend
            verticalAlign="bottom"
            height={32}
            formatter={(value: string) => formatRiskLevel(value as RiskLevel)}
            wrapperStyle={{ fontSize: 12, color: "#c3c2b7" }}
          />
          <Tooltip
            contentStyle={{
              backgroundColor: TOOLTIP_BG,
              border: `1px solid ${TOOLTIP_BORDER}`,
              borderRadius: 8,
              fontSize: 12,
            }}
            labelStyle={{ color: "#ffffff" }}
            itemStyle={{ color: "#c3c2b7" }}
            formatter={(value, name) => [value, formatRiskLevel(String(name) as RiskLevel)]}
          />
        </PieChart>
      </ResponsiveContainer>
    </div>
  );
}
