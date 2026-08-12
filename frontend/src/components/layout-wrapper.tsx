"use client";

import { usePathname } from "next/navigation";
import Navbar from "@/components/Navbar";

export default function LayoutWrapper({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  
  // Do not render Navbar and footer on main app routes and auth pages
  const isAppRoute = pathname?.startsWith("/app") || pathname === "/app";
  const isAuthRoute = 
    pathname?.startsWith("/login") || 
    pathname?.startsWith("/forgot-password") || 
    pathname?.startsWith("/reset-password") || 
    pathname?.startsWith("/verify-email") || 
    (pathname?.startsWith("/register") && !pathname?.startsWith("/register-dev"));
  
  const hideNavbar = isAppRoute || isAuthRoute;

  if (hideNavbar) {
    return <>{children}</>;
  }

  return (
    <div className="min-h-full flex flex-col bg-background text-foreground antialiased">
      <Navbar />
      <main className="flex-grow pt-24">
        {children}
      </main>
      <footer className="py-8 border-t border-card-border bg-background/50 backdrop-blur-md">
        <div className="container mx-auto px-6 text-center text-slate-400 text-sm">
          © {new Date().getFullYear()} Agile Resource Optimizer. All rights reserved.
        </div>
      </footer>
    </div>
  );
}
