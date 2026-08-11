import type { OrganizationSummary } from "@/lib/api/schemas";

export type OrgResolution =
  | { kind: "resolved"; organization: OrganizationSummary }
  | { kind: "no-memberships" }
  | { kind: "unknown-slug"; available: OrganizationSummary[] };

/**
 * Maps the `[orgSlug]` in the URL onto the organization the backend actually wants.
 *
 * <p>The URL carries a slug because `/app/acme-robotics/dashboard` is a link someone
 * can read, paste into Slack and recognise; the backend keys everything on the UUID.
 * The session already lists every membership with both, so this costs no request.</p>
 *
 * <p>Returns a discriminated union rather than throwing or returning null: "you
 * belong to nothing yet" and "that slug is not one of yours" want completely
 * different screens, and collapsing them into a 404 loses the distinction — the
 * second case can offer the organizations the user does belong to.</p>
 */
export function resolveOrganization(
  slug: string | undefined,
  organizations: OrganizationSummary[],
): OrgResolution {
  if (organizations.length === 0) {
    return { kind: "no-memberships" };
  }

  if (!slug) {
    return { kind: "resolved", organization: organizations[0] };
  }

  // Case-insensitive: slugs are lower-case, but URLs get typed and pasted by hand.
  const normalized = slug.toLowerCase();
  const match = organizations.find((org) => org.slug.toLowerCase() === normalized);

  return match
    ? { kind: "resolved", organization: match }
    : { kind: "unknown-slug", available: organizations };
}

/** The organization a signed-in user should land on when no slug is given. */
export function defaultOrganization(
  organizations: OrganizationSummary[],
  activeOrganizationId?: string | null,
): OrganizationSummary | null {
  if (organizations.length === 0) return null;
  // Prefer whichever the backend considers active — it is the one the JWT is scoped
  // to, so landing anywhere else would immediately need an org switch.
  return (
    organizations.find((org) => org.id === activeOrganizationId) ?? organizations[0]
  );
}
