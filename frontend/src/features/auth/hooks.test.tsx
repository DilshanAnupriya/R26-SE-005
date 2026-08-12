import { describe, expect, it, vi } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useForm } from "react-hook-form";
import { useSubmitError } from "./hooks";
import { ApiError } from "@/lib/api/errors";

type Values = { email: string; password: string };

function setup() {
  return renderHook(() => {
    const form = useForm<Values>({ defaultValues: { email: "", password: "" } });
    const submit = useSubmitError<Values>(form.setError);
    // formState is a Proxy that only tracks what is read during render. Without
    // reading `errors` here the hook never re-renders when one is set, and the
    // assertions below would see a stale, empty object.
    const { errors } = form.formState;
    return { form, submit, errors };
  });
}

describe("useSubmitError", () => {
  it("puts a field violation on the input that caused it", () => {
    const { result } = setup();

    act(() => {
      result.current.submit.handleError(
        new ApiError({
          status: 422,
          code: "VALIDATION_ERROR",
          message: "Request validation failed",
          fieldErrors: [{ field: "password", message: "Password must be at least 12 characters" }],
        }),
      );
    });

    // Under the password box, where the user can act on it — not in a toast that
    // vanishes while they are still reading.
    expect(result.current.errors.password?.message).toBe(
      "Password must be at least 12 characters",
    );
    expect(result.current.submit.formError).toBeNull();
  });

  it("maps several field violations at once", () => {
    const { result } = setup();

    act(() => {
      result.current.submit.handleError(
        new ApiError({
          status: 422,
          code: "VALIDATION_ERROR",
          message: "failed",
          fieldErrors: [
            { field: "email", message: "Already registered" },
            { field: "password", message: "Too short" },
          ],
        }),
      );
    });

    expect(result.current.errors.email?.message).toBe("Already registered");
    expect(result.current.errors.password?.message).toBe("Too short");
  });

  it("shows an error with no field at form level", () => {
    const { result } = setup();

    act(() => {
      result.current.submit.handleError(
        new ApiError({
          status: 401,
          code: "INVALID_CREDENTIALS",
          message: "Invalid email or password",
          correlationId: "corr-42",
        }),
      );
    });

    // A wrong password belongs to the form, not to either input: saying which one
    // was wrong would tell an attacker whether the account exists.
    expect(result.current.submit.formError?.message).toBe("Invalid email or password");
    expect(result.current.submit.formError?.correlationId).toBe("corr-42");
    expect(result.current.errors.email).toBeUndefined();
  });

  it("clears a previous form error on the next submission", () => {
    const { result } = setup();

    act(() => {
      result.current.submit.handleError(
        new ApiError({ status: 500, code: "INTERNAL_ERROR", message: "Boom" }),
      );
    });
    expect(result.current.submit.formError).not.toBeNull();

    act(() => result.current.submit.clearError());
    expect(result.current.submit.formError).toBeNull();
  });

  it("survives a thrown value that is not an ApiError", () => {
    const { result } = setup();

    act(() => result.current.submit.handleError(new Error("unexpected")));

    expect(result.current.submit.formError?.message).toBe("Something went wrong.");
  });
});

describe("register timezone", () => {
  it("sends the browser's timezone so dates are right from the first render", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({}), { status: 200, headers: { "Content-Type": "application/json" } }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const { authApi } = await import("./hooks");
    await authApi
      .register({
        fullName: "Dev",
        email: "dev@example.com",
        password: "Str0ng!Passw0rd#2026",
        organizationName: "Acme",
      })
      .catch(() => undefined); // schema parse of {} fails; the request is what matters

    const body = JSON.parse((fetchMock.mock.lastCall as [string, RequestInit])[1].body as string);
    expect(body.timezone).toBe(Intl.DateTimeFormat().resolvedOptions().timeZone);
    vi.unstubAllGlobals();
  });
});
