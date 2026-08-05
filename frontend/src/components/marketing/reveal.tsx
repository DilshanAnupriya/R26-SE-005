"use client";

import { useEffect, useRef, useState } from "react";
import { DURATION, EASE, REVEAL_FROM, REVEAL_TO, STAGGER, prefersReducedMotion } from "@/lib/motion";
import { cn } from "@/lib/utils";

/**
 * Scroll-triggered reveal for marketing sections.
 *
 * <p>GSAP and ScrollTrigger are imported dynamically inside the effect rather than at
 * module scope. They are a meaningful amount of JavaScript that only the marketing
 * route needs, and a static import would put them in a shared chunk that the signed-in
 * app pays for on every load.</p>
 *
 * <p>Children start visible in the markup and are hidden by GSAP only once it has
 * loaded and confirmed motion is wanted. The reverse — hidden in CSS, revealed by JS —
 * leaves the page blank for anyone whose JavaScript fails, and blank is a worse
 * failure than unanimated.</p>
 */
export function Reveal({
  children,
  className,
  stagger = false,
  delay = 0,
  as: Tag = "div",
}: {
  children: React.ReactNode;
  className?: string;
  /** Animate direct children in sequence rather than the container as one block. */
  stagger?: boolean;
  delay?: number;
  as?: "div" | "section" | "ul" | "header";
}) {
  const container = useRef<HTMLElement>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    const element = container.current;
    if (!element || prefersReducedMotion()) {
      // Nothing to do: the content is already visible in the markup.
      return;
    }

    let cleanup: (() => void) | undefined;
    let cancelled = false;

    /**
     * GSAP pauses its ticker while the document is hidden. Since the reveal works by
     * hiding content and animating it back, starting one in a background tab leaves
     * the section stuck at whatever opacity it reached — a blank page for anyone who
     * opened the link in a new tab. So wait until the page is actually visible before
     * touching anything; until then the markup stands on its own.
     */
    function whenVisible(run: () => void) {
      if (document.visibilityState === "visible") {
        run();
        return;
      }
      const onVisible = () => {
        if (document.visibilityState !== "visible") return;
        document.removeEventListener("visibilitychange", onVisible);
        if (!cancelled) run();
      };
      document.addEventListener("visibilitychange", onVisible);
      cleanup = () => document.removeEventListener("visibilitychange", onVisible);
    }

    void (async () => {
      const [{ gsap }, { ScrollTrigger }] = await Promise.all([
        import("gsap"),
        import("gsap/ScrollTrigger"),
      ]);
      if (cancelled) return;

      gsap.registerPlugin(ScrollTrigger);

      whenVisible(() => {
        setReady(true);

        // gsap.context scopes the selector below to this element and gives us a
        // single revert() that kills the tween AND its ScrollTrigger on unmount.
        const context = gsap.context(() => {
          const targets = stagger ? Array.from(element.children) : element;

          gsap.fromTo(
            targets,
            REVEAL_FROM,
            {
              ...REVEAL_TO,
              duration: DURATION.reveal,
              ease: EASE.out,
              delay,
              stagger: stagger ? STAGGER : 0,
              scrollTrigger: {
                trigger: element,
                // Fires a little before the element is fully in view, so the motion
                // has finished by the time the reader's eye arrives.
                start: "top 85%",
                // Reveals do not reverse: re-animating on the way back up turns a
                // long page into a flicker show.
                once: true,
              },
            },
          );
        }, element);

        cleanup = () => context.revert();
      });
    })();

    return () => {
      cancelled = true;
      cleanup?.();
    };
  }, [stagger, delay]);

  return (
    <Tag
      ref={container as React.Ref<never>}
      // Only opt into GPU hinting once an animation is actually about to run;
      // will-change left on permanently costs memory for no benefit.
      className={cn(className, ready && "will-change-[transform,opacity]")}
    >
      {children}
    </Tag>
  );
}
