/**
 * The shared motion vocabulary.
 *
 * <p>Two budgets, deliberately far apart. In the app, motion confirms that something
 * happened and shows where it came from, so anything over ~200ms is a defect — the
 * user is working. On the marketing page motion paces a story the visitor is reading,
 * so it can afford to be slower.</p>
 *
 * <p>Easing is directional: things arriving decelerate, things leaving accelerate
 * away, and springs are reserved for direct manipulation where the user's own gesture
 * justifies the overshoot.</p>
 */
export const DURATION = {
  /** Instant feedback: a press, a hover, a checkbox. */
  feedback: 0.12,
  /** Something appearing: a dropdown, a tooltip, a toast. */
  enter: 0.2,
  /** Something leaving. Slightly quicker — nobody wants to wait for a goodbye. */
  exit: 0.15,
  /** Marketing reveals. */
  reveal: 0.7,
  /** Longer marketing beats: a drawn line, a counter running up. */
  narrative: 1.1,
} as const;

export const EASE = {
  /** Arriving. Fast at first, settling gently. */
  out: "power3.out",
  /** Leaving. Slow to release, then gone. */
  in: "power2.in",
  /** Moving between two places on screen. */
  inOut: "power2.inOut",
  /** Direct manipulation only — a drag settling, a toggle snapping. */
  spring: "back.out(1.6)",
  /** Scrubbed sequences must be linear or scroll and position stop matching. */
  none: "none",
} as const;

/**
 * Whether the visitor has asked for less motion.
 *
 * <p>CSS covers transitions and keyframes globally in `globals.css`, but a
 * JS-driven timeline is invisible to that rule, so every GSAP entry point checks
 * this too. Returns true during SSR so the server never emits markup that assumes an
 * animation will run.</p>
 */
export function prefersReducedMotion(): boolean {
  if (typeof window === "undefined" || !window.matchMedia) return true;
  return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

/**
 * Standard reveal geometry: a small rise with a fade.
 *
 * <p>16px, not 60. A large travel distance reads as decorative and, on a long page,
 * as things constantly moving in the corner of the eye. The point is to draw
 * attention to arrival, not to perform.</p>
 */
export const REVEAL_FROM = { opacity: 0, y: 16 } as const;
export const REVEAL_TO = { opacity: 1, y: 0 } as const;

/** Gap between staggered siblings — enough to read as a sequence, not a queue. */
export const STAGGER = 0.08;
