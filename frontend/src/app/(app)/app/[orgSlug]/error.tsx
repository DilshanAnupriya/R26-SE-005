"use client";

import { useEffect } from "react";
import { ErrorState } from "@/components/feedback/states";

/**
 * Route-level boundary for the signed-in area.
 *
 * <p>Scoped inside the org layout on purpose: the sidebar, topbar and command palette
 * keep rendering, so a failed page leaves the user somewhere they can navigate out of
 * rather than staring at a blank document. A boundary at the root would take the whole
 * shell down with it.</p>
 */
export default function AppError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    // Next strips the message in production and leaves only a digest; logging it here
    // is what makes a report traceable to the server-side stack.
    console.error("Route error", { message: error.message, digest: error.digest });
  }, [error]);

  return (
    <div className="mx-auto max-w-2xl py-12">
      <ErrorState error={error} onRetry={reset} />
    </div>
  );
}
