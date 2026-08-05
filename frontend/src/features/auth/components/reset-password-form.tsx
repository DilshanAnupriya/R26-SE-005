"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { CheckCircle2, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { authApi, useSubmitError } from "../hooks";
import { resetPasswordSchema, type ResetPasswordValues } from "../schema";
import { FormError } from "./form-error";
import { PasswordStrength } from "./password-strength";

export function ResetPasswordForm() {
  const router = useRouter();
  const token = useSearchParams().get("token") ?? "";
  const [done, setDone] = useState(false);

  const form = useForm<ResetPasswordValues>({
    resolver: zodResolver(resetPasswordSchema),
    defaultValues: { token, newPassword: "", confirmPassword: "" },
  });
  const { formError, handleError, clearError } = useSubmitError(form.setError);
  const newPassword = form.watch("newPassword");

  const onSubmit = form.handleSubmit(async (values) => {
    clearError();
    try {
      await authApi.resetPassword({ token: values.token, newPassword: values.newPassword });
      setDone(true);
    } catch (cause) {
      handleError(cause);
    }
  });

  // A reset link with no token is either mistyped or truncated by a mail client.
  // Say so plainly instead of showing a form that cannot possibly succeed.
  if (!token) {
    return (
      <div className="space-y-3 text-center">
        <p className="text-sm">This password reset link is incomplete.</p>
        <p className="text-xs text-muted-foreground">
          Links can be cut short by some email clients. Request a fresh one to try again.
        </p>
        <Button asChild variant="outline" className="w-full">
          <Link href="/forgot-password">Request a new link</Link>
        </Button>
      </div>
    );
  }

  if (done) {
    return (
      <div className="space-y-3 text-center">
        <div className="mx-auto flex size-10 items-center justify-center rounded-full bg-status-done/12">
          <CheckCircle2 className="size-5 text-status-done" aria-hidden />
        </div>
        <p className="text-sm">Your password has been changed.</p>
        {/* No session is issued here on purpose: signing in with the new password
            proves the reset actually worked, and keeps one place where a session begins. */}
        <Button className="w-full" onClick={() => router.replace("/login")}>
          Sign in
        </Button>
      </div>
    );
  }

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-4">
      <FormError error={formError} />
      <input type="hidden" {...form.register("token")} />

      <div className="space-y-1.5">
        <Label htmlFor="newPassword">New password</Label>
        <Input
          id="newPassword"
          type="password"
          autoComplete="new-password"
          autoFocus
          aria-invalid={!!form.formState.errors.newPassword}
          {...form.register("newPassword")}
        />
        {newPassword ? (
          <PasswordStrength password={newPassword} />
        ) : form.formState.errors.newPassword ? (
          <p className="text-xs text-destructive">{form.formState.errors.newPassword.message}</p>
        ) : null}
      </div>

      <div className="space-y-1.5">
        <Label htmlFor="confirmPassword">Confirm new password</Label>
        <Input
          id="confirmPassword"
          type="password"
          autoComplete="new-password"
          aria-invalid={!!form.formState.errors.confirmPassword}
          {...form.register("confirmPassword")}
        />
        {form.formState.errors.confirmPassword ? (
          <p className="text-xs text-destructive">
            {form.formState.errors.confirmPassword.message}
          </p>
        ) : null}
      </div>

      <Button type="submit" className="w-full" disabled={form.formState.isSubmitting}>
        {form.formState.isSubmitting ? (
          <>
            <Loader2 className="size-4 animate-spin" aria-hidden />
            Updating…
          </>
        ) : (
          "Set new password"
        )}
      </Button>
    </form>
  );
}
