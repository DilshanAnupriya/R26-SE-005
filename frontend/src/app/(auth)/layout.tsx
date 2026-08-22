import Link from "next/link";
import { Sparkles } from "lucide-react";

/**
 * Auth pages sit on a centred card against a quiet, gradient-washed field.
 *
 * <p>Restrained on purpose: this screen is a checkpoint, not a destination. The
 * marketing surface does the persuading; here the job is to get someone through with
 * as little to look at as possible.</p>
 */
export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="relative flex min-h-svh flex-col items-center justify-center px-4 py-12">
      {/* Decorative only — hidden from assistive tech, which has nothing to gain here. */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 -z-10 bg-[radial-gradient(60%_50%_at_50%_0%,var(--color-primary)/8%,transparent_70%)]"
      />

      <Link
        href="/"
        className="mb-8 flex items-center gap-2 font-semibold tracking-tight transition-opacity hover:opacity-80"
      >
        <span className="flex size-7 items-center justify-center rounded-md bg-primary text-primary-foreground">
          <Sparkles className="size-4" />
        </span>
        SprintIQ
      </Link>

      <main className="w-full max-w-sm">{children}</main>

      <p className="mt-8 text-xs text-muted-foreground">
        © {new Date().getFullYear()} SprintIQ
      </p>
    </div>
  );
}
