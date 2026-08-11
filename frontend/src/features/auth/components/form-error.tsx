import { AlertCircle } from "lucide-react";
import type { ApiError } from "@/lib/api/errors";

/**
 * Form-level failure: the ones with no field to attach to — wrong password, locked
 * account, unreachable server.
 *
 * <p>`role="alert"` so a screen reader announces it on appearance; someone who
 * submitted a form and heard nothing has no way to know it failed.</p>
 *
 * <p>The correlation id is rendered when present, small and selectable. It is the
 * same id in the backend's structured logs, so a support conversation can start from
 * the exact request instead of "sometime this morning".</p>
 */
export function FormError({ error }: { error: ApiError | null }) {
  if (!error) return null;

  return (
    <div
      role="alert"
      className="mb-4 flex gap-2.5 rounded-md border border-destructive/30 bg-destructive/8 p-3 text-sm text-destructive"
    >
      <AlertCircle className="mt-px size-4 shrink-0" aria-hidden />
      <div className="space-y-1">
        <p>{error.message}</p>
        {error.correlationId ? (
          <p className="font-mono text-2xs text-destructive/70 select-all">
            Reference: {error.correlationId}
          </p>
        ) : null}
      </div>
    </div>
  );
}
