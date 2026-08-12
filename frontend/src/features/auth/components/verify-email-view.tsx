"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { CheckCircle2, Loader2, XCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ApiError } from "@/lib/api/errors";
import { authApi } from "../hooks";

type State = "verifying" | "verified" | "failed" | "missing-token";

/**
 * Consumes the verification token from the email link.
 *
 * <p>Verifies on mount rather than behind a button: the user already expressed
 * intent by clicking the link in their inbox, and asking them to confirm again is a
 * step that exists only to serve the implementation.</p>
 */
export function VerifyEmailView() {
  const token = useSearchParams().get("token") ?? "";
  const [state, setState] = useState<State>(token ? "verifying" : "missing-token");
  const [message, setMessage] = useState<string | null>(null);
  const attempted = useRef(false);

  useEffect(() => {
    if (!token || attempted.current) return;
    // The token is single-use. React StrictMode double-invokes effects in
    // development, and a second call would consume an already-spent token and
    // report failure for a verification that actually succeeded.
    attempted.current = true;

    authApi
      .verifyEmail({ token })
      .then(() => setState("verified"))
      .catch((cause: unknown) => {
        setMessage(
          cause instanceof ApiError
            ? cause.message
            : "We could not verify this link. It may have expired.",
        );
        setState("failed");
      });
  }, [token]);

  if (state === "verifying") {
    return (
      <div className="space-y-3 text-center" aria-live="polite">
        <Loader2 className="mx-auto size-6 animate-spin text-muted-foreground" aria-hidden />
        <p className="text-sm text-muted-foreground">Verifying your email…</p>
      </div>
    );
  }

  if (state === "verified") {
    return (
      <div className="space-y-3 text-center" aria-live="polite">
        <div className="mx-auto flex size-10 items-center justify-center rounded-full bg-status-done/12">
          <CheckCircle2 className="size-5 text-status-done" aria-hidden />
        </div>
        <p className="text-sm">Your email is verified.</p>
        <Button asChild className="w-full">
          <Link href="/app">Go to SprintIQ</Link>
        </Button>
      </div>
    );
  }

  return (
    <div className="space-y-3 text-center" aria-live="polite">
      <div className="mx-auto flex size-10 items-center justify-center rounded-full bg-destructive/12">
        <XCircle className="size-5 text-destructive" aria-hidden />
      </div>
      <p className="text-sm">
        {state === "missing-token"
          ? "This verification link is incomplete."
          : (message ?? "We could not verify this link.")}
      </p>
      <p className="text-xs text-muted-foreground">
        Verification links expire. You can request a new one from your account settings.
      </p>
      <Button asChild variant="outline" className="w-full">
        <Link href="/login">Back to sign in</Link>
      </Button>
    </div>
  );
}
