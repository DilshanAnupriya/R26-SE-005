import { AppShell } from "@/components/layout/app-shell";

/**
 * Params are typed explicitly rather than through Next's generated `LayoutProps`,
 * which only exists for routes present at the last build — a newly added route fails
 * to typecheck until something regenerates it, which makes `tsc --noEmit` depend on
 * build order.
 */
export default async function OrgLayout({
  children,
  params,
}: {
  children: React.ReactNode;
  params: Promise<{ orgSlug: string }>;
}) {
  // Route params are async from Next 15 onwards.
  const { orgSlug } = await params;
  return <AppShell orgSlug={orgSlug}>{children}</AppShell>;
}
