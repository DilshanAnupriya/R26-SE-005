import type { Metadata } from "next";
import { Inter } from "next/font/google";
import "./globals.css";
import { cn } from "@/lib/utils";

// One typeface. `shadcn init` also wired up Geist bound to --font-sans, which
// silently shadowed Inter — removed, since two sans stacks fighting over the same
// variable is a coin toss over which one actually renders.
const inter = Inter({ subsets: ["latin"], variable: "--font-inter" });

export const metadata: Metadata = {
  title: "SprintIQ — AI-powered agile delivery",
  description:
    "Plan, execute and track sprints with AI estimation, risk and cost forecasting attached to the decisions you are actually making.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    // suppressHydrationWarning: the theme provider writes class="dark" onto <html>
    // before React hydrates, which would otherwise be reported as a mismatch.
    <html lang="en" className={cn("h-full", inter.variable)} suppressHydrationWarning>
      <body className="flex min-h-full flex-col font-sans antialiased">{children}</body>
    </html>
  );
}
