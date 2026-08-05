"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Eye, EyeOff, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useSession } from "@/components/session-provider";
import { authApi, useSubmitError } from "../hooks";
import { registerSchema, type RegisterValues } from "../schema";
import { FormError } from "./form-error";
import { PasswordStrength } from "./password-strength";

export function RegisterForm() {
  const router = useRouter();
  const { adopt } = useSession();
  const [showPassword, setShowPassword] = useState(false);

  const form = useForm<RegisterValues>({
    resolver: zodResolver(registerSchema),
    // onBlur rather than onChange: validating as someone types tells them their
    // half-finished email is invalid, which it obviously is, and is just noise.
    mode: "onBlur",
    defaultValues: {
      fullName: "",
      email: "",
      password: "",
      confirmPassword: "",
      organizationName: "",
    },
  });
  const { formError, handleError, clearError } = useSubmitError(form.setError);
  const password = form.watch("password");

  const onSubmit = form.handleSubmit(async (values) => {
    clearError();
    try {
      // Registration signs the user straight in — the backend provisions the
      // organization and returns a token pair, so there is no second login step.
      adopt(
        await authApi.register({
          fullName: values.fullName,
          email: values.email,
          password: values.password,
          organizationName: values.organizationName,
        }),
      );
      router.replace("/app");
    } catch (cause) {
      handleError(cause);
    }
  });

  const errors = form.formState.errors;
  const isSubmitting = form.formState.isSubmitting;

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-4">
      <FormError error={formError} />

      <div className="space-y-1.5">
        <Label htmlFor="fullName">Your name</Label>
        <Input
          id="fullName"
          autoComplete="name"
          autoFocus
          placeholder="Ada Lovelace"
          aria-invalid={!!errors.fullName}
          {...form.register("fullName")}
        />
        {errors.fullName ? (
          <p className="text-xs text-destructive">{errors.fullName.message}</p>
        ) : null}
      </div>

      <div className="space-y-1.5">
        <Label htmlFor="organizationName">Organization</Label>
        <Input
          id="organizationName"
          autoComplete="organization"
          placeholder="Acme Robotics"
          aria-invalid={!!errors.organizationName}
          {...form.register("organizationName")}
        />
        {errors.organizationName ? (
          <p className="text-xs text-destructive">{errors.organizationName.message}</p>
        ) : (
          <p className="text-2xs text-muted-foreground">
            Your workspace name. You can invite the rest of your team afterwards.
          </p>
        )}
      </div>

      <div className="space-y-1.5">
        <Label htmlFor="email">Work email</Label>
        <Input
          id="email"
          type="email"
          autoComplete="email"
          placeholder="you@company.com"
          aria-invalid={!!errors.email}
          {...form.register("email")}
        />
        {errors.email ? <p className="text-xs text-destructive">{errors.email.message}</p> : null}
      </div>

      <div className="space-y-1.5">
        <Label htmlFor="password">Password</Label>
        <div className="relative">
          <Input
            id="password"
            type={showPassword ? "text" : "password"}
            autoComplete="new-password"
            className="pr-9"
            aria-invalid={!!errors.password}
            {...form.register("password")}
          />
          <button
            type="button"
            onClick={() => setShowPassword((visible) => !visible)}
            className="absolute inset-y-0 right-0 flex w-9 items-center justify-center text-muted-foreground transition-colors hover:text-foreground"
            aria-label={showPassword ? "Hide password" : "Show password"}
            aria-pressed={showPassword}
            tabIndex={-1}
          >
            {showPassword ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
          </button>
        </div>
        {/* The checklist replaces the error message while typing: it says the same
            thing, but as something to complete rather than something already wrong. */}
        {password ? (
          <PasswordStrength password={password} />
        ) : errors.password ? (
          <p className="text-xs text-destructive">{errors.password.message}</p>
        ) : null}
      </div>

      <div className="space-y-1.5">
        <Label htmlFor="confirmPassword">Confirm password</Label>
        <Input
          id="confirmPassword"
          type={showPassword ? "text" : "password"}
          autoComplete="new-password"
          aria-invalid={!!errors.confirmPassword}
          {...form.register("confirmPassword")}
        />
        {errors.confirmPassword ? (
          <p className="text-xs text-destructive">{errors.confirmPassword.message}</p>
        ) : null}
      </div>

      <Button type="submit" className="w-full" disabled={isSubmitting}>
        {isSubmitting ? (
          <>
            <Loader2 className="size-4 animate-spin" aria-hidden />
            Creating your workspace…
          </>
        ) : (
          "Create account"
        )}
      </Button>
    </form>
  );
}
