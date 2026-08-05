"use client";

import Link from "next/link";
import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Sidebar } from "./sidebar";
import { Topbar } from "./topbar";
import { CommandPalette } from "./command-palette";
import { useSession } from "@/components/session-provider";
import { resolveOrganization } from "@/features/organizations/resolve-org";

/**
 * The signed-in chrome, and the place where `[orgSlug]` becomes a real organization.
 *
 * <p>The proxy only checked that a cookie existed. This is where the session is
 * actually known, so it is also where the three things that can go wrong get
 * distinct answers: still loading, no memberships at all, or a slug that is not
 * one of yours.</p>
 */
export function AppShell({
  orgSlug,
  children,
}: {
  orgSlug: string;
  children: React.ReactNode;
}) {
  const router = useRouter();
  const { status, user, activeOrganizationId, setActiveOrganizationId, switchOrganization } =
    useSession();

  const resolution = resolveOrganization(orgSlug, user?.organizations ?? []);
  const resolvedId = resolution.kind === "resolved" ? resolution.organization.id : null;

  /**
   * Keeps the tenant header in step with the URL. Someone who pastes a link to
   * another of their organizations must not have their requests scoped to whichever
   * one the session happened to start in.
   */
  useEffect(() => {
    if (!resolvedId || resolvedId === activeOrganizationId) return;

    setActiveOrganizationId(resolvedId);
    // The JWT carries the tenant claim too, so the token has to be re-minted —
    // setting the header alone would send a request the backend rejects.
    void switchOrganization(resolvedId).catch(() => {
      router.replace("/app");
    });
  }, [resolvedId, activeOrganizationId, setActiveOrganizationId, switchOrganization, router]);

  if (status === "loading") {
    return (
      <div className="flex min-h-svh items-center justify-center" aria-live="polite">
        <Loader2 className="size-5 animate-spin text-muted-foreground" aria-hidden />
        <span className="sr-only">Loading your workspace</span>
      </div>
    );
  }

  if (status === "anonymous") {
    // The provider already redirects; this is the frame or two before it lands.
    return null;
  }

  if (resolution.kind === "no-memberships") {
    return (
      <CentredMessage
        title="You are not in any organization yet"
        body="Ask a colleague for an invitation, or create your own workspace to get started."
        action={
          <Button asChild>
            <Link href="/register">Create a workspace</Link>
          </Button>
        }
      />
    );
  }

  if (resolution.kind === "unknown-slug") {
    // Not a 404: we know exactly which organizations this person does belong to,
    // and offering them is more useful than a dead end.
    return (
      <CentredMessage
        title="That workspace is not one of yours"
        body="You may have followed a link meant for a different account."
        action={
          <div className="flex flex-col gap-2">
            {resolution.available.map((org) => (
              <Button key={org.id} asChild variant="outline">
                <Link href={`/app/${org.slug}/dashboard`}>Go to {org.name}</Link>
              </Button>
            ))}
          </div>
        }
      />
    );
  }

  return (
    <div className="flex min-h-svh">
      <Sidebar orgSlug={resolution.organization.slug} />
      <div className="flex min-w-0 flex-1 flex-col">
        <Topbar />
        {/* The landmark a "skip to content" link targets. */}
        <main id="main" className="flex-1 overflow-auto p-6">
          {children}
        </main>
      </div>
      <CommandPalette orgSlug={resolution.organization.slug} />
    </div>
  );
}

function CentredMessage({
  title,
  body,
  action,
}: {
  title: string;
  body: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="flex min-h-svh items-center justify-center px-4">
      <div className="max-w-sm space-y-3 text-center">
        <h1 className="text-lg font-semibold tracking-tight">{title}</h1>
        <p className="text-sm text-muted-foreground">{body}</p>
        {action ? <div className="pt-2">{action}</div> : null}
      </div>
    </div>
  );
}
