import type { Metadata } from "next";
import { Inter } from "next/font/google";
import "./globals.css";

const inter = Inter({
  subsets: ["latin"],
  variable: "--font-inter",
});

export const metadata: Metadata = {
  title: "Agile Resource Optimizer | AI-Powered Resource Allocation",
  description: "Optimize your agile team's performance with AI-driven resource allocation and sprint management.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className={`${inter.variable} h-full`}>
      <body className="min-h-full flex flex-col bg-background text-foreground antialiased">
        <main className="flex-grow">
          {children}
        </main>
        <footer className="py-8 border-t border-card-border bg-background/50 backdrop-blur-md">
          <div className="container mx-auto px-6 text-center text-slate-400 text-sm">
            © {new Date().getFullYear()} Agile Resource Optimizer. All rights reserved.
          </div>
        </footer>
      </body>
    </html>
  );
}
