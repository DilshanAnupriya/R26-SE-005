import { z } from "zod";

/**
 * Client-side mirrors of the backend's auth validation.
 *
 * <p>Deliberately duplicated rather than trusted from one side only. The backend is
 * the authority — it rejects anything invalid regardless of what happens here — but
 * telling someone their password is too short after a network round trip, when the
 * rule was knowable as they typed, is a worse experience than it needs to be.</p>
 *
 * <p>The password rule is character-for-character the backend's regex in
 * {@code AuthDtos.PASSWORD_RULE}. If one moves, both move.</p>
 */
const PASSWORD_RULE = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9]).{12,128}$/;

const passwordField = z
  .string()
  .min(1, "Password is required")
  .regex(
    PASSWORD_RULE,
    "At least 12 characters, with an upper case letter, a lower case letter, a number and a symbol",
  );

const emailField = z
  .email("Enter a valid email address")
  .max(255, "That email address is too long");

export const loginSchema = z.object({
  email: emailField,
  // Not the full rule: an existing account may predate a rule change, and refusing
  // to even attempt the sign-in would lock them out of their own account.
  password: z.string().min(1, "Password is required"),
});
export type LoginValues = z.infer<typeof loginSchema>;

export const registerSchema = z
  .object({
    fullName: z
      .string()
      .trim()
      .min(2, "Enter your full name")
      .max(150, "That name is too long"),
    email: emailField,
    password: passwordField,
    confirmPassword: z.string().min(1, "Confirm your password"),
    organizationName: z
      .string()
      .trim()
      .min(2, "Enter your organization's name")
      .max(150, "That name is too long"),
  })
  .refine((values) => values.password === values.confirmPassword, {
    message: "Passwords do not match",
    // Reported on the confirm field, where the user can actually fix it.
    path: ["confirmPassword"],
  });
export type RegisterValues = z.infer<typeof registerSchema>;

export const forgotPasswordSchema = z.object({ email: emailField });
export type ForgotPasswordValues = z.infer<typeof forgotPasswordSchema>;

export const resetPasswordSchema = z
  .object({
    token: z.string().min(1),
    newPassword: passwordField,
    confirmPassword: z.string().min(1, "Confirm your password"),
  })
  .refine((values) => values.newPassword === values.confirmPassword, {
    message: "Passwords do not match",
    path: ["confirmPassword"],
  });
export type ResetPasswordValues = z.infer<typeof resetPasswordSchema>;

/**
 * How strong a password looks, for the meter on the register form.
 *
 * <p>Scored against the rules the backend actually enforces rather than an abstract
 * entropy estimate, so "strong" here means "will be accepted", which is the only
 * thing the person filling in the form cares about at that moment.</p>
 */
export function passwordStrength(password: string): {
  score: 0 | 1 | 2 | 3 | 4;
  label: string;
  met: { length: boolean; lower: boolean; upper: boolean; digit: boolean; symbol: boolean };
} {
  const met = {
    length: password.length >= 12,
    lower: /[a-z]/.test(password),
    upper: /[A-Z]/.test(password),
    digit: /\d/.test(password),
    symbol: /[^A-Za-z0-9]/.test(password),
  };

  const satisfied = Object.values(met).filter(Boolean).length;
  const score = Math.max(0, satisfied - 1) as 0 | 1 | 2 | 3 | 4;
  const label = ["Too weak", "Weak", "Fair", "Good", "Strong"][score];

  return { score, label, met };
}
