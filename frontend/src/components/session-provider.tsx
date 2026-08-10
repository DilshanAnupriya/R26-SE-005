"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { ApiClient } from "@/lib/api/client";
import { clientSessionSchema, type AuthenticatedUser } from "@/lib/api/schemas";
import { getTokenStore } from "@/lib/api/token-store";
import { getActiveOrganization, setActiveOrganization } from "@/lib/api/org-context";
import { env } from "@/lib/env";

type SessionStatus = "loading" | "authenticated" | "anonymous";

interface SessionContextValue {
  status: SessionStatus;
  user: AuthenticatedUser | null;
  api: ApiClient;
  /** Adopts a session returned by login or register. */
  adopt: (session: { accessToken: string; user: AuthenticatedUser }) => void;
  signOut: () => Promise<void>;
  switchOrganization: (organizationId: string) => Promise<AuthenticatedUser>;
  activeOrganizationId: string | null;
  setActiveOrganizationId: (organizationId: string | null) => void;
  hasPermission: (permission: string) => boolean;
}

const SessionContext = createContext<SessionContextValue | null>(null);

/** Calls a BFF route. Credentials are included so the refresh cookie travels. */
async function callBff<T>(path: string, body?: unknown): Promise<T> {
  const response = await fetch(path, {
    method: "POST",
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
    // Same-origin, and the whole point: this is the only place the cookie is used.
    credentials: "same-origin",
  });
  if (!response.ok) {
    throw new Error(`Session request failed: ${response.status}`);
  }
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
}

/**
 * Owns the session for the whole client.
 *
 * <p>The access token lives in {@code TokenStore} rather than in React state: it is
 * read by the API client on every request, and routing it through a re-render would
 * make each request depend on React's scheduling. React state holds only what the UI
 * renders — the user and the status.</p>
 */
export function SessionProvider({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const [status, setStatus] = useState<SessionStatus>("loading");
  const [user, setUser] = useState<AuthenticatedUser | null>(null);
  const [activeOrganizationId, setActiveOrganizationId] = useState<string | null>(null);

  const tokenStore = useMemo(() => getTokenStore(), []);

  const api = useMemo(
    () =>
      new ApiClient({
        baseUrl: env.NEXT_PUBLIC_API_URL,
        tokenStore,
        // A module-level getter rather than a closure over a ref: the client is built
        // during render, and anything it reads from React's own storage has to be read
        // at request time instead — which is what this is.
        getOrganizationId: getActiveOrganization,
      }),
    [tokenStore],
  );

  /**
   * Changes the active organization, header first.
   *
   * <p>Synchronous rather than through an effect: the shell resolves the org from the
   * URL slug and the queries for that org fire immediately afterwards. An effect would
   * order the header update against those refetches by luck, and losing the race sends
   * the first page of requests to the previous tenant.</p>
   */
  const applyOrganization = useCallback((organizationId: string | null) => {
    setActiveOrganization(organizationId);
    setActiveOrganizationId(organizationId);
  }, []);

  const adopt = useCallback(
    (session: { accessToken: string; user: AuthenticatedUser }) => {
      tokenStore.setAccessToken(session.accessToken);
      setUser(session.user);
      applyOrganization(session.user.activeOrganizationId ?? null);
      setStatus("authenticated");
    },
    [tokenStore, applyOrganization],
  );

  const signOut = useCallback(async () => {
    // Clear locally first: if the network call fails, the user must still end up
    // signed out of this browser. The cookie is cleared by the route regardless.
    tokenStore.clear();
    setUser(null);
    applyOrganization(null);
    setStatus("anonymous");
    try {
      await callBff("/api/auth/logout");
    } finally {
      router.replace("/login");
    }
  }, [tokenStore, router, applyOrganization]);

  const switchOrganization = useCallback(
    async (organizationId: string) => {
      const session = clientSessionSchema.parse(
        await callBff("/api/auth/switch-org", { organizationId }),
      );
      adopt(session);
      return session.user;
    },
    [adopt],
  );

  /**
   * Wires the token store to the BFF once. The refresh function is the ONLY place
   * a refresh is triggered from, which is what makes the single-flight guarantee
   * meaningful.
   */
  useEffect(() => {
    tokenStore.configure({
      refresh: async () => {
        const session = clientSessionSchema.parse(await callBff("/api/auth/refresh"));
        setUser(session.user);
        return session.accessToken;
      },
      onSessionExpired: () => {
        setUser(null);
        setStatus("anonymous");
        router.replace("/login");
      },
    });
  }, [tokenStore, router]);

  /**
   * Boot: exchange the cookie for an access token. This is what allows memory-only
   * token storage to survive a hard refresh — the browser kept nothing, but the
   * httpOnly cookie is still there.
   *
   * <p>Latched to one exchange per provider instance. The exchange rotates the refresh
   * token, and the backend treats a rotated token presented twice as theft — it revokes
   * the whole family and signs the user out of every device. StrictMode's double-invoke
   * in development fires this effect twice with the same cookie, which is exactly that
   * shape; the `cancelled` flag below only discards the second <i>result</i>, long after
   * the second request has already gone out.</p>
   */
  const bootRef = useRef<Promise<unknown> | null>(null);

  useEffect(() => {
    let cancelled = false;

    // The promise is latched, not the effect. Latching the effect instead would let
    // StrictMode's cleanup cancel the one exchange that was allowed to start, leaving
    // the app stuck on "loading" — the second run would find the guard closed and
    // attach no handlers to anything.
    bootRef.current ??= callBff("/api/auth/session");

    bootRef.current
      .then((payload) => {
        if (cancelled) return;
        adopt(clientSessionSchema.parse(payload));
      })
      .catch(() => {
        if (cancelled) return;
        // No cookie, or the backend rejected it. Anonymous is the correct answer,
        // and the proxy has already redirected anyone who needed a session.
        setStatus("anonymous");
      });

    return () => {
      cancelled = true;
    };
  }, [adopt]);

  const value = useMemo<SessionContextValue>(
    () => ({
      status,
      user,
      api,
      adopt,
      signOut,
      switchOrganization,
      activeOrganizationId,
      setActiveOrganizationId: applyOrganization,
      hasPermission: (permission: string) => user?.permissions.includes(permission) ?? false,
    }),
    [status, user, api, adopt, signOut, switchOrganization, activeOrganizationId, applyOrganization],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const context = useContext(SessionContext);
  if (!context) {
    throw new Error("useSession() must be used inside <SessionProvider>");
  }
  return context;
}
