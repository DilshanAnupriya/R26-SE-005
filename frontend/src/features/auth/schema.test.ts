import { describe, expect, it } from "vitest";
import {
  loginSchema,
  passwordStrength,
  registerSchema,
  resetPasswordSchema,
} from "./schema";

const VALID_PASSWORD = "Str0ng!Passw0rd#2026";

describe("registerSchema", () => {
  const valid = {
    fullName: "Dev User",
    email: "dev@example.com",
    password: VALID_PASSWORD,
    confirmPassword: VALID_PASSWORD,
    organizationName: "Acme Robotics",
  };

  it("accepts a valid registration", () => {
    expect(registerSchema.safeParse(valid).success).toBe(true);
  });

  it("mirrors the backend's password rule exactly", () => {
    // Each of these fails one clause of AuthDtos.PASSWORD_RULE. If this test and the
    // backend ever disagree, users see a server error for something the form said
    // was fine.
    const rejected = [
      "Sh0rt!Pa",              // under 12 characters
      "str0ng!password#2026",  // no upper case
      "STR0NG!PASSWORD#2026",  // no lower case
      "Strong!Password#abc",   // no digit
      "Str0ngPassw0rd2026",    // no symbol
    ];

    for (const password of rejected) {
      const result = registerSchema.safeParse({
        ...valid,
        password,
        confirmPassword: password,
      });
      expect(result.success, `expected ${password} to be rejected`).toBe(false);
    }
  });

  it("reports a password mismatch on the confirm field", () => {
    const result = registerSchema.safeParse({ ...valid, confirmPassword: "Different!Pass1234" });

    expect(result.success).toBe(false);
    // On the field the user can actually fix, not on the original password.
    expect(result.error?.issues[0].path).toEqual(["confirmPassword"]);
  });

  it("rejects a name that is only whitespace", () => {
    expect(registerSchema.safeParse({ ...valid, fullName: "   " }).success).toBe(false);
  });
});

describe("loginSchema", () => {
  it("does not apply the password complexity rule", () => {
    // An account created before a rule change must still be able to sign in;
    // refusing to submit would lock someone out of their own account.
    expect(loginSchema.safeParse({ email: "dev@example.com", password: "old" }).success).toBe(true);
  });

  it("still requires a password", () => {
    expect(loginSchema.safeParse({ email: "dev@example.com", password: "" }).success).toBe(false);
  });

  it("rejects a malformed email", () => {
    expect(loginSchema.safeParse({ email: "not-an-email", password: "x" }).success).toBe(false);
  });
});

describe("resetPasswordSchema", () => {
  it("requires the token", () => {
    const result = resetPasswordSchema.safeParse({
      token: "",
      newPassword: VALID_PASSWORD,
      confirmPassword: VALID_PASSWORD,
    });
    expect(result.success).toBe(false);
  });

  it("applies the full complexity rule to the new password", () => {
    const result = resetPasswordSchema.safeParse({
      token: "reset-token",
      newPassword: "weak",
      confirmPassword: "weak",
    });
    expect(result.success).toBe(false);
  });
});

describe("passwordStrength", () => {
  it("scores an empty password as too weak", () => {
    expect(passwordStrength("").score).toBe(0);
  });

  it("scores a password meeting every backend rule as strong", () => {
    const strength = passwordStrength(VALID_PASSWORD);

    expect(strength.score).toBe(4);
    expect(strength.label).toBe("Strong");
    expect(strength.met).toEqual({
      length: true,
      lower: true,
      upper: true,
      digit: true,
      symbol: true,
    });
  });

  it("reports which rules are unmet so the form can show them", () => {
    const strength = passwordStrength("alllowercase");

    expect(strength.met.length).toBe(true);
    expect(strength.met.upper).toBe(false);
    expect(strength.met.digit).toBe(false);
    expect(strength.met.symbol).toBe(false);
  });

  it("never reports strong for something the backend would reject", () => {
    // The meter's only job is to predict acceptance. A long lower-case-only string
    // looks substantial and would still be refused.
    expect(passwordStrength("aaaaaaaaaaaaaaaaaaaa").label).not.toBe("Strong");
  });
});
