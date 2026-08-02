"use client";

import type { JSX } from "react";
import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { TimelinePoint } from "@/types";
import { formatDateShort, formatDateTime, formatScore } from "@/lib/utils";
import styles from "./chart-card.module.css";

export interface TimelineChartProps {
  points: TimelinePoint[];
}

// Duplicated from --color-accent / --color-gridline / --color-text-muted in
// app/globals.css — see fraud-distribution-chart.tsx for why Recharts needs
// literal hex rather than var().
const LINE_COLOR = "#3987e5";
const GRID_COLOR = "#2c2c2a";
const AXIS_COLOR = "#898781";
const TOOLTIP_BG = "#1a1a19";
const TOOLTIP_BORDER = "rgba(255, 255, 255, 0.1)";

/**
 * Average ensemble fraud score over time. A single series names itself via
 * the card title, so per the dataviz skill's rule it carries no legend.
 */
export function TimelineChart({ points }: TimelineChartProps): JSX.Element {
  return (
    <div className={styles.card}>
      <h2 className={styles.title}>Average Fraud Score Over Time</h2>
      <ResponsiveContainer width="100%" height={280}>
        <AreaChart data={points} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
          <defs>
            <linearGradient id="timelineFill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="5%" stopColor={LINE_COLOR} stopOpacity={0.35} />
              <stop offset="95%" stopColor={LINE_COLOR} stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid stroke={GRID_COLOR} vertical={false} />
          <XAxis
            dataKey="timestamp"
            tickFormatter={formatDateShort}
            stroke={AXIS_COLOR}
            tick={{ fontSize: 12, fill: AXIS_COLOR }}
            tickLine={false}
            axisLine={{ stroke: GRID_COLOR }}
          />
          <YAxis
            domain={[0, 1]}
            stroke={AXIS_COLOR}
            tick={{ fontSize: 12, fill: AXIS_COLOR }}
            tickLine={false}
            axisLine={false}
            width={40}
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
            formatter={(value) => [formatScore(Number(value)), "Avg. score"]}
            labelFormatter={(label) => formatDateTime(String(label))}
          />
          <Area
            type="monotone"
            dataKey="average_score"
            stroke={LINE_COLOR}
            strokeWidth={2}
            fill="url(#timelineFill)"
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
