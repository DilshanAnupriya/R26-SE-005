"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Loader2, MailCheck } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { authApi, useSubmitError } from "../hooks";
import { forgotPasswordSchema, type ForgotPasswordValues } from "../schema";
import { FormError } from "./form-error";

export function ForgotPasswordForm() {
  const [submitted, setSubmitted] = useState(false);

  const form = useForm<ForgotPasswordValues>({
    resolver: zodResolver(forgotPasswordSchema),
    defaultValues: { email: "" },
  });
  const { formError, handleError, clearError } = useSubmitError(form.setError);

  const onSubmit = form.handleSubmit(async (values) => {
    clearError();
    try {
      await authApi.forgotPassword(values);
      setSubmitted(true);
    } catch (cause) {
      // Only a genuine outage reaches here; the route returns 204 for an unknown
      // address so the response cannot be used to test whether an account exists.
      handleError(cause);
    }
  });

  if (submitted) {
    return (
      <div className="space-y-3 text-center">
        <div className="mx-auto flex size-10 items-center justify-center rounded-full bg-status-done/12">
          <MailCheck className="size-5 text-status-done" aria-hidden />
        </div>
        {/* Deliberately does not confirm whether an account exists — the same
            message either way, or this page becomes an account-enumeration oracle. */}
        <p className="text-sm">
          If an account exists for <span className="font-medium">{form.getValues("email")}</span>,
          a reset link is on its way.
        </p>
        <p className="text-xs text-muted-foreground">
          The link expires in an hour. Check your spam folder if it does not arrive.
        </p>
      </div>
    );
  }

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-4">
      <FormError error={formError} />

      <div className="space-y-1.5">
        <Label htmlFor="email">Email</Label>
        <Input
          id="email"
          type="email"
          autoComplete="email"
          autoFocus
          placeholder="you@company.com"
          aria-invalid={!!form.formState.errors.email}
          {...form.register("email")}
        />
        {form.formState.errors.email ? (
          <p className="text-xs text-destructive">{form.formState.errors.email.message}</p>
        ) : null}
      </div>

      <Button type="submit" className="w-full" disabled={form.formState.isSubmitting}>
        {form.formState.isSubmitting ? (
          <>
            <Loader2 className="size-4 animate-spin" aria-hidden />
            Sending…
          </>
        ) : (
          "Send reset link"
        )}
      </Button>
    </form>
  );
}
