import { redirect } from "next/navigation";
import type { JSX } from "react";

/** The dashboard's landing view is the KPI/charts overview. */
export default function RootPage(): JSX.Element {
  redirect("/overview");
}
