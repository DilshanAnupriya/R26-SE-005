import { cn } from "@/lib/utils";

/**
 * The SprintIQ mark.
 *
 * <p>Drawn as SVG rather than shipped as a raster: it appears at 22px in the top bar
 * and 30px in the sidebar, stays sharp at any density, and picks up the theme through
 * currentColor instead of needing a second file for dark mode.</p>
 *
 * <p>The glyph is a sprint burndown — a descending stroke over a rising bar — which
 * is what the product is actually about, rather than an abstract shape that could
 * belong to anything.</p>
 */
export function BrandMark({ size = 26, className }: { size?: number; className?: string }) {
  return (
    <span
      className={cn(
        "inline-flex flex-none items-center justify-center rounded-[8px] text-white shadow-card",
        className,
      )}
      style={{
        width: size,
        height: size,
        background: "linear-gradient(135deg, var(--accent), var(--violet))",
      }}
      aria-hidden
    >
      <svg
        width={size * 0.62}
        height={size * 0.62}
        viewBox="0 0 16 16"
        fill="none"
        stroke="currentColor"
        strokeWidth={1.9}
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        {/* Burning down, left to right. */}
        <path d="M2 3.5 L14 12.5" opacity={0.55} />
        {/* Delivered work, rising. */}
        <path d="M3.2 12.8 L3.2 10.4" />
        <path d="M7 12.8 L7 8" />
        <path d="M10.8 12.8 L10.8 5.6" />
      </svg>
    </span>
  );
}

/** Product lockup for the top bar: mark plus wordmark. */
export function BrandLockup({ className }: { className?: string }) {
  return (
    <span className={cn("flex flex-none items-center gap-2", className)}>
      <BrandMark size={24} />
      <span className="text-md font-semibold tracking-[-0.02em]">SprintIQ</span>
      <span className="sr-only">SprintIQ home</span>
    </span>
  );
}
