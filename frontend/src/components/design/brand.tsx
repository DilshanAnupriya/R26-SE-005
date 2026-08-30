import Image from "next/image";
import { cn } from "@/lib/utils";

/**
 * The SprintIQ mark.
 *
 * <p>The supplied public logo contains both the large mark and wordmark. At navigation
 * sizes the wordmark is too small to read, so this component crops the source to its
 * mark and lets {@link BrandLockup} render accessible, theme-aware product text.</p>
 */
export function BrandMark({ size = 26, className }: { size?: number; className?: string }) {
  return (
    <span
      className={cn(
        "relative inline-flex flex-none overflow-hidden rounded-[8px] bg-[#07070c] shadow-card",
        className,
      )}
      style={{
        width: size,
        height: size,
      }}
      aria-hidden
    >
      <Image
        src="/SprintIQLOGO.png"
        alt=""
        width={1536}
        height={1024}
        className="pointer-events-none absolute top-0 h-auto max-w-none"
        style={{
          left: "52%",
          width: size * 2,
          transform: "translateX(-52%)",
        }}
        sizes={`${size}px`}
      />
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
