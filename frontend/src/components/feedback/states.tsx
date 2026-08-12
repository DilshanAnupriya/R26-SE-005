import { AlertTriangle, Inbox, Lock, SearchX, WifiOff } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ApiError } from "@/lib/api/errors";

/**
 * Empty and error states.
 *
 * <p>A permission failure or a missing record is a *state* of the screen, not an
 * event that happened to it — so these render in place, where the content would have
 * been, rather than as a toast that slides away while the user is still reading it.
 * Toasts are reserved for things that genuinely happened in passing.</p>
 */

export function EmptyState({
  title,
  description,
  action,
  icon: Icon = Inbox,
}: {
  title: string;
  description?: string;
  action?: React.ReactNode;
  icon?: typeof Inbox;
}) {
  return (
    <div className="flex flex-col items-center justify-center rounded-lg border border-dashed px-6 py-12 text-center">
      <span className="flex size-10 items-center justify-center rounded-full bg-muted">
        <Icon className="size-4 text-muted-foreground" aria-hidden />
      </span>
      <h3 className="mt-3 text-sm font-medium">{title}</h3>
      {description ? (
        <p className="mt-1 max-w-sm text-xs text-muted-foreground">{description}</p>
      ) : null}
      {action ? <div className="mt-4">{action}</div> : null}
    </div>
  );
}

/** Picks the presentation from what actually went wrong. */
export function ErrorState({
  error,
  onRetry,
}: {
  error: unknown;
  onRetry?: () => void;
}) {
  const apiError = error instanceof ApiError ? error : null;

  if (apiError?.status === 403) {
    return (
      <EmptyState
        icon={Lock}
        title="You do not have access to this"
        description="Ask an administrator in your organization if you think you should."
      />
    );
  }

  if (apiError?.status === 404) {
    return (
      <EmptyState
        icon={SearchX}
        title="Not found"
        description="This may have been deleted, or the link may be wrong."
      />
    );
  }

  const offline = apiError?.code === "NETWORK_ERROR";

  return (
    <div
      role="alert"
      className="flex flex-col items-center justify-center rounded-lg border border-destructive/30 bg-destructive/5 px-6 py-12 text-center"
    >
      <span className="flex size-10 items-center justify-center rounded-full bg-destructive/12">
        {offline ? (
          <WifiOff className="size-4 text-destructive" aria-hidden />
        ) : (
          <AlertTriangle className="size-4 text-destructive" aria-hidden />
        )}
      </span>
      <h3 className="mt-3 text-sm font-medium">
        {offline ? "Cannot reach SprintIQ" : "Something went wrong"}
      </h3>
      <p className="mt-1 max-w-sm text-xs text-muted-foreground">
        {apiError?.message ?? "An unexpected error occurred."}
      </p>
      {apiError?.correlationId ? (
        // The same id in the backend's structured logs, so a support conversation can
        // start from the exact request rather than "sometime this morning".
        <p className="mt-2 font-mono text-2xs text-muted-foreground select-all">
          Reference: {apiError.correlationId}
        </p>
      ) : null}
      {onRetry ? (
        <Button variant="outline" size="sm" onClick={onRetry} className="mt-4">
          Try again
        </Button>
      ) : null}
    </div>
  );
}
