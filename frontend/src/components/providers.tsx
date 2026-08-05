"use client";

import { useState } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "next-themes";
import { Toaster } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import { SessionProvider } from "@/components/session-provider";
import { ApiError } from "@/lib/api/errors";

function makeQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // Delivery data changes as the team works, but not second to second. Thirty
        // seconds keeps a board feeling live without refetching on every focus change.
        staleTime: 30_000,
        gcTime: 5 * 60_000,
        refetchOnWindowFocus: true,
        retry: (failureCount, error) => {
          // Retrying a 4xx repeats a request the server already answered definitively.
          // 401 is handled by the client's refresh path, not by retrying here.
          if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
            return false;
          }
          return failureCount < 2;
        },
      },
      mutations: {
        // A mutation is a user's deliberate action. Replaying it automatically risks
        // performing it twice, which for "start sprint" or "delete project" is worse
        // than showing the error.
        retry: false,
      },
    },
  });
}

export function Providers({ children }: { children: React.ReactNode }) {
  // useState, not a module-level client: a module singleton is shared across
  // requests on the server, which would leak one user's cached data to another.
  const [queryClient] = useState(makeQueryClient);

  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider attribute="class" defaultTheme="system" enableSystem disableTransitionOnChange>
        <SessionProvider>
          <TooltipProvider delayDuration={300}>
            {children}
            <Toaster position="bottom-right" closeButton richColors />
          </TooltipProvider>
        </SessionProvider>
      </ThemeProvider>
    </QueryClientProvider>
  );
}
