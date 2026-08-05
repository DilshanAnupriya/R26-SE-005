import type { Metadata } from "next";
import { Inter } from "next/font/google";
import "./globals.css";

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
    <html lang="en" className={`${inter.variable} h-full`} suppressHydrationWarning>
      <body className="flex min-h-full flex-col font-sans antialiased">{children}</body>
    </html>
  );
}
