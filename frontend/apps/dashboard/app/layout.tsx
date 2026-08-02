import type { Metadata } from "next";
import { Inter } from "next/font/google";
import type { JSX, ReactNode } from "react";
import { AppShell } from "@/components/layout/app-shell";
import "./globals.css";

// next/font/google self-hosts Inter at build time (no runtime request to
// Google Fonts, no manual <link> tag / layout shift) and exposes it as a
// CSS variable so globals.css's --font-sans can reference it.
const inter = Inter({
  subsets: ["latin"],
  variable: "--font-inter",
  display: "swap",
});

export const metadata: Metadata = {
  title: "Lynceus | Fraud Detection Dashboard",
  description: "Real-time transaction fraud detection and monitoring.",
};

export default function RootLayout({
  children,
}: {
  children: ReactNode;
}): JSX.Element {
  return (
    <html lang="en" className={inter.variable}>
      <body>
        <AppShell>{children}</AppShell>
      </body>
    </html>
  );
}
