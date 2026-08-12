"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { Loader2 } from "lucide-react";
import { useSession } from "@/components/session-provider";
import { defaultOrganization } from "@/features/organizations/resolve-org";

/**
 * The slug-less entry point. Login, the proxy and the org switcher all send people
 * here rather than guessing a slug; this is the one place that decides which
 * organization "the app" means for a given user.
 */
export default function AppIndexPage() {
  const router = useRouter();
  const { status, user, activeOrganizationId } = useSession();

  useEffect(() => {
    if (status === "loading") return;
    if (status === "anonymous") {
      router.replace("/login");
      return;
    }
    const organization = defaultOrganization(user?.organizations ?? [], activeOrganizationId);
    router.replace(organization ? `/app/${organization.slug}/dashboard` : "/app/none/dashboard");
  }, [status, user, activeOrganizationId, router]);

  return (
    <div className="flex min-h-svh items-center justify-center" aria-live="polite">
      <Loader2 className="size-5 animate-spin text-muted-foreground" aria-hidden />
      <span className="sr-only">Opening your workspace</span>
    </div>
  );
}
