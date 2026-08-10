"use client";

import { useSyncExternalStore } from "react";

/**
 * Two things the app needs from outside React: whether hydration has happened, and
 * what time it is.
 *
 * <p>Both used to be a {@code useState} plus an effect that immediately called its
 * setter. That works, but it is a cascading render by construction — React commits,
 * the effect fires, state changes, React commits again — and it is what
 * {@code react-hooks/set-state-in-effect} exists to catch. {@code useSyncExternalStore}
 * is the primitive for exactly this shape: it has a server snapshot, so there is no
 * hydration mismatch to paper over, and no state to keep in sync.</p>
 */

/** Nothing to subscribe to — the value is constant once the client is running. */
function subscribeNever(): () => void {
  return () => {};
}

const HYDRATED = () => true;
const NOT_HYDRATED = () => false;

/**
 * False during server rendering and the hydration pass, true afterwards.
 *
 * <p>Used where the correct markup depends on something only the browser knows — the
 * resolved colour theme, for instance. Rendering the wrong icon first and swapping it
 * produces a visible flip on every page load.</p>
 */
export function useHydrated(): boolean {
  return useSyncExternalStore(subscribeNever, HYDRATED, NOT_HYDRATED);
}

/**
 * One interval for the whole app, shared by every component that shows a clock.
 *
 * <p>Ticking whole seconds rather than milliseconds keeps the snapshot stable between
 * reads, which {@code useSyncExternalStore} requires — a getter returning
 * {@code Date.now()} would report a new value on every call and never settle.</p>
 */
const listeners = new Set<() => void>();
let ticker: ReturnType<typeof setInterval> | null = null;

function subscribeToSeconds(onChange: () => void): () => void {
  listeners.add(onChange);
  ticker ??= setInterval(() => {
    for (const listener of listeners) listener();
  }, 1000);

  return () => {
    listeners.delete(onChange);
    if (listeners.size === 0 && ticker !== null) {
      clearInterval(ticker);
      ticker = null;
    }
  };
}

const nowSeconds = () => Math.floor(Date.now() / 1000);
const noTime = () => null;

/**
 * The current time in whole seconds, or null before hydration.
 *
 * <p>Null on the server because the server's clock is not the viewer's, and rendering
 * one then hydrating with the other is a mismatch React will complain about.</p>
 */
export function useNowSeconds(): number | null {
  return useSyncExternalStore(subscribeToSeconds, nowSeconds, noTime);
}
