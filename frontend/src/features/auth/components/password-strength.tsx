"use client";

import { Check, X } from "lucide-react";
import { passwordStrength } from "../schema";
import { cn } from "@/lib/utils";

const RULES = [
  { key: "length", label: "12 characters or more" },
  { key: "upper", label: "An upper case letter" },
  { key: "lower", label: "A lower case letter" },
  { key: "digit", label: "A number" },
  { key: "symbol", label: "A symbol" },
] as const;

/**
 * Live feedback against the rules the backend actually enforces.
 *
 * <p>Shows the checklist rather than a bare score, because "Weak" tells someone their
 * password is wrong without telling them what to change — and they are usually one
 * character away.</p>
 */
export function PasswordStrength({ password }: { password: string }) {
  if (!password) return null;

  const { score, label, met } = passwordStrength(password);

  return (
    <div className="mt-2 space-y-2">
      <div className="flex items-center gap-2">
        <div className="flex h-1 flex-1 gap-1" aria-hidden>
          {[0, 1, 2, 3].map((segment) => (
            <div
              key={segment}
              className={cn(
                "flex-1 rounded-full transition-colors duration-200",
                segment < score
                  ? score === 4
                    ? "bg-status-done"
                    : score >= 2
                      ? "bg-priority-medium"
                      : "bg-destructive"
                  : "bg-muted",
              )}
            />
          ))}
        </div>
        {/* aria-live so the strength is announced as it changes, not just seen. */}
        <span className="text-2xs text-muted-foreground" aria-live="polite">
          {label}
        </span>
      </div>

      <ul className="grid gap-1">
        {RULES.map((rule) => {
          const satisfied = met[rule.key];
          return (
            <li
              key={rule.key}
              className={cn(
                "flex items-center gap-1.5 text-2xs",
                satisfied ? "text-muted-foreground" : "text-muted-foreground/70",
              )}
            >
              {satisfied ? (
                <Check className="size-3 text-status-done" aria-hidden />
              ) : (
                <X className="size-3 opacity-50" aria-hidden />
              )}
              <span>{rule.label}</span>
              <span className="sr-only">{satisfied ? "met" : "not met"}</span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
