import { cn } from "@/lib/utils";

/**
 * The design's two repeated container shapes.
 *
 * <p>Every panel in the mockup is the same card — surface background, 1px line,
 * 12px radius, the small shadow — with an optional titled header separated by a
 * rule. Encoding it once means a spacing tweak lands everywhere instead of in
 * twenty near-identical class strings.</p>
 */
export function Card({
  className,
  children,
  ...props
}: React.ComponentProps<"div">) {
  return (
    <div
      className={cn(
        "overflow-hidden rounded-xl border border-line bg-surface shadow-card",
        className,
      )}
      {...props}
    >
      {children}
    </div>
  );
}

export function CardHeader({
  title,
  subtitle,
  action,
  className,
}: {
  title: React.ReactNode;
  subtitle?: React.ReactNode;
  action?: React.ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cn(
        "flex items-center justify-between gap-3 border-b border-line px-[18px] pt-[15px] pb-3",
        className,
      )}
    >
      <div className="min-w-0">
        <div className="text-lg font-semibold">{title}</div>
        {subtitle ? <div className="mt-px text-sm text-ink-3">{subtitle}</div> : null}
      </div>
      {action}
    </div>
  );
}

/** Section eyebrow: uppercase, tracked, muted — the design's sub-heading. */
export function SectionLabel({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <div
      className={cn(
        "text-sm font-semibold tracking-[0.05em] text-ink-3 uppercase",
        className,
      )}
    >
      {children}
    </div>
  );
}

/** Thin progress bar, used for capacity, sprint points and project progress. */
export function ProgressBar({
  value,
  colour = "var(--accent)",
  height = 8,
  className,
}: {
  /** 0–100. Clamped, because a sprint can exceed its capacity. */
  value: number;
  colour?: string;
  height?: number;
  className?: string;
}) {
  const clamped = Math.max(0, Math.min(100, value));
  return (
    <div
      className={cn("overflow-hidden rounded-full bg-surface-3", className)}
      style={{ height }}
      role="progressbar"
      aria-valuenow={Math.round(clamped)}
      aria-valuemin={0}
      aria-valuemax={100}
    >
      <div
        className="h-full rounded-full transition-[width] duration-300 ease-out-quart"
        style={{ width: `${clamped}%`, background: colour }}
      />
    </div>
  );
}

/**
 * Where a screen has no backend yet.
 *
 * <p>Says so plainly rather than showing plausible numbers. A mockup figure that
 * reads as real is worse than an obvious gap — someone will quote it in a meeting.</p>
 */
export function NotConnectedYet({
  title,
  body,
}: {
  title: string;
  body: string;
}) {
  return (
    <Card className="p-8 text-center">
      <div className="mx-auto max-w-md space-y-2">
        <h2 className="text-lg font-semibold">{title}</h2>
        <p className="text-base text-ink-2">{body}</p>
        <p className="pt-1 text-sm text-ink-3">
          The layout is built; the data arrives with its API.
        </p>
      </div>
    </Card>
  );
}
