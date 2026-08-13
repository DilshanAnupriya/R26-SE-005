import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";
import { cn } from "@/lib/utils";
import { Providers } from "@/components/providers";
import LayoutWrapper from "@/components/layout-wrapper";

/*
 * Geist and Geist Mono, as specified by the design. The mono face is not
 * decoration: issue keys (PAY-142), story points and timers are all tabular in the
 * mockup, and a proportional face makes a column of them visibly ragged.
 */
const geist = Geist({ subsets: ["latin"], variable: "--font-geist" });
const geistMono = Geist_Mono({ subsets: ["latin"], variable: "--font-geist-mono" });

export const metadata: Metadata = {
  title: "SprintIQ — AI-powered agile delivery",
  description:
    "Plan, execute and track sprints with AI estimation, risk and cost forecasting attached to the decisions you are actually making.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    // suppressHydrationWarning: the theme provider writes class="dark" onto <html>
    // before React hydrates, which would otherwise be reported as a mismatch.
    <html
      lang="en"
      className={cn("h-full", geist.variable, geistMono.variable)}
      suppressHydrationWarning
    >
      <body className="flex min-h-full flex-col font-sans antialiased">
        <Providers>
          <LayoutWrapper>
            {children}
          </LayoutWrapper>
        </Providers>
      </body>
    </html>
  );
}
