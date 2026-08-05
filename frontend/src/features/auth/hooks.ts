"use client";

import { useCallback, useState } from "react";
import type { FieldValues, Path, UseFormSetError } from "react-hook-form";
import { ApiError } from "@/lib/api/errors";
import { apiErrorSchema, clientSessionSchema, type ClientSession } from "@/lib/api/schemas";

/**
 * Posts to a BFF auth route and normalises failures into {@link ApiError}.
 *
 * <p>These calls go through Next, not straight to the backend, because they are the
 * only ones that touch the refresh cookie. They also bypass {@code ApiClient}
 * entirely — it exists to attach a bearer token and recover from 401s, neither of
 * which means anything when you are not signed in yet.</p>
 */
async function postAuth<T>(path: string, body?: unknown): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      method: "POST",
      headers: body === undefined ? {} : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: "same-origin",
    });
  } catch {
    throw new ApiError({
      status: 0,
      code: "NETWORK_ERROR",
      message: "Could not reach SprintIQ. Check your connection and try again.",
    });
  }

  if (response.ok) {
    return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
  }

  const body_ = await response.json().catch(() => null);
  const parsed = apiErrorSchema.safeParse(body_);
  throw parsed.success
    ? new ApiError({
        status: parsed.data.status,
        code: parsed.data.code,
        message: parsed.data.message,
        correlationId: parsed.data.correlationId,
        fieldErrors: parsed.data.fieldErrors,
      })
    : new ApiError({
        status: response.status,
        code: "INTERNAL_ERROR",
        message: "Something went wrong. Please try again.",
      });
}

export const authApi = {
  login: (values: { email: string; password: string }) =>
    postAuth<ClientSession>("/api/auth/login", values).then((s) => clientSessionSchema.parse(s)),

  register: (values: {
    fullName: string;
    email: string;
    password: string;
    organizationName: string;
  }) =>
    postAuth<ClientSession>("/api/auth/register", {
      ...values,
      // The backend stores a timezone per user; taking it from the browser means one
      // fewer question on the form and correct dates from the first render.
      timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    }).then((s) => clientSessionSchema.parse(s)),

  forgotPassword: (values: { email: string }) =>
    postAuth<void>("/api/auth/password/forgot", values),

  resetPassword: (values: { token: string; newPassword: string }) =>
    postAuth<void>("/api/auth/password/reset", values),

  verifyEmail: (values: { token: string }) => postAuth<void>("/api/auth/email/verify", values),
};

/**
 * Routes a failed submission to the right place.
 *
 * <p>Field-level violations land on the input that caused them — the backend's
 * 12-character password rule belongs under the password box, not in a toast that
 * disappears while you are still reading it. Anything without a field is a form-level
 * message shown above the submit button.</p>
 */
export function useSubmitError<TValues extends FieldValues>(
  setError: UseFormSetError<TValues>,
) {
  const [formError, setFormError] = useState<ApiError | null>(null);

  const handle = useCallback(
    (cause: unknown) => {
      const error = cause instanceof ApiError ? cause : null;
      if (!error) {
        setFormError(
          new ApiError({ status: 0, code: "INTERNAL_ERROR", message: "Something went wrong." }),
        );
        return;
      }

      if (error.hasFieldErrors) {
        for (const [field, message] of Object.entries(error.fieldErrorMap())) {
          setError(field as Path<TValues>, { type: "server", message });
        }
        // A form-level message as well would be redundant with the inline ones, and
        // the eye goes to whichever is louder rather than to the field to fix.
        setFormError(null);
        return;
      }

      setFormError(error);
    },
    [setError],
  );

  const clear = useCallback(() => setFormError(null), []);

  return { formError, handleError: handle, clearError: clear };
}
