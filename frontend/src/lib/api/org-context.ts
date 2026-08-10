/**
 * The organization every API request is made against.
 *
 * <p>Module scope rather than React state, for the same reason the access token is:
 * it is read by the API client's header builder on every request, and routing it
 * through a re-render would make each request depend on React's scheduling. The
 * session provider owns writing it; nothing else should.</p>
 *
 * <p>Writes are ignored on the server. A module-level variable there is one value per
 * server process shared by every concurrent request, which is one tenant's header
 * attached to another tenant's call. Nothing server-side uses this client — server
 * code goes through {@code src/server/backend.ts} — so refusing the write is free.</p>
 */
let activeOrganizationId: string | null = null;

export function setActiveOrganization(organizationId: string | null): void {
  if (typeof window === "undefined") return;
  activeOrganizationId = organizationId;
}

export function getActiveOrganization(): string | null {
  return typeof window === "undefined" ? null : activeOrganizationId;
}
